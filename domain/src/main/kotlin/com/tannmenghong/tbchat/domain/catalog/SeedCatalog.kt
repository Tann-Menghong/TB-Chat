package com.tannmenghong.tbchat.domain.catalog

import com.tannmenghong.tbchat.inference.api.Accelerator
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.ArchSpec
import com.tannmenghong.tbchat.inference.api.FileRole
import com.tannmenghong.tbchat.inference.api.License
import com.tannmenghong.tbchat.inference.api.LicenseClass
import com.tannmenghong.tbchat.inference.api.Modality
import com.tannmenghong.tbchat.inference.api.ModelFile
import com.tannmenghong.tbchat.inference.api.ModelFormat
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.RuntimeId

/**
 * The catalog the app ships with, so it is useful on first launch with no
 * network round-trip. A remote catalog can add to this later without an app
 * update; it can never invalidate a model already on disk.
 *
 * GENERATED from live Hugging Face metadata -- every revision SHA, byte size
 * and LFS checksum below was read from the Hub rather than typed by hand, which
 * is what makes download verification meaningful. Regenerate with
 * `tools/catalog/gencatalog.py` when adding a model.
 *
 * Revisions are pinned to a commit SHA, never to `main`, so a repository update
 * cannot silently change what a queued download fetches.
 */
object SeedCatalog {

    private val LICENSE_APACHE_2_0 = License(
        id = "apache-2.0",
        name = "Apache 2.0",
        clazz = LicenseClass.PERMISSIVE,
        url = "https://www.apache.org/licenses/LICENSE-2.0",
        restrictions = emptyList()
    )

    private val LICENSE_MIT = License(
        id = "mit",
        name = "MIT",
        clazz = LicenseClass.PERMISSIVE,
        url = "https://opensource.org/license/mit",
        restrictions = emptyList()
    )

    private val LICENSE_GEMMA = License(
        id = "gemma",
        name = "Gemma Terms of Use",
        clazz = LicenseClass.USE_RESTRICTED,
        url = "https://ai.google.dev/gemma/terms",
        restrictions = listOf(
            "Google's prohibited use policy applies to anything you generate",
            "You must pass these same terms on if you redistribute the model",
            "Google may update the use restrictions over time"
        )
    )

    private val LICENSE_LLAMA3_2 = License(
        id = "llama3.2",
        name = "Llama 3.2 Community License",
        clazz = LicenseClass.USE_RESTRICTED,
        url = "https://github.com/meta-llama/llama-models/blob/main/models/llama3_2/LICENSE",
        restrictions = listOf(
            "Meta's acceptable use policy applies to anything you generate",
            "Products built on it must display \"Built with Llama\"",
            "A separate licence is required above 700 million monthly users"
        )
    )

    private val LICENSE_LFM_OPEN_1_0 = License(
        id = "lfm-open-1.0",
        name = "LFM Open License v1.0",
        clazz = LicenseClass.USE_RESTRICTED,
        url = "https://huggingface.co/LiquidAI/LFM2-350M",
        restrictions = listOf(
            "Free for research and personal use",
            "Commercial use is restricted above an annual revenue threshold",
            "Read the licence on the model page before shipping a product"
        )
    )

