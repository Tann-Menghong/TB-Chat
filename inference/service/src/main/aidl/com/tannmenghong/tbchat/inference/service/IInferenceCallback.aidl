package com.tannmenghong.tbchat.inference.service;

/**
 * Streaming results back to the UI process.
 *
 * `oneway` throughout: the inference process must never block waiting on the UI
 * process, or a slow frame in the UI would stall token generation.
 */
oneway interface IInferenceCallback {
    void onLoadProgress(float progress);
    void onLoaded(int contextLength, long residentBytes);
    void onPrefill(int done, int total);
    void onToken(String piece);

    /**
     * [promptTokens, generatedTokens, prefillMs, decodeMs, firstTokenMs,
     *  totalMs, contextUsed, contextTotal, stopCode]
     */
    void onDone(in long[] stats);

    void onError(int code, String message);
}
