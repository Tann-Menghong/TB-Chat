package com.tannmenghong.tbchat.domain.compat

import com.google.common.truth.Truth.assertThat
import com.tannmenghong.tbchat.domain.catalog.SeedCatalog
import com.tannmenghong.tbchat.inference.api.Accelerator
import com.tannmenghong.tbchat.inference.api.Calibration
import com.tannmenghong.tbchat.inference.api.Compatibility
import com.tannmenghong.tbchat.inference.api.CpuInfo
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.api.DeviceTier
import com.tannmenghong.tbchat.inference.api.GpuInfo
import com.tannmenghong.tbchat.inference.api.MemoryBudget
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.Reason
import com.tannmenghong.tbchat.inference.api.RuntimeId
import com.tannmenghong.tbchat.inference.api.StorageInfo
import org.junit.Test

private const val GB = 1024L * 1024L * 1024L
private const val MB = 1024L * 1024L

class ModelCompatibilityCheckerTest {

    private fun profile(
        totalRam: Long,
        usable: Long,
        freeStorage: Long = 40 * GB,
        dotProduct: Boolean = true,
        calibration: Calibration? = null
    ) = DeviceProfile(
        tier = DeviceTier.FLAGSHIP,
        androidApi = 34,
        manufacturer = "test",
        deviceModel = "test",
        abi = "arm64-v8a",
        cpu = CpuInfo(8, 4, dotProduct, true, true, 3_300_000, "test-soc"),
        gpu = GpuInfo(true, "1.3", false, "test-gpu"),
        memory = MemoryBudget(totalRam, usable + 512 * MB, usable, false),
        storage = StorageInfo(freeStorage, 128 * GB, 0),
        availableAccelerators = setOf(Accelerator.CPU),
        calibration = calibration
    )

    private val runtimes = setOf(RuntimeId.LLAMA_CPP)

    @Test
    fun `kv cache matches the hand-computed Qwen3-4B figure`() {
        // 2 x 36 layers x 8 kv heads x 128 head dim x 4096 ctx x 2 bytes = 604 MB
        val f16 = ModelCompatibilityChecker.kvCacheBytes(36, 8, 128, 4096, Quantization.F16)
        assertThat(f16).isEqualTo(603_979_776L)

        // Q8_0 is 8.5 bits, so slightly over half of the f16 figure.
        val q8 = ModelCompatibilityChecker.kvCacheBytes(36, 8, 128, 4096, Quantization.Q8_0)
        assertThat(q8).isEqualTo(320_864_256L)
        assertThat(q8.toDouble() / f16).isWithin(0.02).of(0.53)
    }

    @Test
    fun `kv cache scales linearly with context`() {
        val at4k = ModelCompatibilityChecker.kvCacheBytes(36, 8, 128, 4096, Quantization.F16)
        val at8k = ModelCompatibilityChecker.kvCacheBytes(36, 8, 128, 8192, Quantization.F16)
        assertThat(at8k).isEqualTo(at4k * 2)
    }

