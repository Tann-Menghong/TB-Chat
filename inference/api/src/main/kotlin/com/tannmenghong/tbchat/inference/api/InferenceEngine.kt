package com.tannmenghong.tbchat.inference.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val progress: Float) : EngineState
    data class Ready(
        val model: AiModel,
        val accelerator: Accelerator,
        val residentBytes: Long,
        val contextLength: Int
    ) : EngineState

    data class Failed(val error: InferenceError) : EngineState
}

/**
 * Every failure the user can actually be shown. The message is written for the
 * person holding the phone, not for a log: it says what happened and what to do
 * about it, because a local model failing is a normal event here, not a bug.
 */
sealed class InferenceError(val userMessage: String) {
    data class InsufficientMemory(val neededBytes: Long, val availableBytes: Long) :
        InferenceError("This model needs more memory than the phone has free right now. Close some apps, or pick a smaller model.")

    data class UnsupportedArchitecture(val arch: String) :
        InferenceError("This runtime does not recognise the model architecture ($arch).")

    data class CorruptModel(val detail: String) :
        InferenceError("The model file is incomplete or damaged. Delete it and download again.")

    data class AcceleratorUnavailable(val requested: Accelerator) :
        InferenceError("Falling back to the CPU: the ${requested.name} backend is not usable on this device.")

    data object ThermalStop :
        InferenceError("Paused because the phone is too hot to continue safely. It will resume once it cools down.")

    data class NativeCrash(val signal: String) :
        InferenceError("The inference engine stopped unexpectedly. Try a smaller model or a shorter context.")

    data object EngineUnavailable :
        InferenceError("The inference engine was not included in this build.")

    data class ModelFileMissing(val path: String) :
        InferenceError("The model file is no longer on the phone. Download it again.")

    data class Unknown(val detail: String) : InferenceError("Something went wrong: $detail")

    fun asException(): InferenceException = InferenceException(this)
}

class InferenceException(val error: InferenceError) : Exception(error.userMessage)

/**
 * The contract every runtime implements. Adding a new runtime -- ExecuTorch,
 * LiteRT-LM, stable-diffusion.cpp -- means one new module implementing this and
 * one entry in the registry. Nothing above this interface changes.
 */
interface InferenceEngine {
    val runtimeId: RuntimeId
    val state: StateFlow<EngineState>

    /** Whether this engine can run this model at all, before any memory check. */
    fun supports(model: AiModel): Boolean

    suspend fun load(model: AiModel, modelPath: String, options: LoadOptions): Result<Unit>

    suspend fun unload()

    /** Predicted peak resident bytes. Must be conservative: over-predicting blocks a model, under-predicting kills the process. */
    fun estimateResidentBytes(model: AiModel, options: LoadOptions): Long
}

sealed interface ChatEvent {
    data class PromptProcessing(val done: Int, val total: Int) : ChatEvent
    data class Token(val text: String) : ChatEvent
    data class Done(val stats: GenerationStats) : ChatEvent
    data class Error(val error: InferenceError) : ChatEvent
}

enum class StopReason { END_OF_TURN, MAX_TOKENS, STOP_SEQUENCE, CANCELLED, CONTEXT_FULL, ERROR }

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillTokensPerSec: Double,
    val decodeTokensPerSec: Double,
    val firstTokenMs: Long,
    val totalMs: Long,
    val contextUsed: Int,
    val contextTotal: Int,
    val stopReason: StopReason
)

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

interface ChatEngine : InferenceEngine {
    /**
     * Cold flow. Cancelling collection sets the native abort flag, which is how
     * the Stop button works with no separate cancellation plumbing.
     */
    fun generate(messages: List<ChatMessage>, params: SamplingParams): Flow<ChatEvent>

    suspend fun tokenCount(text: String): Int

    suspend fun resetPromptCache()
}
