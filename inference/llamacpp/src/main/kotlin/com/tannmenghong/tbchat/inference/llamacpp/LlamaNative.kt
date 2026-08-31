package com.tannmenghong.tbchat.inference.llamacpp

import android.util.Log

/**
 * One-to-one mapping onto llama_jni.cpp. Nothing in the app calls this directly;
 * LlamaCppChatEngine owns it and is the only thing allowed to hold a handle.
 *
 * A handle is a native pointer. Passing a stale one is a segfault, so handles
 * never escape the engine and every entry point tolerates 0.
 */
internal object LlamaNative {

    /** False when the module was built without the native engine. */
    val isAvailable: Boolean = loadLibrary()

    private fun loadLibrary(): Boolean = try {
        if (BuildConfig.NATIVE_ENGINE_BUILT) {
            System.loadLibrary("tbchat_llama")
            nativeInit()
            true
        } else {
            Log.w(TAG, "Built without the native engine; chat will report as unavailable.")
            false
        }
    } catch (t: Throwable) {
        // An UnsatisfiedLinkError here means a broken build or an unsupported
        // ABI. Neither should take the whole app down: the engine simply
        // reports itself unavailable and the UI explains why.
        Log.e(TAG, "Failed to load libtbchat_llama.so", t)
        false
    }

    interface Callback {
        fun onToken(piece: String)
        fun onPrefill(done: Int, total: Int)
    }

    interface LoadProgress {
        fun onLoadProgress(progress: Float)
    }

    private external fun nativeInit()

    external fun nativeLoad(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        kvTypeCode: Int,
        flashAttn: Boolean,
        nGpuLayers: Int,
        progress: LoadProgress?
    ): Long

    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeResetCache(handle: Long)
    external fun nativeContextSize(handle: Long): Int
    external fun nativeDescribe(handle: Long): String
    external fun nativeTokenCount(handle: Long, text: String): Int

    /** [params, layers, kvHeads, embd, trainedCtx, vocab] */
    external fun nativeModelInfo(handle: Long): LongArray

    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String

    /**
     * Blocks until generation finishes or is cancelled. Returns:
     * [promptTokens, generatedTokens, prefillMs, decodeMs, firstTokenMs, totalMs,
     *  contextUsed, contextTotal, stopCode]
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Long,
        callback: Callback
    ): LongArray

    private const val TAG = "LlamaNative"
}
