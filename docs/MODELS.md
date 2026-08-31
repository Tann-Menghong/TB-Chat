# Installing models

## From the built-in catalog

Models → filter to **Runs on this phone** → Download. The size, RAM cost, licence and expected tokens per second are on the card before you tap anything.

The default filter hides models this device cannot run, and says how many are hidden. Switch to **Everything** to see them with their blocking reasons.

## From the Hugging Face Hub

Type a search term and tap **Hub**. Results are GGUF files with their real sizes and checksums, pinned to the commit SHA that was current when you searched, so a repository update between browsing and downloading cannot silently swap the weights.

Gated repositories are labelled. Accept the terms on huggingface.co and import the file manually — the app does not and will not work around access controls.

## Importing your own

Models → **Import**. GGUF only.

`.ckpt` and `.pt` are Python pickle files that execute arbitrary code when deserialized, and are refused. Convert first:

```bash
python llama.cpp/convert_hf_to_gguf.py <model-dir> --outfile model.gguf --outtype q8_0
llama.cpp/build/bin/llama-quantize model.gguf model-Q4_K_M.gguf Q4_K_M
```

The file is copied into app storage rather than referenced in place: a content URI is not a stable path, and llama.cpp needs a real file descriptor it can mmap.

## Choosing a quantization

| Quant | Bits/weight | Notes |
|---|---|---|
| Q8_0 | 8.5 | Near-lossless, twice the size of Q4 for little gain on a phone |
| Q6_K | 6.6 | Very good; worth it if the memory is there |
| **Q4_K_M** | **4.85** | **The default. Best quality per byte.** |
| Q4_0 | 4.55 | Older format, slightly worse than Q4_K_M at the same size |
| IQ4_XS | 4.25 | Smaller, slower to decode on weak CPUs |
| Q3_K_M | 3.9 | Noticeable quality loss |
| IQ2_XXS | 2.4 | Usually not worth running |

"4-bit" is nominal. Q4_K_M costs about 4.85 bits per weight once the block scales and mins are counted, and the size and memory figures shown in the app use the measured widths, not the nominal ones.

## Where the memory goes

```
resident  = weights + KV cache + compute buffer + ~160 MB overhead
KV cache  = 2 x layers x kvHeads x headDim x context x bytesPerElement
```

The KV cache is stored at Q8_0 by default, which halves it against F16 for no measurable quality cost. It scales linearly with context: for a 4B model at 16K context it is comparable to the weights themselves, which is why the context picker is capped at what the device can actually afford rather than at what the model architecture allows.
