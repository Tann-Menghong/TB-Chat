import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The native engine is the slowest part of a clean build. When the submodule is
 * absent (a source zip rather than a git clone) or the developer has opted out
 * with `tbchat.buildNativeEngine=false`, we skip CMake entirely: the module
 * still compiles, and the engine reports itself unavailable at runtime rather
 * than crashing on a missing library.
 */
val llamaCppDir = rootProject.layout.projectDirectory.dir("third_party/llama.cpp").asFile
val submodulePresent = File(llamaCppDir, "CMakeLists.txt").exists()
val nativeRequested = (project.findProperty("tbchat.buildNativeEngine") as String?)?.toBoolean() ?: true
val buildNative = submodulePresent && nativeRequested

if (!buildNative) {
    logger.lifecycle(
        "[:inference:llamacpp] Native engine DISABLED " +
            "(submodule present=$submodulePresent, requested=$nativeRequested). " +
            "Run: git submodule update --init --recursive"
    )
}

android {
    namespace = "com.tannmenghong.tbchat.inference.llamacpp"
    compileSdk = 35

    // Pinned so a toolchain upgrade is a deliberate, benchmarked change rather
    // than something that happens silently on a different machine.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26

        // 32-bit cannot address these models and doubles the build matrix for a
        // vanishing share of devices.
        ndk { abiFilters += "arm64-v8a" }

        if (buildNative) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DGGML_NATIVE=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DGGML_LLAMAFILE=OFF",
                        "-DLLAMA_BUILD_COMMON=OFF",
                        "-DLLAMA_BUILD_TESTS=OFF",
                        "-DLLAMA_BUILD_EXAMPLES=OFF",
                        "-DLLAMA_BUILD_SERVER=OFF",
                        "-DLLAMA_BUILD_TOOLS=OFF",
                        "-DLLAMA_CURL=OFF",
                        "-DBUILD_SHARED_LIBS=OFF"
                    )
                    cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                }
            }
        }
    }

    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildFeatures { buildConfig = true }

    defaultConfig {
        buildConfigField("boolean", "NATIVE_ENGINE_BUILT", buildNative.toString())
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":inference:api"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