    val models: List<AiModel> = listOf(
        AiModel(
            id = "lfm2-350m-q4km",
            displayName = "LFM2 350M",
            publisher = "Liquid AI",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 354000000,
                layers = 16,
                kvHeads = 8,
                headDim = 64,
                maxContext = 32768,
                vocabSize = 65536
            ),
            files = listOf(
                ModelFile(
                    id = "lfm2-350m-q4km-weights",
                    repoId = "LiquidAI/LFM2-350M-GGUF",
                    revision = "8fdc9d526b7ed346b19257551b05816c7912ecc2",
                    path = "LFM2-350M-Q4_K_M.gguf",
                    sizeBytes = 229309376L,
                    sha256 = "a4d000c7064bd3b2e42c6845836286a899a4e79cf1791da1a6797b58d575957d",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_LFM_OPEN_1_0,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/LiquidAI/LFM2-350M-GGUF",
            isGated = false,
            description = "The fastest model here and the calibration probe. Good for rewriting, tidying text and quick questions.",
            contextLength = 8192
        ),
        AiModel(
            id = "qwen3-0_6b-q4km",
            displayName = "Qwen3 0.6B",
            publisher = "Alibaba",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 596000000,
                layers = 28,
                kvHeads = 8,
                headDim = 128,
                maxContext = 32768,
                vocabSize = 151936
            ),
            files = listOf(
                ModelFile(
                    id = "qwen3-0_6b-q4km-weights",
                    repoId = "unsloth/Qwen3-0.6B-GGUF",
                    revision = "50968a4468ef4233ed78cd7c3de230dd1d61a56b",
                    path = "Qwen3-0.6B-Q4_K_M.gguf",
                    sizeBytes = 396705472L,
                    sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_APACHE_2_0,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF",
            isGated = false,
            description = "Strong multilingual ability for its size. A sensible default on 4 GB phones.",
            contextLength = 8192
        ),
        AiModel(
            id = "qwen3-1_7b-q4km",
            displayName = "Qwen3 1.7B",
            publisher = "Alibaba",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 1720000000,
                layers = 28,
                kvHeads = 8,
                headDim = 128,
                maxContext = 32768,
                vocabSize = 151936
            ),
            files = listOf(
                ModelFile(
                    id = "qwen3-1_7b-q4km-weights",
                    repoId = "unsloth/Qwen3-1.7B-GGUF",
                    revision = "d7f544eead698dbd1f15126ef60b45a1e1933222",
                    path = "Qwen3-1.7B-Q4_K_M.gguf",
                    sizeBytes = 1107409472L,
                    sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_APACHE_2_0,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF",
            isGated = false,
            description = "Generates faster than most people read. The recommended default on 6-8 GB phones.",
            contextLength = 8192
        ),
        AiModel(
            id = "gemma3-1b-q4km",
            displayName = "Gemma 3 1B",
            publisher = "Google",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 1000000000,
                layers = 26,
                kvHeads = 1,
                headDim = 256,
                maxContext = 32768,
                vocabSize = 262144
            ),
            files = listOf(
                ModelFile(
                    id = "gemma3-1b-q4km-weights",
                    repoId = "unsloth/gemma-3-1b-it-GGUF",
                    revision = "f0b45be0aac41bd6a100a4b5734cad5f67255bfb",
                    path = "gemma-3-1b-it-Q4_K_M.gguf",
                    sizeBytes = 806058272L,
                    sha256 = "8270790f3ab69fdfe860b7b64008d9a19986d8df7e407bb018184caa08798ebd",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_GEMMA,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF",
            isGated = false,
            description = "Compact and well-behaved in conversation. Unusually small memory cost per token of context.",
            contextLength = 8192
        ),
        AiModel(
            id = "llama32-1b-q4km",
            displayName = "Llama 3.2 1B",
            publisher = "Meta",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 1236000000,
                layers = 16,
                kvHeads = 8,
                headDim = 64,
                maxContext = 32768,
                vocabSize = 128256
            ),
            files = listOf(
                ModelFile(
                    id = "llama32-1b-q4km-weights",
                    repoId = "unsloth/Llama-3.2-1B-Instruct-GGUF",
                    revision = "b69aef112e9f895e6f98d7ae0949f72ff09aa401",
                    path = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    sizeBytes = 807694368L,
                    sha256 = "3f5a22426976ab26cfe84dba63c1d08391717abb1af893e10f1b2968d862dcc1",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_LLAMA3_2,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF",
            isGated = false,
            description = "Meta's small instruct model. Broad general knowledge for the size.",
            contextLength = 8192
        ),
        AiModel(
            id = "smollm3-3b-q4km",
            displayName = "SmolLM3 3B",
            publisher = "Hugging Face",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 3080000000,
                layers = 36,
                kvHeads = 4,
                headDim = 128,
                maxContext = 32768,
                vocabSize = 128256
            ),
            files = listOf(
                ModelFile(
                    id = "smollm3-3b-q4km-weights",
                    repoId = "ggml-org/SmolLM3-3B-GGUF",
                    revision = "4965cb60b150737b68a0408c36aeefb65078f894",
                    path = "SmolLM3-Q4_K_M.gguf",
                    sizeBytes = 1915305312L,
                    sha256 = "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_APACHE_2_0,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF",
            isGated = false,
            description = "Fully open training data and good step-by-step reasoning.",
            contextLength = 4096
        ),
        AiModel(
            id = "phi4-mini-q4km",
            displayName = "Phi-4 mini",
            publisher = "Microsoft",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 3840000000,
                layers = 32,
                kvHeads = 8,
                headDim = 128,
                maxContext = 32768,
                vocabSize = 200064
            ),
            files = listOf(
                ModelFile(
                    id = "phi4-mini-q4km-weights",
                    repoId = "unsloth/Phi-4-mini-instruct-GGUF",
                    revision = "78eb92a46fc37e6b524df991ed9aca9bc6aa7b80",
                    path = "Phi-4-mini-instruct-Q4_K_M.gguf",
                    sizeBytes = 2491874272L,
                    sha256 = "88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_MIT,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF",
            isGated = false,
            description = "Best-in-class reasoning per byte. MIT licensed, so no usage restrictions at all.",
            contextLength = 4096
        ),
        AiModel(
            id = "qwen3-4b-q4km",
            displayName = "Qwen3 4B",
            publisher = "Alibaba",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = 4022000000,
                layers = 36,
                kvHeads = 8,
                headDim = 128,
                maxContext = 32768,
                vocabSize = 151936
            ),
            files = listOf(
                ModelFile(
                    id = "qwen3-4b-q4km-weights",
                    repoId = "Qwen/Qwen3-4B-GGUF",
                    revision = "bc640142c66e1fdd12af0bd68f40445458f3869b",
                    path = "Qwen3-4B-Q4_K_M.gguf",
                    sizeBytes = 2497280256L,
                    sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
                    role = FileRole.WEIGHTS
                )
            ),
            license = LICENSE_APACHE_2_0,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF",
            isGated = false,
            description = "The best quality that still feels responsive. Recommended on 12 GB phones.",
            contextLength = 4096
        )
    )

    /** Shown on Home before the device has been calibrated. */
    val recommendedIds: List<String> = listOf(
        "lfm2-350m-q4km",
        "qwen3-1_7b-q4km",
        "phi4-mini-q4km",
        "qwen3-4b-q4km"
    )

    /** The 230 MB model used for the first-launch calibration measurement. */
    const val PROBE_MODEL_ID: String = "lfm2-350m-q4km"

    fun byId(id: String): AiModel? = models.firstOrNull { it.id == id }
}
