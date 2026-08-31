# Development

## Toolchain

| Tool | Version | Why pinned |
|---|---|---|
| JDK | 17 | AGP 8.7 requirement |
| Android SDK | 35 | `compileSdk` / `targetSdk` |
| NDK | 27.0.12077973 | 16 KB page support; a silent bump changes generated code |
| CMake | 3.22.1 | Ships with the SDK |
| Gradle | 8.11.1 | Wrapper |
| Kotlin | 2.0.21 | K2 with the Compose plugin |

## Layout

`:domain` and `:inference:api` are **pure Kotlin JVM modules with no Android dependency**. That is a hard rule, not a preference: it is what lets the compatibility arithmetic run as a plain JUnit test instead of on a device.

Everything else may depend on Android. Feature modules depend on `:core:*` and `:domain`, never on each other.

## The native build

`inference/llamacpp/src/main/cpp/CMakeLists.txt` adds llama.cpp via `add_subdirectory` with every backend but CPU forced off:

- **Vulkan** needs a host-side `vulkan-shaders-gen` binary compiled during the Android build, which complicates CI for a gain that has not been measured here.
- **OpenCL** is Adreno-only in practice.

Per the design rule, a GPU backend must beat the CPU on a benchmark before the app adopts it, so shipping CPU-only costs nothing today. Re-enabling either is a one-line change plus a benchmark.

The link line lists `c++_shared` explicitly. `ANDROID_STL` does not inject it when llama.cpp comes in through `add_subdirectory`, and the failure mode is a wall of undefined `std::` symbols.

## Regenerating the seed catalog

`domain/.../catalog/SeedCatalog.kt` is generated, not hand-written. It carries real commit SHAs, byte sizes and LFS checksums pulled from the Hugging Face API:

```bash
python tools/catalog/fetch_metadata.py    # queries the Hub, writes catalog-metadata.json
python tools/catalog/gencatalog.py        # renders SeedCatalog.kt
```

Do not hand-edit the generated file. A wrong SHA means downloads fail verification; a wrong layer count means the memory estimate lies, which is the one thing this app must not do.

## Testing

```bash
./gradlew test                      # JVM unit tests
./gradlew :domain:test              # the memory arithmetic
```

`ModelCompatibilityCheckerTest` asserts exact byte figures against real model configs — `kvCacheBytes(36, 8, 128, 4096, F16) == 603_979_776`, which is the actual geometry of Qwen3-4B. If you change the formula, that test should fail; if it does not, the test is wrong.

## Conventions

- Errors reaching the UI are `InferenceError` subclasses with a `userMessage` written for a person, not a log line.
- Nothing tappable offers an action the app has already calculated will fail. The verdict comes first.
- A "no data yet" state and a "zero" state are always distinguished — a progress bar sitting at exactly 0% is a bug report waiting to happen.
