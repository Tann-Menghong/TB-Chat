package com.tannmenghong.tbchat.inference.service;

import com.tannmenghong.tbchat.inference.service.IInferenceCallback;

/**
 * The boundary between the UI process and the process that runs native code.
 *
 * Deliberately primitive-only: no Parcelable model metadata crosses here. The
 * inference process is a dumb GGUF runner that knows a file path and some
 * numbers, which keeps the marshalling trivial and means a catalog schema change
 * can never break the IPC contract.
 *
 * Note there is no network-capable object anywhere in this interface. The
 * inference process holds no HTTP client, so prompts physically cannot leave the
 * component that sees them.
 */
interface IInferenceService {

    boolean isEngineAvailable();

    /** The model id currently resident, or null. */
    String loadedModelId();

    /**
     * Loads a GGUF, evicting whatever is currently resident first. Only one
     * model is ever in memory.
     */
    void load(String modelId, String modelPath, int contextLength, int threads,
              int kvCacheBits, boolean flashAttention, int gpuLayers,
              IInferenceCallback callback);

    void unload();

    void generate(in String[] roles, in String[] contents,
                  float temperature, float topP, int topK, float minP,
                  float repeatPenalty, int maxTokens, long seed,
                  IInferenceCallback callback);

    void cancel();

    int tokenCount(String text);

    long residentBytes();
}
