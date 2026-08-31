package com.tannmenghong.tbchat.inference.llamacpp

import android.util.Log
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.ChatEngine
import com.tannmenghong.tbchat.inference.api.ChatEvent
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.EngineState
import com.tannmenghong.tbchat.inference.api.GenerationStats
import com.tannmenghong.tbchat.inference.api.InferenceError
import com.tannmenghong.tbchat.inference.api.LoadOptions
import com.tannmenghong.tbchat.inference.api.ModelFormat
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.RuntimeId
import com.tannmenghong.tbchat.inference.api.SamplingParams
import com.tannmenghong.tbchat.inference.api.StopReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The GGUF chat engine.
 *
 * All native work happens on one dedicated thread. That is not a performance
 * choice -- llama.cpp spawns its own worker pool internally -- it is a
 * correctness one: a native handle must never be touched from two threads, and
 * pinning everything to a single thread makes that structural rather than a rule
 * to remember.
 */
@Singleton
class LlamaCppChatEngine @Inject constructor() : ChatEngine {

    override val runtimeId: RuntimeId = RuntimeId.LLAMA_CPP

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val nativeThread: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-native") }.asCoroutineDispatcher()

    private val loadLock = Mutex()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loaded: AiModel? = null

    val isAvailable: Boolean get() = LlamaNative.isAvailable

    override fun supports(model: AiModel): Boolean =
        LlamaNative.isAvailable &&
            model.format == ModelFormat.GGUF &&
            model.requiredRuntime == RuntimeId.LLAMA_CPP

    override suspend fun load(
        model: AiModel,
        modelPath: String,
        options: LoadOptions
    ): Result<Unit> = loadLock.withLock {
        if (!LlamaNative.isAvailable) {
            _state.value = EngineState.Failed(InferenceError.EngineUnavailable)
            return Result.failure(InferenceError.EngineUnavailable.asException())
        }

        val file = File(modelPath)
        if (!file.isFile || file.length() == 0L) {
            val error = InferenceError.ModelFileMissing(modelPath)
            _state.value = EngineState.Failed(error)
            return Result.failure(error.asException())
        }

        unloadInternal()
        _state.value = EngineState.Loading(0f)

        return withContext(nativeThread) {
            val progress = object : LlamaNative.LoadProgress {
                override fun onLoadProgress(progress: Float) {
                    _state.value = EngineState.Loading(progress.coerceIn(0f, 1f))
                }
            }

            val kvCode = if (options.kvCacheType == Quantization.Q8_0) 8 else 1
            // A larger logical batch speeds prefill but costs a proportionally
            // larger compute buffer. 512 is the knee on phone-class memory.
            val nBatch = 512

            val newHandle = runCatching {
                LlamaNative.nativeLoad(
                    modelPath = file.absolutePath,
                    nCtx = options.contextLength,
                    nBatch = nBatch,
                    nThreads = options.threads,
                    kvTypeCode = kvCode,
                    flashAttn = options.flashAttention,
                    nGpuLayers = options.gpuLayers,
                    progress = progress
                )
            }.getOrElse { throwable ->
                Log.e(TAG, "native load threw", throwable)
                0L
            }

            if (newHandle == 0L) {
                val error = InferenceError.CorruptModel("llama.cpp could not open ${file.name}")
                _state.value = EngineState.Failed(error)
                return@withContext Result.failure(error.asException())
            }

            handle = newHandle
            loaded = model
            _state.value = EngineState.Ready(
                model = model,
                accelerator = options.accelerator,
                residentBytes = estimateResidentBytes(model, options),
                contextLength = LlamaNative.nativeContextSize(newHandle)
            )
            Log.i(TAG, "loaded ${model.displayName}: ${LlamaNative.nativeDescribe(newHandle)}")
            Result.success(Unit)
        }
    }

    override suspend fun unload() = loadLock.withLock { unloadInternal() }

