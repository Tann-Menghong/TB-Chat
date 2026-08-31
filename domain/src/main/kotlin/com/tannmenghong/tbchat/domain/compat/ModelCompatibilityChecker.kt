package com.tannmenghong.tbchat.domain.compat

import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.Compatibility
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.Reason
import com.tannmenghong.tbchat.inference.api.RuntimeId
import kotlin.math.roundToLong

/**
 * The single source of truth for "will this run on this phone?".
 *
 * Deliberately pure and Android-free so the arithmetic can be unit tested
 * against known model headers. Everything here is conservative on purpose: an
 * optimistic prediction that ends in the low-memory killer taking the process
 * is a far worse experience than a pessimistic one that blocks a model the
 * device could just barely have handled.
 */
object ModelCompatibilityChecker {

    /** Below this the answer is technically streaming but slower than reading. */
    private const val UNUSABLY_SLOW_TOKENS_PER_SEC = 3.0

    /** Above this fraction of the budget, warn even though it fits. */
    private const val TIGHT_MEMORY_FRACTION = 0.8

    private const val MB = 1024L * 1024L

    /**
     * KV cache = 2 (K and V) x layers x kvHeads x headDim x context x bytesPerElement.
     *
     * Worked example, Qwen3-4B at 4096 context with an f16 cache:
     *   2 x 36 x 8 x 128 x 4096 x 2 bytes = 604 MB.
     * The same cache at Q8_0 is 321 MB, which is why Q8_0 is the default: it
     * halves the context cost for a quality difference nobody can detect in chat.
     */
    fun kvCacheBytes(
        layers: Int,
        kvHeads: Int,
        headDim: Int,
        contextLength: Int,
        type: Quantization
    ): Long {
        val bytesPerElement = type.bitsPerWeight / 8.0
        return (2.0 * layers * kvHeads * headDim * contextLength * bytesPerElement).roundToLong()
    }

    /**
     * Predicted peak resident bytes for a loaded model.
     *
     * weights + kv cache + compute buffer + fixed overhead. When the
     * architecture is unknown -- an imported GGUF whose header we could not
     * fully parse -- fall back to a flat multiplier on the file size, which is
     * cruder but never wildly optimistic.
     */
    fun estimateResidentBytes(
        model: AiModel,
        contextLength: Int,
        kvCacheType: Quantization = Quantization.Q8_0
    ): Long {
        val weights = model.downloadBytes
        val arch = model.arch ?: return (weights * 1.35).roundToLong()

        val ctx = contextLength.coerceAtMost(arch.maxContext)
        val kv = kvCacheBytes(arch.layers, arch.kvHeads, arch.headDim, ctx, kvCacheType)

        // The logits buffer alone is vocab x 4 bytes; the rest is per-layer
        // scratch that scales with hidden size, approximated per layer here.
        val computeBuffer = (arch.vocabSize * 4L) + (arch.layers * 8L * MB)

        // Tokenizer tables, JNI frames, and allocator slack. Measured at ~120 MB
        // on a Tier A device; rounded up.
        val fixedOverhead = 160L * MB

        return weights + kv + computeBuffer + fixedOverhead
    }

    /**
     * Extrapolate decode throughput for a model that has never run on this
     * device, from the calibration probe that has.
     *
     * Decode is memory-bandwidth bound, so throughput scales roughly with the
     * inverse of bytes-per-token-read, which is the quantised weight size. That
     * makes the ratio of probe size to target size a better predictor than any
     * FLOPS estimate.
     */
    fun extrapolateDecodeRate(model: AiModel, profile: DeviceProfile): Double? {
        val cal = profile.calibration ?: return null
        val targetParams = model.arch?.paramCount ?: return null
        if (targetParams <= 0 || cal.probeParamCount <= 0) return null

        val probeBytes = cal.probeParamCount * (Quantization.Q4_K_M.bitsPerWeight / 8.0)
        val targetBytes = targetParams * (model.quantization.bitsPerWeight / 8.0)
        if (targetBytes <= 0) return null

        // Sub-linear falloff: larger models get slightly better bandwidth
        // utilisation per byte than the tiny probe does, so a pure inverse ratio
        // under-predicts. 0.92 is the correction fitted from device-lab runs.
        val ratio = probeBytes / targetBytes
        return cal.decodeTokensPerSec * Math.pow(ratio, 0.92)
    }

    fun check(
        model: AiModel,
        profile: DeviceProfile,
        contextLength: Int,
        availableRuntimes: Set<RuntimeId>,
        freeStorageBytes: Long = profile.storage.freeBytes
    ): Compatibility {
        val hardBlockers = mutableListOf<Reason>()
        val warnings = mutableListOf<Reason>()

        if (model.minAndroidApi > profile.androidApi) {
            hardBlockers += Reason.AndroidTooOld(model.minAndroidApi)
        }
        if (!profile.abi.startsWith("arm64")) {
            hardBlockers += Reason.UnsupportedAbi(profile.abi)
        }
        if (model.requiredRuntime !in availableRuntimes) {
            hardBlockers += Reason.NoRuntime(model.requiredRuntime)
        }

        val needed = estimateResidentBytes(model, contextLength)
        if (needed > profile.memory.usableBytes) {
            hardBlockers += Reason.InsufficientMemory(needed, profile.memory.usableBytes)
        } else if (needed > profile.memory.usableBytes * TIGHT_MEMORY_FRACTION) {
            warnings += Reason.TightMemory("Memory will be tight. Close other apps before running this.")
        }

        // A 10% margin covers the .part file living alongside the final file
        // during the atomic rename.
        if (freeStorageBytes < model.downloadBytes * 1.1) {
            hardBlockers += Reason.InsufficientStorage(model.downloadBytes, freeStorageBytes)
        }

        if (hardBlockers.isNotEmpty()) return Compatibility.Unsupported(hardBlockers)

        if (!profile.cpu.hasDotProduct && model.parameterCount > 2_000_000_000L) {
            warnings += Reason.SlowCpu("This CPU has no int8 dot-product instructions, so large models run several times slower.")
        }

        val tps = extrapolateDecodeRate(model, profile)
        if (tps != null && tps < UNUSABLY_SLOW_TOKENS_PER_SEC) {
            warnings += Reason.SlowGeneration(tps)
        }

        return if (warnings.isEmpty()) Compatibility.Supported(tps)
        else Compatibility.Marginal(warnings, tps)
    }

    /**
     * The largest context this device can afford for this model, rounded down to
     * a power of two. Used to cap the picker rather than letting a user select a
     * 128K context that cannot possibly be allocated.
     */
    fun maxAffordableContext(model: AiModel, profile: DeviceProfile): Int {
        val arch = model.arch ?: return 2048
        var ctx = arch.maxContext
        while (ctx > 1024) {
            if (estimateResidentBytes(model, ctx) <= profile.memory.usableBytes * TIGHT_MEMORY_FRACTION) return ctx
            ctx /= 2
        }
        return 1024
    }
}
