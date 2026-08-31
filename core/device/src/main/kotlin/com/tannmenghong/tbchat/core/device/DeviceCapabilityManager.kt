package com.tannmenghong.tbchat.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.tannmenghong.tbchat.inference.api.Accelerator
import com.tannmenghong.tbchat.inference.api.Calibration
import com.tannmenghong.tbchat.inference.api.CpuInfo
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.api.DeviceTier
import com.tannmenghong.tbchat.inference.api.GpuInfo
import com.tannmenghong.tbchat.inference.api.MemoryBudget
import com.tannmenghong.tbchat.inference.api.StorageInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app knows about the hardware it is running on.
 *
 * Reads are cheap but not free (a few /proc and /sys files), so the static
 * facts are cached and only the memory and storage figures are re-read on every
 * call -- those are the ones that actually change minute to minute.
 */
@Singleton
class DeviceCapabilityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    private val cachedCpu: CpuInfo by lazy { readCpuInfo() }
    private val cachedGpu: GpuInfo by lazy { readGpuInfo() }

    @Volatile
    private var memoryCeilingPercent: Int = DEFAULT_MEMORY_CEILING_PERCENT

    @Volatile
    private var calibration: Calibration? = null

    fun setMemoryCeilingPercent(percent: Int) {
        memoryCeilingPercent = percent.coerceIn(25, 75)
    }

    fun setCalibration(value: Calibration?) {
        calibration = value
    }

    fun profile(): DeviceProfile {
        val memory = currentMemoryBudget()
        val storage = storageInfo()
        val cpu = cachedCpu
        val gpu = cachedGpu
        return DeviceProfile(
            tier = classify(memory, cpu, gpu),
            androidApi = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpu = cpu,
            gpu = gpu,
            memory = memory,
            storage = storage,
            availableAccelerators = availableAccelerators(gpu),
            calibration = calibration
        )
    }

    /**
     * Two independent ceilings, take the lower.
     *
     * `totalMem * ceiling` stops a model claiming so much of the phone that the
     * rest of the system starts thrashing. `availMem - headroom` stops it
     * claiming memory that is not actually free right now. The headroom is
     * twice the system low-memory threshold, because being *at* the threshold
     * is already the point where processes start dying.
     */
    fun currentMemoryBudget(): MemoryBudget {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)

        val byTotal = (info.totalMem * (memoryCeilingPercent / 100.0)).toLong()
        val headroom = maxOf(MIN_HEADROOM_BYTES, info.threshold * 2)
        val byAvailable = info.availMem - headroom

        return MemoryBudget(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            usableBytes = maxOf(0L, minOf(byTotal, byAvailable)),
            lowMemory = info.lowMemory
        )
    }

    fun storageInfo(): StorageInfo {
        val dir = modelsDirectory()
        return StorageInfo(
            freeBytes = dir.usableSpace,
            totalBytes = dir.totalSpace,
            modelDirBytes = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        )
    }

    fun modelsDirectory(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    /**
     * Thread count for inference.
     *
     * Never `availableProcessors()`: on a big.LITTLE phone the little cores
     * finish their share of a GEMM far later than the big ones, and every
     * thread waits at the barrier for the slowest. Four fast cores beat eight
     * mixed ones, consistently and by a wide margin.
     */
    fun performanceCoreCount(): Int = cachedCpu.performanceCores

    private fun readCpuInfo(): CpuInfo {
        val total = Runtime.getRuntime().availableProcessors()

        val frequencies = (0 until total).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toInt()
            }.getOrNull()
        }

        val maxFreq = frequencies.maxOrNull() ?: 0
        // Cores within 15% of the fastest are treated as one performance
        // cluster. This correctly groups the 1+3 and 2+6 layouts that current
        // flagships use without hard-coding either.
        val perfCores = if (frequencies.isEmpty()) {
            (total / 2).coerceIn(2, 8)
        } else {
            frequencies.count { it >= maxFreq * 0.85 }.coerceIn(1, 8)
        }

        val features = runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')?.trim()?.split(' ')?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())

        return CpuInfo(
            totalCores = total,
            performanceCores = perfCores,
            // asimddp is int8 dot product; its absence roughly halves throughput.
            hasDotProduct = "asimddp" in features,
            // i8mm is the int8 matrix extension, worth another large factor on prefill.
            hasI8mm = "i8mm" in features,
            hasFp16 = "asimdhp" in features || "fphp" in features,
            maxFrequencyKHz = maxFreq,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        )
    }

    private fun readGpuInfo(): GpuInfo {
        val pm = context.packageManager
        val vulkanFeature = pm.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }

        val vulkanVersion = vulkanFeature?.version?.let { v ->
            // Packed as (major << 22) | (minor << 12) | patch.
            "${(v shr 22) and 0x7F}.${(v shr 12) and 0x3FF}"
        }

        // No public API for OpenCL. The vendor driver being present on disk is
        // the only signal available before attempting a dlopen, which we do not
        // do here because loading it has a real cost.
        val openClPresent = OPENCL_PATHS.any { File(it).exists() }

        return GpuInfo(
            hasVulkan = vulkanFeature != null,
            vulkanVersion = vulkanVersion,
            hasOpenCl = openClPresent,
            rendererName = Build.HARDWARE
        )
    }

    private fun availableAccelerators(gpu: GpuInfo): Set<Accelerator> = buildSet {
        add(Accelerator.CPU)
        // Listed as available, not as adopted. Nothing uses a GPU backend until
        // the benchmark shows it actually beating the CPU on this device --
        // Mali Vulkan in particular has shipped driver versions where it loses.
        if (gpu.hasVulkan) add(Accelerator.GPU_VULKAN)
        if (gpu.hasOpenCl) add(Accelerator.GPU_OPENCL)
    }

    /**
     * The starting hypothesis, later corrected by the calibration probe. RAM
     * sets the ceiling, but a missing dot-product unit drops a device a full
     * tier regardless of how much memory it has.
     */
    private fun classify(memory: MemoryBudget, cpu: CpuInfo, gpu: GpuInfo): DeviceTier {
        val gb = memory.totalBytes / (1024.0 * 1024.0 * 1024.0)

        val base = when {
            gb >= 15.0 -> DeviceTier.ACCELERATED
            gb >= 11.0 -> DeviceTier.FLAGSHIP
            gb >= 7.0 -> DeviceTier.MAINSTREAM
            gb >= 5.0 -> DeviceTier.ENTRY
            else -> DeviceTier.MINIMAL
        }

        // The NPU fast lane is Snapdragon-only, so a 16 GB MediaTek device is
        // Flagship rather than Accelerated no matter how much memory it has.
        val demoted = if (base == DeviceTier.ACCELERATED && !hasQnnStack()) DeviceTier.FLAGSHIP else base

        return when {
            !cpu.hasDotProduct -> minOf(demoted, DeviceTier.ENTRY)
            !cpu.hasI8mm && demoted > DeviceTier.MAINSTREAM -> DeviceTier.MAINSTREAM
            !gpu.hasVulkan && demoted > DeviceTier.ENTRY -> minOf(demoted, DeviceTier.MAINSTREAM)
            else -> demoted
        }
    }

    private fun hasQnnStack(): Boolean =
        QNN_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun minOf(a: DeviceTier, b: DeviceTier): DeviceTier =
        if (a.ordinal <= b.ordinal) a else b

    companion object {
        private const val TAG = "DeviceCapability"
        private const val DEFAULT_MEMORY_CEILING_PERCENT = 50
        private const val MIN_HEADROOM_BYTES = 512L * 1024 * 1024

        private val OPENCL_PATHS = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so"
        )

        private val QNN_PATHS = listOf(
            "/vendor/lib64/libQnnHtp.so",
            "/vendor/lib64/libQnnHtpV73Stub.so",
            "/vendor/lib64/libQnnHtpV75Stub.so"
        )
    }
}
