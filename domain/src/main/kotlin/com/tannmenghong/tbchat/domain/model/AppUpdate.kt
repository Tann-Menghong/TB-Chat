package com.tannmenghong.tbchat.domain.model

/** The app's own version, read from the installed package. */
data class AppVersion(val name: String, val code: Long)

/**
 * A published release of TB-Chat discovered from its distribution channel
 * (GitHub Releases for the sideloaded build). Carries only what the updater
 * needs: which version, where the APK is, how big, and -- when the channel
 * provides one -- a digest to verify it against before install.
 */
data class AppRelease(
    val versionName: String,   // "1.0.2"
    val tag: String,           // "v1.0.2"
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    /** e.g. "sha256:abcd..."; null when the channel does not publish one. */
    val sha256: String?
)

/** The result of asking the distribution channel whether a newer build exists. */
sealed interface UpdateCheck {
    data class Available(val current: AppVersion, val release: AppRelease) : UpdateCheck
    data class UpToDate(val current: AppVersion) : UpdateCheck

    /** Offline mode is on, so no network request was made. */
    data object OfflineBlocked : UpdateCheck
}

/** Progress of fetching and preparing an update APK for installation. */
sealed interface UpdateProgress {
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long
    ) : UpdateProgress {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f
            else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data object Verifying : UpdateProgress

    /** Downloaded and verified; ready to hand to Android's package installer. */
    data class ReadyToInstall(val apkPath: String) : UpdateProgress

    data class Failed(val message: String) : UpdateProgress
}
