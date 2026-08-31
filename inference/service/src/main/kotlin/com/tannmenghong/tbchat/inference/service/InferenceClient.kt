package com.tannmenghong.tbchat.inference.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.tannmenghong.tbchat.inference.api.ChatEvent
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.GenerationStats
import com.tannmenghong.tbchat.inference.api.InferenceError
import com.tannmenghong.tbchat.inference.api.LoadOptions
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.SamplingParams
import com.tannmenghong.tbchat.inference.api.StopReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The UI-process half of the inference boundary.
 *
 * Binding is lazy and the connection is kept for the life of the app. When the
 * inference process dies -- which is a normal event, not an exception -- the
 * client surfaces it as a typed error and marks nothing as loaded, so the next
 * request rebinds and reloads rather than talking to a dead handle.
 */
@Singleton
class InferenceClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed interface ClientState {
        data object Disconnected : ClientState
        data object Connected : ClientState
        data class Loading(val modelId: String, val progress: Float) : ClientState
        data class Ready(val modelId: String, val contextLength: Int, val residentBytes: Long) :
            ClientState

        data class Failed(val error: InferenceError) : ClientState
    }

    private val _state = MutableStateFlow<ClientState>(ClientState.Disconnected)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val bindLock = Mutex()

    @Volatile
    private var service: IInferenceService? = null

    @Volatile
    private var pendingBind: ((IInferenceService?) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = IInferenceService.Stub.asInterface(binder)
            service = bound
            _state.value = ClientState.Connected
            pendingBind?.invoke(bound)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The inference process died. Expected under memory pressure; the
            // whole point of the separate process is that we get here instead of
            // taking the app down.
            Log.w(TAG, "inference process disconnected")
            service = null
            _state.value = ClientState.Failed(InferenceError.NativeCrash("process terminated"))
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            _state.value = ClientState.Disconnected
            runCatching { context.unbindService(this) }
        }
    }

    private suspend fun connect(): IInferenceService? = bindLock.withLock {
        service?.let { return it }
        suspendCancellableCoroutine { continuation ->
            pendingBind = { bound -> if (continuation.isActive) continuation.resume(bound) }
            val intent = Intent(context, InferenceService::class.java)
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                pendingBind = null
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    suspend fun isEngineAvailable(): Boolean =
        runCatching { connect()?.isEngineAvailable ?: false }.getOrDefault(false)

    suspend fun loadedModelId(): String? = runCatching { connect()?.loadedModelId() }.getOrNull()

    /**
     * Loads a model, or returns immediately when it is already resident.
     * Suspends until the engine reports ready or fails.
     */
    suspend fun ensureLoaded(
        modelId: String,
        modelPath: String,
        options: LoadOptions
    ): Result<Int> {
        val bound = connect()
            ?: return Result.failure(InferenceError.EngineUnavailable.asException())

        if (!bound.isEngineAvailable) {
            _state.value = ClientState.Failed(InferenceError.EngineUnavailable)
            return Result.failure(InferenceError.EngineUnavailable.asException())
        }

        (state.value as? ClientState.Ready)?.let { ready ->
            if (ready.modelId == modelId) return Result.success(ready.contextLength)
        }

        _state.value = ClientState.Loading(modelId, 0f)

        return suspendCancellableCoroutine { continuation ->
            val callback = object : IInferenceCallback.Stub() {
                override fun onLoadProgress(progress: Float) {
                    _state.value = ClientState.Loading(modelId, progress)
                }

                override fun onLoaded(contextLength: Int, residentBytes: Long) {
                    _state.value = ClientState.Ready(modelId, contextLength, residentBytes)
                    if (continuation.isActive) continuation.resume(Result.success(contextLength))
                }

                override fun onPrefill(done: Int, total: Int) = Unit
                override fun onToken(piece: String?) = Unit
                override fun onDone(stats: LongArray?) = Unit

                override fun onError(code: Int, message: String?) {
                    val error = InferenceError.Unknown(message ?: "Could not load the model")
                    _state.value = ClientState.Failed(error)
                    if (continuation.isActive) continuation.resume(Result.failure(error.asException()))
                }
            }

            runCatching {
                bound.load(
                    modelId,
                    modelPath,
                    options.contextLength,
                    options.threads,
                    if (options.kvCacheType == Quantization.Q8_0) 8 else 16,
                    options.flashAttention,
                    options.gpuLayers,
                    callback
                )
            }.onFailure { throwable ->
                if (continuation.isActive) {
                    continuation.resume(
                        Result.failure(InferenceError.NativeCrash(throwable.message ?: "bind lost").asException())
                    )
                }
            }
        }
    }

    fun generate(messages: List<ChatMessage>, params: SamplingParams): Flow<ChatEvent> =
        callbackFlow {
            val bound = connect()
            if (bound == null) {
                trySend(ChatEvent.Error(InferenceError.EngineUnavailable))
                close()
                return@callbackFlow
            }

            val callback = object : IInferenceCallback.Stub() {
                override fun onLoadProgress(progress: Float) = Unit
                override fun onLoaded(contextLength: Int, residentBytes: Long) = Unit

                override fun onPrefill(done: Int, total: Int) {
                    trySend(ChatEvent.PromptProcessing(done, total))
                }

                override fun onToken(piece: String?) {
                    piece?.let { trySend(ChatEvent.Token(it)) }
                }

                override fun onDone(stats: LongArray?) {
                    stats?.let { trySend(ChatEvent.Done(it.toStats())) }
                    close()
                }

                override fun onError(code: Int, message: String?) {
                    trySend(ChatEvent.Error(InferenceError.Unknown(message ?: "Generation failed")))
                    close()
                }
            }

            runCatching {
                bound.generate(
                    messages.map { it.role.wire }.toTypedArray(),
                    messages.map { it.content }.toTypedArray(),
                    params.temperature,
                    params.topP,
                    params.topK,
                    params.minP,
                    params.repeatPenalty,
                    params.maxTokens,
                    params.seed,
                    callback
                )
            }.onFailure {
                trySend(ChatEvent.Error(InferenceError.NativeCrash(it.message ?: "bind lost")))
                close()
            }

            // Collector cancellation -- the Stop button -- reaches the native
            // abort flag through here.
            awaitClose { runCatching { service?.cancel() } }
        }.buffer(Channel.UNLIMITED)

    suspend fun unload() {
        runCatching { connect()?.unload() }
        _state.value = ClientState.Connected
    }

    fun cancel() {
        runCatching { service?.cancel() }
    }

    suspend fun tokenCount(text: String): Int =
        runCatching { connect()?.tokenCount(text) ?: 0 }.getOrDefault(0)

    private val ChatMessage.Role.wire: String
        get() = when (this) {
            ChatMessage.Role.SYSTEM -> "system"
            ChatMessage.Role.USER -> "user"
            ChatMessage.Role.ASSISTANT -> "assistant"
        }

    private fun LongArray.toStats() = GenerationStats(
        promptTokens = this[0].toInt(),
        generatedTokens = this[1].toInt(),
        prefillTokensPerSec = this[2] / 1000.0,
        decodeTokensPerSec = this[3] / 1000.0,
        firstTokenMs = this[4],
        totalMs = this[5],
        contextUsed = this[6].toInt(),
        contextTotal = this[7].toInt(),
        stopReason = StopReason.entries.getOrElse(this[8].toInt()) { StopReason.ERROR }
    )

    private companion object {
        const val TAG = "InferenceClient"
    }
}
