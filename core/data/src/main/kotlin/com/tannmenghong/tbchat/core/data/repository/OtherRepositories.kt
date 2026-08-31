package com.tannmenghong.tbchat.core.data.repository

import com.tannmenghong.tbchat.core.data.database.ConversationDao
import com.tannmenghong.tbchat.core.data.database.ConversationEntity
import com.tannmenghong.tbchat.core.data.database.MessageDao
import com.tannmenghong.tbchat.core.data.database.MessageEntity
import com.tannmenghong.tbchat.core.data.database.NetworkEventDao
import com.tannmenghong.tbchat.core.data.database.NetworkEventEntity
import com.tannmenghong.tbchat.core.data.settings.SettingsDataSource
import com.tannmenghong.tbchat.core.device.DeviceCapabilityManager
import com.tannmenghong.tbchat.domain.model.AppSettings
import com.tannmenghong.tbchat.domain.model.Conversation
import com.tannmenghong.tbchat.domain.model.Message
import com.tannmenghong.tbchat.domain.model.NetworkEvent
import com.tannmenghong.tbchat.domain.repository.ConversationRepository
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.NetworkLogRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.api.SamplingParams
import com.tannmenghong.tbchat.core.common.IoDispatcher
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ConversationRepository {

    override fun conversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun messages(conversationId: String): Flow<List<Message>> =
        messageDao.observe(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getConversation(id: String): Conversation? = withContext(ioDispatcher) {
        conversationDao.get(id)?.toDomain()
    }

    override suspend fun createConversation(
        modelId: String?,
        systemPrompt: String?
    ): Conversation = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            modelId = modelId,
            systemPrompt = systemPrompt,
            sampling = SamplingParams(),
            createdAt = now,
            updatedAt = now
        )
        conversationDao.upsert(conversation.toEntity())
        conversation
    }

    override suspend fun updateConversation(conversation: Conversation) = withContext(ioDispatcher) {
        conversationDao.upsert(conversation.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    override suspend fun deleteConversation(id: String) = withContext(ioDispatcher) {
        messageDao.deleteAllIn(id)
        conversationDao.delete(id)
    }

    override suspend fun addMessage(message: Message): Message = withContext(ioDispatcher) {
        val stored = if (message.id.isBlank()) {
            message.copy(id = UUID.randomUUID().toString())
        } else message

        messageDao.upsert(stored.toEntity())

        // The first user turn names the thread, so the list is browsable
        // without opening every conversation.
        conversationDao.get(stored.conversationId)?.let { conversation ->
            val isFirstUserTurn = stored.role == ChatMessage.Role.USER &&
                conversationDao.messageCount(conversation.id) <= 1
            conversationDao.upsert(
                conversation.copy(
                    title = if (isFirstUserTurn) stored.content.toTitle() else conversation.title,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        stored
    }

    override suspend fun updateMessage(message: Message) = withContext(ioDispatcher) {
        messageDao.upsert(message.toEntity())
    }

    override suspend fun deleteMessage(id: String) = withContext(ioDispatcher) {
        messageDao.delete(id)
    }

    override suspend fun truncateFrom(conversationId: String, messageId: String) =
        withContext(ioDispatcher) {
            messageDao.truncateFrom(conversationId, messageId)
        }

    /**
     * The prompt handed to the engine. Errors and still-streaming placeholders
     * are excluded: feeding a failed turn back as context makes the next answer
     * worse, not better.
     */
    override suspend fun history(conversationId: String): List<ChatMessage> =
        withContext(ioDispatcher) {
            val conversation = conversationDao.get(conversationId)
            val system = conversation?.systemPrompt
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ChatMessage(ChatMessage.Role.SYSTEM, it)) }
                .orEmpty()

            system + messageDao.list(conversationId)
                .filterNot { it.isError }
                .filter { it.content.isNotBlank() }
                .map { ChatMessage(it.role.toRole(), it.content) }
        }

    private fun String.toTitle(): String =
        trim().lineSequence().first().take(60).ifBlank { "New chat" }

    private fun Conversation.toEntity() = ConversationEntity(
        id = id,
        title = title,
        modelId = modelId,
        systemPrompt = systemPrompt,
        samplingJson = json.encodeToString(SamplingParams.serializer(), sampling),
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned
    )

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        modelId = modelId,
        systemPrompt = systemPrompt,
        sampling = runCatching {
            json.decodeFromString(SamplingParams.serializer(), samplingJson)
        }.getOrDefault(SamplingParams()),
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned
    )

    private fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        content = content,
        tokenCount = tokenCount,
        decodeTokensPerSec = decodeTokensPerSec,
        firstTokenMs = firstTokenMs,
        modelIdUsed = modelIdUsed,
        createdAt = createdAt,
        isError = isError
    )

    private fun MessageEntity.toDomain() = Message(
        id = id,
        conversationId = conversationId,
        role = role.toRole(),
        content = content,
        tokenCount = tokenCount,
        decodeTokensPerSec = decodeTokensPerSec,
        firstTokenMs = firstTokenMs,
        modelIdUsed = modelIdUsed,
        createdAt = createdAt,
        isError = isError,
        // Nothing streams across a process restart; a row read from the
        // database is by definition finished.
        isStreaming = false
    )

    private fun String.toRole(): ChatMessage.Role =
        runCatching { ChatMessage.Role.valueOf(this) }.getOrDefault(ChatMessage.Role.USER)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val source: SettingsDataSource
) : SettingsRepository {
    override val settings: Flow<AppSettings> = source.settings
    override suspend fun current(): AppSettings = source.current()
    override suspend fun update(transform: (AppSettings) -> AppSettings) = source.update(transform)
}

/**
 * The device profile is a snapshot, not a stream: RAM totals and CPU features
 * do not change while the app runs. The one figure that does move -- available
 * memory -- is read fresh at load time by the inference layer rather than
 * cached here, so a stale reading can never authorise a load that will not fit.
 */
@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val manager: DeviceCapabilityManager,
    private val settings: SettingsDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DeviceRepository {

    private val _profile = MutableStateFlow(manager.profile())
    override val profile: Flow<DeviceProfile> = _profile.asStateFlow()

    override suspend fun refresh(): DeviceProfile = withContext(ioDispatcher) {
        manager.setMemoryCeilingPercent(settings.current().memoryCeilingPercent)
        manager.profile().also { _profile.value = it }
    }

    override suspend fun current(): DeviceProfile = _profile.value
}

@Singleton
class NetworkLogRepositoryImpl @Inject constructor(
    private val dao: NetworkEventDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : NetworkLogRepository {

    override fun events(): Flow<List<NetworkEvent>> =
        dao.observeRecent().map { rows ->
            rows.map { NetworkEvent(it.id, it.host, it.purpose, it.bytes, it.timestamp) }
        }

    override suspend fun record(host: String, purpose: String, bytes: Long) =
        withContext(ioDispatcher) {
            dao.insert(
                NetworkEventEntity(
                    host = host,
                    purpose = purpose,
                    bytes = bytes,
                    timestamp = System.currentTimeMillis()
                )
            )
            dao.trim(System.currentTimeMillis() - RETENTION_MS)
        }

    override suspend fun clear() = withContext(ioDispatcher) { dao.clear() }

    private companion object {
        /** A diagnostic, not an archive: two weeks is plenty to answer "what did it contact?". */
        const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    }
}
