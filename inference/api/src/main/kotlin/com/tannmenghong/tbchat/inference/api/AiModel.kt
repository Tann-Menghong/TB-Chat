package com.tannmenghong.tbchat.inference.api

import kotlinx.serialization.Serializable

/** What a model can be asked to do. A single model may support several. */
enum class Modality { CHAT, VISION_CHAT, TEXT_TO_IMAGE, IMAGE_TO_IMAGE, TEXT_TO_VIDEO, IMAGE_TO_VIDEO, EMBEDDING, INTERPOLATION }

enum class ModelFormat(val extension: String) {
    GGUF("gguf"),
    SAFETENSORS("safetensors"),
    ONNX("onnx"),
    LITERTLM("litertlm"),
    EXECUTORCH("pte");

    companion object {
        fun fromExtension(ext: String): ModelFormat? =
            entries.firstOrNull { it.extension.equals(ext.removePrefix("."), ignoreCase = true) }
    }
}

/**
 * Effective bits per weight, including the block scales and mins that k-quants
 * store alongside the packed weights. These are the numbers the size and memory
 * estimates are built on, so they are the measured widths rather than the
 * nominal ones -- Q4_K_M is "4-bit" but actually costs about 4.85 bits.
 */
enum class Quantization(val bitsPerWeight: Float, val label: String) {
    F32(32f, "F32"),
    F16(16f, "F16"),
    Q8_0(8.5f, "Q8_0"),
    Q6_K(6.6f, "Q6_K"),
    Q5_K_M(5.7f, "Q5_K_M"),
    Q4_K_M(4.85f, "Q4_K_M"),
    Q4_0(4.55f, "Q4_0"),
    IQ4_XS(4.25f, "IQ4_XS"),
    Q3_K_M(3.9f, "Q3_K_M"),
    IQ2_XXS(2.4f, "IQ2_XXS"),
    NONE(16f, "None");

    companion object {
        /** Recover the quantisation from a GGUF filename such as `Qwen3-4B-Q4_K_M.gguf`. */
        fun fromFileName(name: String): Quantization {
            val upper = name.uppercase()
            // Longest label first so Q4_K_M is not matched as Q4_0 or similar.
            return entries.sortedByDescending { it.label.length }
                .firstOrNull { it != NONE && upper.contains(it.label) } ?: NONE
        }
    }
}

enum class Accelerator { CPU, GPU_VULKAN, GPU_OPENCL, NPU_QNN, NPU_NEUROPILOT }

enum class RuntimeId { LLAMA_CPP, STABLE_DIFFUSION_CPP, ONNX_RUNTIME, NCNN, LITERT_LM, EXECUTORCH }

enum class FileRole { WEIGHTS, TOKENIZER, PROJECTOR, VAE, TEXT_ENCODER, LORA }

/**
 * How permissive a licence is, which decides how much friction the app puts in
 * front of a download. This is a product decision as much as a legal one.
 */
enum class LicenseClass {
    /** Apache-2.0, MIT. Shown, not gated. */
    PERMISSIVE,

    /** Gemma Terms, Llama Community, OpenRAIL. One-time acknowledgement required. */
    USE_RESTRICTED,

    /** Stability research licences. Persistent badge, recorded on every output. */
    NON_COMMERCIAL
}

@Serializable
data class License(
    val id: String,
    val name: String,
    val clazz: LicenseClass,
    val url: String,
    /** Plain-language restrictions shown on the acknowledgement screen. */
    val restrictions: List<String> = emptyList()
)

/**
 * The architecture facts needed to compute memory cost without downloading the
 * weights. Populated from the catalog, or parsed out of a GGUF header for a
 * model the user imported themselves.
 */
@Serializable
data class ArchSpec(
    val paramCount: Long,
    val layers: Int,
    val kvHeads: Int,
    val headDim: Int,
    val maxContext: Int,
    val vocabSize: Int
)

@Serializable
data class ModelFile(
    val id: String,
    val repoId: String,
    /** A pinned commit SHA or tag -- never `main`, so a queued download cannot change under us. */
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val role: FileRole = FileRole.WEIGHTS
) {
    val downloadUrl: String get() = "https://huggingface.co/$repoId/resolve/$revision/$path"
}

@Serializable
data class AiModel(
    val id: String,
    val displayName: String,
    val publisher: String,
    val modalities: Set<Modality>,
    val format: ModelFormat,
    val quantization: Quantization,
    val arch: ArchSpec?,
    val files: List<ModelFile>,
    val license: License,
    val requiredRuntime: RuntimeId,
    val supportedAccelerators: Set<Accelerator> = setOf(Accelerator.CPU),
    val minAndroidApi: Int = 26,
    val sourceUrl: String,
    val isGated: Boolean = false,
    val description: String = "",
    val contextLength: Int = 4096
) {
    val downloadBytes: Long get() = files.sumOf { it.sizeBytes }
    val weightsFile: ModelFile? get() = files.firstOrNull { it.role == FileRole.WEIGHTS }
    val parameterCount: Long get() = arch?.paramCount ?: 0L
}

@Serializable
data class LoadOptions(
    val accelerator: Accelerator = Accelerator.CPU,
    /** Performance cores only. Never `availableProcessors()` -- little cores stall the barrier. */
    val threads: Int = 4,
    val contextLength: Int = 4096,
    val kvCacheType: Quantization = Quantization.Q8_0,
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val flashAttention: Boolean = true
)

@Serializable
data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val seed: Long = -1L,
    val stopSequences: List<String> = emptyList()
)