    @Test
    fun `estimate always exceeds the raw weight size`() {
        val model = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }
        val estimate = ModelCompatibilityChecker.estimateResidentBytes(model, 4096)
        assertThat(estimate).isGreaterThan(model.downloadBytes)
        // Sanity: a 2.5 GB model at 4K context should land under 4 GB resident.
        assertThat(estimate).isLessThan(4L * GB)
    }

    @Test
    fun `4B model is blocked on a 4GB phone and allowed on a 12GB phone`() {
        val model = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }

        val lowEnd = ModelCompatibilityChecker.check(model, profile(4 * GB, 2 * GB), 4096, runtimes)
        assertThat(lowEnd.canRun).isFalse()
        assertThat((lowEnd as Compatibility.Unsupported).reasons.any { it is Reason.InsufficientMemory })
            .isTrue()

        val flagship = ModelCompatibilityChecker.check(model, profile(12 * GB, 6 * GB), 4096, runtimes)
        assertThat(flagship.canRun).isTrue()
    }

    @Test
    fun `insufficient storage blocks the download`() {
        val model = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }
        val result = ModelCompatibilityChecker.check(
            model, profile(12 * GB, 6 * GB, freeStorage = 100 * MB), 4096, runtimes
        )
        assertThat(result.canRun).isFalse()
        assertThat((result as Compatibility.Unsupported).reasons.any { it is Reason.InsufficientStorage }).isTrue()
    }

    @Test
    fun `a missing runtime is a hard block, not a warning`() {
        val model = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }
        val result = ModelCompatibilityChecker.check(model, profile(12 * GB, 6 * GB), 4096, emptySet())
        assertThat(result.canRun).isFalse()
        assertThat((result as Compatibility.Unsupported).reasons.any { it is Reason.NoRuntime }).isTrue()
    }

    @Test
    fun `tight memory warns but still runs`() {
        val model = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }
        val needed = ModelCompatibilityChecker.estimateResidentBytes(model, 4096)
        // Budget just above the requirement: fits, but inside the tight band.
        val result = ModelCompatibilityChecker.check(
            model, profile(8 * GB, (needed * 1.05).toLong()), 4096, runtimes
        )
        assertThat(result.canRun).isTrue()
        assertThat(result).isInstanceOf(Compatibility.Marginal::class.java)
        assertThat((result as Compatibility.Marginal).reasons.any { it is Reason.TightMemory }).isTrue()
    }

    @Test
    fun `extrapolation predicts a slower rate for a larger model`() {
        val cal = Calibration(
            probeModelId = "lfm2-350m-q4km",
            probeParamCount = 354_000_000L,
            prefillTokensPerSec = 620.0,
            decodeTokensPerSec = 62.0,
            accelerator = Accelerator.CPU,
            threads = 4,
            osFingerprint = "test",
            measuredAt = 0L
        )
        val p = profile(12 * GB, 6 * GB, calibration = cal)
        val small = SeedCatalog.models.first { it.id == "qwen3-1_7b-q4km" }
        val large = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }

        val smallRate = ModelCompatibilityChecker.extrapolateDecodeRate(small, p)!!
        val largeRate = ModelCompatibilityChecker.extrapolateDecodeRate(large, p)!!

        assertThat(smallRate).isGreaterThan(largeRate)
        // A 4B Q4 model on a device that does 62 t/s at 350M should land in a
        // plausible single-digit-to-low-teens band, not somewhere absurd.
        assertThat(largeRate).isIn(com.google.common.collect.Range.closed(3.0, 25.0))
    }

    @Test
    fun `max affordable context shrinks on a smaller device`() {
        val model = SeedCatalog.models.first { it.id == "qwen3-4b-q4km" }
        val big = ModelCompatibilityChecker.maxAffordableContext(model, profile(16 * GB, 8 * GB))
        val small = ModelCompatibilityChecker.maxAffordableContext(model, profile(8 * GB, 3500L * MB))
        assertThat(big).isAtLeast(small)
    }

    @Test
    fun `every seed model has a weights file and a source url`() {
        assertThat(SeedCatalog.models).isNotEmpty()
        SeedCatalog.models.forEach { model ->
            assertThat(model.weightsFile).isNotNull()
            assertThat(model.sourceUrl).startsWith("https://")
            assertThat(model.downloadBytes).isGreaterThan(0L)
        }
    }

    @Test
    fun `quantisation is recovered from a gguf filename`() {
        assertThat(Quantization.fromFileName("Qwen3-4B-Q4_K_M.gguf")).isEqualTo(Quantization.Q4_K_M)
        assertThat(Quantization.fromFileName("model-Q8_0.gguf")).isEqualTo(Quantization.Q8_0)
        assertThat(Quantization.fromFileName("mystery.gguf")).isEqualTo(Quantization.NONE)
    }
}
