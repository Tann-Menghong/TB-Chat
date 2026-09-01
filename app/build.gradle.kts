plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tannmenghong.tbchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tannmenghong.tbchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.7"

        // The native engine is arm64 only, so shipping other ABIs would produce
        // an APK that installs and then cannot run anything.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing is driven entirely by environment variables so no
        // keystore or password is ever committed. When they are absent -- a local
        // build, or CI without the secrets configured -- the release APK is left
        // unsigned rather than failing the build.
        create("release") {
            val storePath = System.getenv("TBCHAT_KEYSTORE")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("TBCHAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TBCHAT_KEY_ALIAS")
                keyPassword = System.getenv("TBCHAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // CI runners are ephemeral, so each build would otherwise generate a
            // fresh random debug keystore. Every release would then be signed
            // with a different key, and Android refuses to update an installed
            // app whose signature changed ("package conflicts with an existing
            // package"). A committed debug keystore keeps the signature stable
            // so debug builds update in place. Safe to commit: the credentials
            // are the well-known Android debug defaults and grant nothing.
            val debugStore = rootProject.file("debug.keystore")
            if (debugStore.exists()) {
                signingConfig = signingConfigs.getByName("debug").apply {
                    storeFile = debugStore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed only when the keystore env vars are present; otherwise the
            // APK is emitted unsigned (app-release-unsigned.apk) exactly as before.
            val storePath = System.getenv("TBCHAT_KEYSTORE")
            signingConfig = if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Loaded straight from the APK, which avoids a second copy of a
            // multi-megabyte library on disk.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:device"))
    implementation(project(":core:designsystem"))
    implementation(project(":inference:api"))
    implementation(project(":inference:service"))
    implementation(project(":inference:llamacpp"))

    implementation(project(":feature:home"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:models"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
}
