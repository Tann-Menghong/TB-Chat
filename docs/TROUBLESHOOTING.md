# Troubleshooting

## "This build has no inference engine"

The APK was built without the native library. Either the `third_party/llama.cpp` submodule was missing at build time, or `-Ptbchat.buildNativeEngine=false` was passed.

```bash
git submodule update --init --recursive
./gradlew :app:assembleDebug
```

The app deliberately reports every model as unrunnable in this state rather than offering downloads that cannot work.

## Native link errors mentioning `std::__ndk1::basic_string`

The C++ standard library is not on the link line. `src/main/cpp/CMakeLists.txt` lists `c++_shared` explicitly in `target_link_libraries` for exactly this reason — adding llama.cpp via `add_subdirectory()` does not cause `ANDROID_STL` to inject it. If you have edited that file, put it back.

## A model I can afford shows "Will not run"

Expand the card. The reasons are always listed. The usual causes:

- **Memory ceiling.** The default is 50% of total RAM. Settings → Performance → Memory ceiling raises it, at the cost of Android being more likely to kill your other apps.
- **Context length.** The KV cache scales linearly with context, and at 16K it can exceed the weights themselves. Drop to 4096.
- **Storage.** A 10% margin above the download size is required, because the `.part` file and the final file coexist briefly during the rename.

## A download is stuck at 0%

Check Settings → Downloads → Wi-Fi only. Downloads wait for an unmetered connection by default. WorkManager also holds jobs when storage is low.

The bar is deliberately indeterminate until the first byte arrives, so a genuine wait does not look like a stalled 0%.

## A download failed verification

The bytes did not match the SHA-256 the publisher lists, or the file is not a valid GGUF. The partial file is deleted rather than retried, because re-fetching byte-identical corrupt content achieves nothing. Try the download again; if it fails twice, the upstream file has probably changed.

## Generation is very slow

Check the CPU line on the Home screen. A device without `asimddp` (int8 dot product) runs quantized models several times slower, and the app warns about this before the download rather than after.

Otherwise: fewer parameters, or a smaller quantization. Q4_K_M is the sweet spot; below Q3 the quality loss is usually more annoying than the speed gain is worth.

## The app says the phone is too hot

The thermal governor reads `PowerManager.addThermalStatusListener` and backs off. At `THROTTLING_SEVERE` it drops threads; at `CRITICAL` it refuses to start a new generation. This is the SoC reporting its own state, not a guess — let the phone cool for a minute.

## Chat stops mid-answer with "the engine stopped unexpectedly"

The `:inference` process died, usually the kernel OOM killer. The partial answer is kept. Lower the context length or use a smaller model; if it reproduces on a specific model, that file may be damaged — use Verify on the Models screen.

## Importing a file is refused

Only GGUF is accepted. `.ckpt` and `.pt` are Python pickles that execute arbitrary code when loaded, and there is no safe way to import them. Convert to GGUF with the llama.cpp `convert_hf_to_gguf.py` script first.
