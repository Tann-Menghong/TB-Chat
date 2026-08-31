"""Emit SeedCatalog.kt from the real Hugging Face metadata in catalog2.json,
so pinned revisions, byte sizes and LFS checksums are never hand-transcribed."""
import json, sys

meta = {r["id"]: r for r in json.load(open(sys.argv[1], encoding="utf-8"))}

LIC = {
    "apache-2.0": ("apache-2.0", "Apache 2.0", "PERMISSIVE",
                   "https://www.apache.org/licenses/LICENSE-2.0", []),
    "mit": ("mit", "MIT", "PERMISSIVE",
            "https://opensource.org/license/mit", []),
    "gemma": ("gemma", "Gemma Terms of Use", "USE_RESTRICTED",
              "https://ai.google.dev/gemma/terms",
              ["Google's prohibited use policy applies to anything you generate",
               "You must pass these same terms on if you redistribute the model",
               "Google may update the use restrictions over time"]),
    "llama3.2": ("llama3.2", "Llama 3.2 Community License", "USE_RESTRICTED",
                 "https://github.com/meta-llama/llama-models/blob/main/models/llama3_2/LICENSE",
                 ["Meta's acceptable use policy applies to anything you generate",
                  "Products built on it must display \\\"Built with Llama\\\"",
                  "A separate licence is required above 700 million monthly users"]),
    "other": ("lfm-open-1.0", "LFM Open License v1.0", "USE_RESTRICTED",
              "https://huggingface.co/LiquidAI/LFM2-350M",
              ["Free for research and personal use",
               "Commercial use is restricted above an annual revenue threshold",
               "Read the licence on the model page before shipping a product"]),
}

# Editorial metadata: names, sizes and the one-line pitch shown on each card.
INFO = {
    "lfm2-350m-q4km": ("LFM2 350M", "Liquid AI", 354_000_000,
        "The fastest model here and the calibration probe. Good for rewriting, tidying text and quick questions.", True),
    "qwen3-0_6b-q4km": ("Qwen3 0.6B", "Alibaba", 596_000_000,
        "Strong multilingual ability for its size. A sensible default on 4 GB phones.", False),
    "qwen3-1_7b-q4km": ("Qwen3 1.7B", "Alibaba", 1_720_000_000,
        "Generates faster than most people read. The recommended default on 6-8 GB phones.", True),
    "qwen3-4b-q4km": ("Qwen3 4B", "Alibaba", 4_022_000_000,
        "The best quality that still feels responsive. Recommended on 12 GB phones.", True),
    "gemma3-1b-q4km": ("Gemma 3 1B", "Google", 1_000_000_000,
        "Compact and well-behaved in conversation. Unusually small memory cost per token of context.", False),
    "llama32-1b-q4km": ("Llama 3.2 1B", "Meta", 1_236_000_000,
        "Meta's small instruct model. Broad general knowledge for the size.", False),
    "smollm3-3b-q4km": ("SmolLM3 3B", "Hugging Face", 3_080_000_000,
        "Fully open training data and good step-by-step reasoning.", False),
    "phi4-mini-q4km": ("Phi-4 mini", "Microsoft", 3_840_000_000,
        "Best-in-class reasoning per byte. MIT licensed, so no usage restrictions at all.", True),
}

ORDER = ["lfm2-350m-q4km", "qwen3-0_6b-q4km", "qwen3-1_7b-q4km", "gemma3-1b-q4km",
         "llama32-1b-q4km", "smollm3-3b-q4km", "phi4-mini-q4km", "qwen3-4b-q4km"]

lic_ids, lic_decls = {}, []
for key, (lid, name, clazz, url, restr) in LIC.items():
    var = "LICENSE_" + lid.upper().replace("-", "_").replace(".", "_")
    lic_ids[key] = var
    r = "emptyList()" if not restr else ("listOf(\n" + ",\n".join(
        '            "%s"' % x for x in restr) + "\n        )")
    lic_decls.append(
        '    private val %s = License(\n'
        '        id = "%s",\n        name = "%s",\n        clazz = LicenseClass.%s,\n'
        '        url = "%s",\n        restrictions = %s\n    )' % (var, lid, name, clazz, url, r))

entries = []
for mid in ORDER:
    m, (disp, pub, params, desc, rec) = meta[mid], INFO[mid]
    f, a = m["file"], m["arch"]
    # Cap the advertised context at 32K: anything larger is memory the phone
    # does not have, and offering it would be a lie the checker has to retract.
    max_ctx = min(a["maxCtx"], 32768)
    default_ctx = 4096 if params > 2_000_000_000 else 8192
    entries.append("""        AiModel(
            id = "%s",
            displayName = "%s",
            publisher = "%s",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.Q4_K_M,
            arch = ArchSpec(
                paramCount = %d,
                layers = %d,
                kvHeads = %d,
                headDim = %d,
                maxContext = %d,
                vocabSize = %d
            ),
            files = listOf(
                ModelFile(
                    id = "%s-weights",
                    repoId = "%s",
                    revision = "%s",
                    path = "%s",
                    sizeBytes = %dL,
                    sha256 = "%s",
                    role = FileRole.WEIGHTS
                )
            ),
            license = %s,
            requiredRuntime = RuntimeId.LLAMA_CPP,
            supportedAccelerators = setOf(Accelerator.CPU),
            sourceUrl = "https://huggingface.co/%s",
            isGated = %s,
            description = "%s",
            contextLength = %d
        )""" % (mid, disp, pub, params, a["layers"], a["kvHeads"], a["headDim"],
                max_ctx, a["vocab"], mid, m["repo"], m["sha"], f["path"],
                f["size"], f["sha256"], lic_ids[m["license"]], m["repo"],
                "true" if m["gated"] else "false", desc, default_ctx))

recommended = [m for m in ORDER if INFO[m][4]]

src = '''package com.tannmenghong.tbchat.domain.catalog

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

%s

    val models: List<AiModel> = listOf(
%s
    )

    /** Shown on Home before the device has been calibrated. */
    val recommendedIds: List<String> = listOf(
%s
    )

    /** The 230 MB model used for the first-launch calibration measurement. */
    const val PROBE_MODEL_ID: String = "lfm2-350m-q4km"

    fun byId(id: String): AiModel? = models.firstOrNull { it.id == id }
}
''' % ("\n\n".join(lic_decls), ",\n".join(entries),
       ",\n".join('        "%s"' % m for m in recommended))

open(sys.argv[2], "w", encoding="utf-8", newline="\n").write(src)
print("wrote %s (%d models, %d licences)" % (sys.argv[2], len(entries), len(lic_decls)))
