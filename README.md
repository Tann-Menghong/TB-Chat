# TB-Chat

An Android app that runs open language models **entirely on your phone**. No account, no server, no cloud fallback. Prompts and conversations never leave the device.

Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) with GGUF weights, pinned to release `v0.3.0` (tag `b10621`).

---

## What it does

- **Browse and download models** from Hugging Face, with the size, RAM cost, licence and expected speed shown *before* you commit to the download.
- **Chat**, with streaming tokens, prompt-cache reuse across turns, and per-turn throughput figures.
- **Manage what is installed**: verify checksums, delete, import a GGUF you already have.
- **Resumable downloads** that survive the app being killed, verified against the publisher's SHA-256 before they count as installed.

## What it does not do

Image and video generation are **not in this release**, and the app says so on its home screen rather than hiding them behind a button that would fail.

The reason is memory, not effort. A 2 GB language model is mostly weights, and weights can be memory-mapped and paged. A diffusion model's cost is dominated by *peak activation memory* during the denoising loop — several gigabytes for a single 512×512 image at the resolutions people actually want — which cannot be paged and must be resident at once. Android will not give a single app that much on a typical phone. Video is worse by roughly the frame count.

The honest options were: ship nothing, or ship a stub that takes ten minutes per image on a flagship and out-of-memories on everything else. This app ships nothing, and explains why. Both are behind a hardware check on the roadmap.

## Requirements

- **Android 8.0 (API 26)** or newer
- **arm64-v8a** — 32-bit devices cannot address these models, and no 32-bit library is built
- **~3 GB RAM** for the smallest useful models; 6 GB+ for anything above 3B parameters
- Storage for the weights: 230 MB to 2.5 GB per model

The app measures your device on first launch (total and available RAM, performance-core count, `asimddp`/`i8mm` CPU features, Vulkan version, free storage) and refuses to offer a model it has calculated will not fit. That calculation is real arithmetic on the model's actual layer and head counts, not a size heuristic:

```
resident = weights + KV cache + compute buffer + fixed overhead
KV cache = 2 x layers x kvHeads x headDim x context x bytesPerElement
```

## Build

```bash
git clone --recursive https://github.com/Tann-Menghong/TB-Chat.git
cd TB-Chat
./gradlew :app:assembleDebug
```

The `--recursive` matters: `third_party/llama.cpp` is a submodule. If you cloned without it:

```bash
git submodule update --init --recursive
```

**Toolchain**: JDK 17, Android SDK 35, NDK 27.0.12077973, CMake 3.22.1. The NDK version is pinned so a toolchain upgrade is a deliberate, benchmarked change.

To skip the native build (much faster; the app then reports the engine as unavailable and refuses every model, which is the correct behaviour rather than a crash):

```bash
./gradlew :app:assembleDebug -Ptbchat.buildNativeEngine=false
```

## Architecture

Fourteen Gradle modules. The dependency rule is that `:domain` and `:inference:api` are pure Kotlin with no Android dependency at all, which is what makes the memory arithmetic unit-testable on the JVM.

```
:app                    navigation, Application, WorkManager config
:feature:home           device report, entry points
:feature:chat           conversation UI, streaming
:feature:models         catalog, compatibility verdicts, licences
:feature:downloads      queue, pause/resume
:feature:settings       performance, privacy, network log

:core:data              Room, DataStore, OkHttp, download worker, repositories
:core:device            RAM/CPU/GPU probing, thermal governor
:core:designsystem      theme, verdict chips, spec rows
:core:common            formatting, coroutine qualifiers

:inference:api          AiModel, InferenceEngine, DeviceProfile   (pure Kotlin)
:inference:llamacpp     JNI bridge, CMake, the native engine
:inference:service      the :inference process, AIDL, the client

:domain                 compatibility checker, seed catalog, repository interfaces  (pure Kotlin)
```

### The separate inference process

Inference runs in a **separate OS process** (`android:process=":inference"`), not in the UI process. llama.cpp is a large C++ parser handling untrusted downloaded files; a segfault there takes down a process, and the design choice is which one. Here it takes down a process with no UI state and no `INTERNET` permission, and the client observes the death through `onServiceDisconnected` and reports it as a real error rather than the whole app vanishing.

The IPC boundary is AIDL carrying **primitives only** — no Parcelables, no shared serialization — so the two sides can evolve independently.

### Adding a runtime

`InferenceEngine` and `ChatEngine` are the seam. A new runtime (ONNX Runtime Mobile, ExecuTorch, LiteRT-LM) is a new module implementing them plus one entry in the runtime registry; `RuntimeId` is already a field on every model and already part of the compatibility check, so a model whose runtime is not in this build is reported as unrunnable rather than offered and then failing.

## Privacy

- Prompts, conversations and generated text are never transmitted. The `:inference` process does not hold the `INTERNET` permission.
- The only network traffic is model metadata and model files, and **every request is logged and shown in Settings** so the claim is inspectable rather than promised.
- Offline mode blocks network access entirely.
- No account, no analytics, no crash reporting SDK.
- Nothing is included in Android backup or device transfer.

## Licensing

Every model shows its licence class and links to its original model page. Non-permissive licences (Gemma Terms, Llama Community, non-commercial research licences) require a one-time acknowledgement before the download starts, with the actual restrictions in plain language.

Gated models are labelled as gated and are **not** downloaded automatically — you accept the terms on Hugging Face and import the file. The app contains no mechanism for bypassing access controls, and will not gain one.

## Included models

Eight, with pinned commit SHAs, real byte sizes and real LFS checksums gathered from the Hub:

| Model | Size | Licence |
|---|---|---|
| LFM2 350M Q4_K_M | 229 MB | LFM Open v1.0 |
| Qwen3 0.6B Q4_K_M | 397 MB | Apache 2.0 |
| Gemma 3 1B Q4_K_M | 806 MB | Gemma Terms |
| Llama 3.2 1B Q4_K_M | 808 MB | Llama 3.2 Community |
| Qwen3 1.7B Q4_K_M | 1.1 GB | Apache 2.0 |
| SmolLM3 3B Q4_K_M | 1.9 GB | Apache 2.0 |
| Phi-4 mini Q4_K_M | 2.5 GB | MIT |
| Qwen3 4B Q4_K_M | 2.5 GB | Apache 2.0 |

You can also search the Hub for any other GGUF, or import one from your own storage. Imports are GGUF only — pickle-based formats (`.ckpt`, `.pt`) execute code when loaded and are refused.

## Licence

Apache 2.0. See `LICENSE`.

llama.cpp is MIT-licensed and vendored as a submodule. Model weights are **not** included and carry their own licences.
