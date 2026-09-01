package com.tannmenghong.tbchat.domain.repository

import com.tannmenghong.tbchat.domain.model.AppRelease
import com.tannmenghong.tbchat.domain.model.AppSettings
import com.tannmenghong.tbchat.domain.model.AppVersion
import com.tannmenghong.tbchat.domain.model.Conversation
import com.tannmenghong.tbchat.domain.model.DownloadJob
import com.tannmenghong.tbchat.domain.model.InstalledModel
import com.tannmenghong.tbchat.domain.model.Message
import com.tannmenghong.tbchat.domain.model.NetworkEvent
import com.tannmenghong.tbchat.domain.model.UpdateCheck
import com.tannmenghong.tbchat.domain.model.UpdateProgress
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    /** Everything the app knows about, installed or not. */
    fun catalog(): Flow<List<AiModel>>

    fun installedModels(): Flow<List<InstalledModel>>

    suspend fun getModel(id: String): AiModel?

    suspend fun getInstalled(id: String): InstalledModel?

    suspend fun isInstalled(id: String): Boolean

    suspend fun deleteModel(id: String): Result<Long>

    /** Copies an external GGUF into app storage, parsing its header for metadata. */
    suspend fun importModel(uriString: String, displayName: String): Result<AiModel>

    suspend fun markUsed(id: String)

    suspend fun verifyIntegrity(id: String): Result<Boolean>

    /** Search the Hugging Face Hub. Requires network; returns empty in offline mode. */
    suspend fun searchRemote(query: String, limit: Int = 30): Result<List<AiModel>>
}

interface DownloadRepository {
    fun activeJobs(): Flow<List<DownloadJob>>

    fun jobsForModel(modelId: String): Flow<List<DownloadJob>>

    suspend fun enqueue(model: AiModel): Result<Unit>

    suspend fun pause(jobId: String)

    suspend fun resume(jobId: String)

    suspend fun cancel(jobId: String)

    suspend fun retry(jobId: String)
}

interface ConversationRepository {
    fun conversations(): Flow<List<Conversation>>

    fun messages(conversationId: String): Flow<List<Message>>

    suspend fun getConversation(id: String): Conversation?

    suspend fun createConversation(modelId: String?, systemPrompt: String?): Conversation

    suspend fun updateConversation(conversation: Conversation)

    suspend fun deleteConversation(id: String)

    suspend fun addMessage(message: Message): Message

    suspend fun updateMessage(message: Message)

    suspend fun deleteMessage(id: String)

    /** Drops the assistant turn and everything after it, for Regenerate. */
    suspend fun truncateFrom(conversationId: String, messageId: String)

    suspend fun history(conversationId: String): List<ChatMessage>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface DeviceRepository {
    val profile: Flow<DeviceProfile>

    suspend fun refresh(): DeviceProfile

    suspend fun current(): DeviceProfile
}

interface NetworkLogRepository {
    fun events(): Flow<List<NetworkEvent>>

    suspend fun record(host: String, purpose: String, bytes: Long)

    suspend fun clear()
}

/**
 * Checks the distribution channel for a newer build and prepares its APK for
 * installation. Deliberately never installs silently: [installPrepared] hands
 * the verified file to Android's package installer, which shows the system
 * confirmation dialog, and installs require the user's "unknown sources" grant.
 */
interface UpdateRepository {
    /** The version currently running, read from the installed package. */
    val currentVersion: AppVersion

    /** Contacts the channel (unless offline mode is on) and compares versions. */
    suspend fun check(): Result<UpdateCheck>

    /**
     * Downloads the release APK to app cache, verifies its size and digest, and
     * emits progress. A cold flow: cancelling collection cancels the download.
     */
    fun downloadAndPrepare(release: AppRelease): Flow<UpdateProgress>

    /** Launches the system package installer for an already-verified APK. */
    fun installPrepared(apkPath: String)

    /** False when the user has not granted permission to install from this app. */
    fun canRequestInstall(): Boolean

    /** Opens the system screen where the install-unknown-apps grant is given. */
    fun requestInstallPermission()
}