    private suspend fun unloadInternal() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        loaded = null
        withContext(nativeThread) { LlamaNative.nativeFree(current) }
        _state.value = EngineState.Idle
    }

    override fun estimateResidentBytes(model: AiModel, options: LoadOptions): Long =
        com.tannmenghong.tbchat.domain.compat.ModelCompatibilityChecker
            .estimateResidentBytes(model, options.contextLength, options.kvCacheType)

    override suspend fun tokenCount(text: String): Int {
        val current = handle
        if (current == 0L) return 0
        return withContext(nativeThread) { LlamaNative.nativeTokenCount(current, text) }
    }

    override suspend fun resetPromptCache() {
        val current = handle
        if (current == 0L) return
        withContext(nativeThread) { LlamaNative.nativeResetCache(current) }
    }

    /**
     * Cancelling the collector cancels the native run. `awaitClose` fires on
     * cancellation and sets the native abort flag, which the decode loop checks
     * between tokens -- so Stop is immediate without a separate cancel API in
     * the UI layer.
     */
    override fun generate(messages: List<ChatMessage>, params: SamplingParams): Flow<ChatEvent> =
        callbackFlow {
            val current = handle
            if (current == 0L) {
                trySend(ChatEvent.Error(InferenceError.EngineUnavailable))
                close()
                return@callbackFlow
            }

            val prompt = renderPrompt(current, messages)

            val callback = object : LlamaNative.Callback {
                override fun onToken(piece: String) {
                    trySend(ChatEvent.Token(piece))
                }

                override fun onPrefill(done: Int, total: Int) {
                    trySend(ChatEvent.PromptProcessing(done, total))
                }
            }

            val job = launch(nativeThread) {
                val stats = runCatching {
                    LlamaNative.nativeGenerate(
                        handle = current,
                        prompt = prompt,
                        temperature = params.temperature,
                        topP = params.topP,
                        topK = params.topK,
                        minP = params.minP,
                        repeatPenalty = params.repeatPenalty,
                        maxTokens = params.maxTokens,
                        seed = params.seed,
                        callback = callback
                    )
                }.getOrElse { throwable ->
                    Log.e(TAG, "native generate threw", throwable)
                    trySend(ChatEvent.Error(InferenceError.NativeCrash(throwable.message ?: "unknown")))
                    close()
                    return@launch
                }

                trySend(ChatEvent.Done(stats.toGenerationStats()))
                close()
            }

            awaitClose {
                LlamaNative.nativeCancel(current)
                job.cancel()
            }
            // Unlimited, because tokens arrive from a blocking native loop that
            // cannot be back-pressured: a full channel would drop them.
        }.buffer(Channel.UNLIMITED)

    private suspend fun renderPrompt(current: Long, messages: List<ChatMessage>): String =
        withContext(nativeThread) {
            LlamaNative.nativeApplyChatTemplate(
                handle = current,
                roles = messages.map { it.role.wireName }.toTypedArray(),
                contents = messages.map { it.content }.toTypedArray(),
                addAssistant = true
            )
        }

    private val ChatMessage.Role.wireName: String
        get() = when (this) {
            ChatMessage.Role.SYSTEM -> "system"
            ChatMessage.Role.USER -> "user"
            ChatMessage.Role.ASSISTANT -> "assistant"
        }

    private fun LongArray.toGenerationStats(): GenerationStats {
        val promptTokens = this[0].toInt()
        val generated = this[1].toInt()
        val prefillMs = this[2]
        val decodeMs = this[3]
        return GenerationStats(
            promptTokens = promptTokens,
            generatedTokens = generated,
            prefillTokensPerSec = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0,
            decodeTokensPerSec = if (decodeMs > 0) generated * 1000.0 / decodeMs else 0.0,
            firstTokenMs = this[4],
            totalMs = this[5],
            contextUsed = this[6].toInt(),
            contextTotal = this[7].toInt(),
            stopReason = when (this[8].toInt()) {
                0 -> StopReason.END_OF_TURN
                1 -> StopReason.MAX_TOKENS
                2 -> StopReason.STOP_SEQUENCE
                3 -> StopReason.CANCELLED
                4 -> StopReason.CONTEXT_FULL
                else -> StopReason.ERROR
            }
        )
    }

    private companion object {
        const val TAG = "LlamaCppChatEngine"
    }
}
