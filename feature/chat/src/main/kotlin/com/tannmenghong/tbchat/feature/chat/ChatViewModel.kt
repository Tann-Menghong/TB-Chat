package com.tannmenghong.tbchat.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tannmenghong.tbchat.core.device.ThermalGovernor
import com.tannmenghong.tbchat.domain.model.Conversation
import com.tannmenghong.tbchat.domain.model.InstalledModel
import com.tannmenghong.tbchat.domain.model.Message
import com.tannmenghong.tbchat.domain.repository.ConversationRepository
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.ModelRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import com.tannmenghong.tbchat.inference.api.ChatEvent
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.GenerationStats
import com.tannmenghong.tbchat.inference.api.LoadOptions
import com.tannmenghong.tbchat.inference.service.InferenceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val installedModels: List<InstalledModel> = emptyList(),
    val activeModel: InstalledModel? = null,
    val loadProgress: Float? = null,
    val isGenerating: Boolean = false,
    val prefillProgress: Pair<Int, Int>? = null,
    val lastStats: GenerationStats? = null,
    val thermalNotice: String? = null,
    val error: String? = null
) {
    val canSend: Boolean get() = activeModel != null && !isGenerating && loadProgress == null
    val hasAnyModel: Boolean get() = installedModels.isNotEmpty()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val device: DeviceRepository,
    private val inference: InferenceClient,
    private val thermal: ThermalGovernor
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var generationJob: Job? = null

    /** The streaming turn, held in memory so every token is not a database write. */
    private var streamingMessageId: String? = null

    val messageStream: StateFlow<List<Message>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else conversations.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        thermal.start()

        models.installedModels()
            .onEach { installed ->
                val present = installed.filter { File(it.weightsPath).exists() }
                _ui.value = _ui.value.copy(
                    installedModels = present,
                    activeModel = _ui.value.activeModel?.let { active ->
                        present.firstOrNull { it.model.id == active.model.id }
                    }
                )
            }
            .launchIn(viewModelScope)

        combine(messageStream, thermal.policy) { messages, policy ->
            _ui.value = _ui.value.copy(
                // The in-flight assistant turn is appended from memory rather
                // than the database, so the list is never briefly missing it.
                messages = messages + listOfNotNull(inFlightMessage),
                thermalNotice = when (policy) {
                    ThermalGovernor.Policy.PAUSED ->
                        "The phone is too hot to run a model right now. Let it cool for a minute."

                    ThermalGovernor.Policy.REDUCED ->
                        "Running on fewer threads because the phone is warm."

                    ThermalGovernor.Policy.FULL -> null
                }
            )
        }.launchIn(viewModelScope)
    }

    @Volatile
    private var inFlightMessage: Message? = null

    fun open(id: String?) {
        viewModelScope.launch {
            val conversation = id?.let { conversations.getConversation(it) }
                ?: conversations.createConversation(
                    modelId = settings.current().defaultChatModelId,
                    systemPrompt = null
                )
            conversationId.value = conversation.id
            _ui.value = _ui.value.copy(conversation = conversation)

            conversation.modelId?.let { selectModel(it) }
                ?: settings.current().defaultChatModelId?.let { selectModel(it) }
        }
    }

    /**
     * Selects and loads a model.
     *
     * The load is not fire-and-forget: the user cannot send a message until it
     * finishes, because a message queued against a model that turns out not to
     * fit would fail with a confusing error several seconds later.
     */
    fun selectModel(modelId: String) {
        viewModelScope.launch {
            val installed = models.getInstalled(modelId) ?: return@launch
            val file = File(installed.weightsPath)
            if (!file.exists()) {
                _ui.value = _ui.value.copy(
                    error = "The file for ${installed.model.displayName} is missing. Download it again."
                )
                return@launch
            }

            _ui.value = _ui.value.copy(activeModel = installed, loadProgress = 0f, error = null)

            val prefs = settings.current()
            val profile = device.current()
            val options = LoadOptions(
                accelerator = prefs.acceleratorOverride ?: com.tannmenghong.tbchat.inference.api.Accelerator.CPU,
                // Performance cores only, then whatever the thermal governor
                // will currently allow.
                threads = thermal.effectiveThreads(
                    prefs.threadOverride ?: profile.cpu.performanceCores,
                    prefs.performanceMode
                ),
                contextLength = prefs.contextLength,
                gpuLayers = 0
            )

            val progressJob = launch {
                inference.state.collect { state ->
                    if (state is InferenceClient.ClientState.Loading) {
                        _ui.value = _ui.value.copy(loadProgress = state.progress)
                    }
                }
            }

            val result = inference.ensureLoaded(modelId, file.absolutePath, options)
            progressJob.cancel()

            result
                .onSuccess {
                    models.markUsed(modelId)
                    _ui.value = _ui.value.copy(loadProgress = null)
                    _ui.value.conversation?.let { conversation ->
                        conversations.updateConversation(conversation.copy(modelId = modelId))
                    }
                    settings.update { it.copy(defaultChatModelId = modelId) }
                }
                .onFailure { cause ->
                    _ui.value = _ui.value.copy(
                        loadProgress = null,
                        activeModel = null,
                        error = cause.message ?: "The model could not be loaded."
                    )
                }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return

        val conversation = _ui.value.conversation ?: return
        val model = _ui.value.activeModel ?: return

        if (thermal.isTooHotToStart()) {
            _ui.value = _ui.value.copy(
                error = "The phone is too hot to start generating. Give it a moment to cool down."
            )
            return
        }

        generationJob = viewModelScope.launch {
            conversations.addMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversation.id,
                    role = ChatMessage.Role.USER,
                    content = prompt,
                    createdAt = System.currentTimeMillis()
                )
            )
            stream(conversation.id, model.model.id)
        }
    }

    /** Drops the last assistant turn and generates a fresh one from the same prompt. */
    fun regenerate() {
        val conversation = _ui.value.conversation ?: return
        val model = _ui.value.activeModel ?: return
        val lastAssistant = _ui.value.messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }
            ?: return

        generationJob = viewModelScope.launch {
            conversations.truncateFrom(conversation.id, lastAssistant.id)
            stream(conversation.id, model.model.id)
        }
    }

    private suspend fun stream(conversationId: String, modelId: String) {
        val history = conversations.history(conversationId)
        val sampling = _ui.value.conversation?.sampling
            ?: com.tannmenghong.tbchat.inference.api.SamplingParams()

        val placeholderId = UUID.randomUUID().toString()
        streamingMessageId = placeholderId
        val builder = StringBuilder()

        inFlightMessage = Message(
            id = placeholderId,
            conversationId = conversationId,
            role = ChatMessage.Role.ASSISTANT,
            content = "",
            modelIdUsed = modelId,
            createdAt = System.currentTimeMillis(),
            isStreaming = true
        )
        _ui.value = _ui.value.copy(isGenerating = true, error = null, lastStats = null)
        publish()

        var stats: GenerationStats? = null
        var failure: String? = null

        inference.generate(history, sampling).collect { event ->
            when (event) {
                is ChatEvent.PromptProcessing -> {
                    _ui.value = _ui.value.copy(prefillProgress = event.done to event.total)
                }

                is ChatEvent.Token -> {
                    builder.append(event.text)
                    inFlightMessage = inFlightMessage?.copy(content = builder.toString())
                    _ui.value = _ui.value.copy(prefillProgress = null)
                    publish()
                }

                is ChatEvent.Done -> stats = event.stats
                is ChatEvent.Error -> failure = event.error.userMessage
            }
        }

        // Committed once, at the end: a database write per token would be a
        // hundred writes a second competing with the inference thread for I/O.
        val finished = Message(
            id = placeholderId,
            conversationId = conversationId,
            role = ChatMessage.Role.ASSISTANT,
            content = builder.toString().ifBlank { failure ?: "" },
            tokenCount = stats?.generatedTokens,
            decodeTokensPerSec = stats?.decodeTokensPerSec,
            firstTokenMs = stats?.firstTokenMs,
            modelIdUsed = modelId,
            createdAt = System.currentTimeMillis(),
            isError = failure != null
        )
        conversations.addMessage(finished)

        inFlightMessage = null
        streamingMessageId = null
        _ui.value = _ui.value.copy(
            isGenerating = false,
            prefillProgress = null,
            lastStats = stats,
            error = failure
        )
    }

    /**
     * Stops generation. The tokens already produced are kept -- a partial answer
     * the user chose to stop is still useful, and throwing it away would be
     * surprising.
     */
    fun stop() {
        generationJob?.cancel()
        generationJob = null
        _ui.value = _ui.value.copy(isGenerating = false, prefillProgress = null)
    }

    fun newConversation() {
        viewModelScope.launch {
            val model = _ui.value.activeModel?.model?.id ?: settings.current().defaultChatModelId
            val conversation = conversations.createConversation(model, null)
            conversationId.value = conversation.id
            _ui.value = _ui.value.copy(conversation = conversation, lastStats = null, error = null)
        }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    private fun publish() {
        _ui.value = _ui.value.copy(
            messages = messageStream.value.filterNot { it.id == streamingMessageId } +
                listOfNotNull(inFlightMessage)
        )
    }

    override fun onCleared() {
        thermal.stop()
        super.onCleared()
    }
}
