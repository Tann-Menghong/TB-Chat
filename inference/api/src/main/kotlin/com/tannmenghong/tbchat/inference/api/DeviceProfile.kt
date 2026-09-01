package com.tannmenghong.tbchat.inference.api

/**
 * Tiers are a starting hypothesis derived from static facts, then corrected by
 * the calibration probe. They are never the final word on what a device can do:
 * a 12 GB phone with a weak memory controller and no i8mm loses to an 8 GB
 * flagship, and only a measurement can tell you that.
 */
enum class DeviceTier(val displayName: String, val summary: String) {
    MINIMAL("Minimal", "Small chat models only"),
    ENTRY("Entry", "Chat up to 2B"),
    MAINSTREAM("Mainstream", "Chat up to 4B"),
    FLAGSHIP("Flagship", "Everything except the NPU fast lane"),
    ACCELERATED("Accelerated", "Full feature set including NPU");

    val isAtLeastMainstream: Boolean get() = ordinal >= MAINSTREAM.ordinal
}

data class MemoryBudget(
    val totalBytes: Long,
    val availableBytes: Long,
    /** The ceiling a model is actually allowed to occupy. The lower of two independent limits. */
    val usableBytes: Long,
    val lowMemory: Boolean
)

data class CpuInfo(
    val totalCores: Int,
    val performanceCores: Int,
    val hasDotProduct: Boolean,
    val hasI8mm: Boolean,
    val hasFp16: Boolean,
    val maxFrequencyKHz: Int,
    val socModel: String?
)

data class GpuInfo(
    val hasVulkan: Boolean,
    val vulkanVersion: String?,
    val hasOpenCl: Boolean,
    val rendererName: String?
)

data class StorageInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    val modelDirBytes: Long
)

data class DeviceProfile(
    val tier: DeviceTier,
    val androidApi: Int,
    val manufacturer: String,
    val deviceModel: String,
    val abi: String,
    val cpu: CpuInfo,
    val gpu: GpuInfo,
    val memory: MemoryBudget,
    val storage: StorageInfo,
    val availableAccelerators: Set<Accelerator>,
    /** Filled in by the calibration probe. Null until it has run. */
    val calibration: Calibration? = null
) {
    val isCalibrated: Boolean get() = calibration != null
}

/**
 * Measured on this phone with the 350M probe model, then used to extrapolate a
 * throughput prediction for models that have never been run here. Extrapolation
 * from a real measurement beats any spec-sheet formula.
 */
data class Calibration(
    val probeModelId: String,
    val probeParamCount: Long,
    val prefillTokensPerSec: Double,
    val decodeTokensPerSec: Double,
    val accelerator: Accelerator,
    val threads: Int,
    val osFingerprint: String,
    val measuredAt: Long
)

/** The reasons a model is blocked or flagged, each one shown verbatim in the UI. */
sealed class Reason(val message: String) {
    data class InsufficientMemory(val needed: Long, val available: Long) :
        Reason("Needs more RAM than is available")

    data class InsufficientStorage(val needed: Long, val free: Long) :
        Reason("Not enough free storage")

    data class AndroidTooOld(val required: Int) :
        Reason("Requires Android API $required or newer")

    data class TightMemory(val detail: String) : Reason(detail)
    data class SlowCpu(val detail: String) : Reason(detail)
    data class SlowGeneration(val tokensPerSec: Double) :
        Reason("Expect roughly ${"%.1f".format(tokensPerSec)} tokens per second, which is slower than reading speed")

    data class NoRuntime(val runtime: RuntimeId) :
        Reason("No installed engine can run this model format")

    data class UnsupportedAbi(val abi: String) : Reason("This build only supports arm64 devices")
}

sealed class Compatibility {
    abstract val estimatedTokensPerSec: Double?

    data class Supported(override val estimatedTokensPerSec: Double?) : Compatibility()

    /** Runs, but the user is told why it will be unpleasant before they commit. */
    data class Marginal(
        val reasons: List<Reason>,
        override val estimatedTokensPerSec: Double?
    ) : Compatibility()

    /** Blocked. The model is still shown, greyed, with the reasons -- never hidden. */
    data class Unsupported(val reasons: List<Reason>) : Compatibility() {
        override val estimatedTokensPerSec: Double? = null
    }

    val canRun: Boolean get() = this !is Unsupported

    /**
     * Whether it is reasonable to *fetch* this model, which is a different
     * question from whether it can run this second.
     *
     * Free memory is a snapshot: it changes the moment the user closes an app,
     * and a download that takes twenty minutes says nothing about what will be
     * free when the model is finally loaded. Gating the download on it makes
     * every model undownloadable on a busy phone, which is indistinguishable
     * from the download feature being broken.
     *
     * Only the permanent facts block a download: wrong ABI, no runtime in this
     * build, an Android version too old, or genuinely no room on disk. A
     * memory-tight model downloads, and is reported honestly as unlikely to run
     * until memory is freed.
     */
    val canDownload: Boolean
        get() = when (this) {
            is Unsupported -> reasons.none {
                it is Reason.UnsupportedAbi ||
                    it is Reason.NoRuntime ||
                    it is Reason.AndroidTooOld ||
                    it is Reason.InsufficientStorage
            }

            else -> true
        }
}
