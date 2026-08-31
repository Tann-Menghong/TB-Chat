package com.tannmenghong.tbchat.domain.model

import com.tannmenghong.tbchat.inference.api.Accelerator
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.SamplingParams

data class Conversation(
    val id: String,
    val title: String,
    val modelId: String?,
    val systemPrompt: String?,
    val sampling: SamplingParams,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val messageCount: Int = 0
)

data class Message(
    val id: String,
    val conversationId: String,
    val role: ChatMessage.Role,
    val content: String,
    val tokenCount: Int? = null,
    val decodeTokensPerSec: Double? = null,
    val firstTokenMs: Long? = null,
    val modelIdUsed: String? = null,
    val createdAt: Long,
    val isError: Boolean = false,
    /** True while tokens are still arriving, so the UI can show a caret and a Stop button. */
    val isStreaming: Boolean = false
) {
    fun toChatMessage() = ChatMessage(role, content)
}

/**
 * A model that is actually on this phone. Deliberately carries its own frozen
 * copy of the metadata: if the model later disappears from the catalog, the
 * copy on disk stays fully usable.
 */
data class InstalledModel(
    val model: AiModel,
    val localDir: String,
    val installedAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int,
    val bytesOnDisk: Long,
    val integrityVerified: Boolean,
    val importedByUser: Boolean
) {
    val weightsPath: String get() = "$localDir/${model.weightsFile?.path?.substringAfterLast('/') ?: ""}"
}

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, VERIFYING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val id: String,
    val modelId: String,
    val modelDisplayName: String,
    val fileId: String,
    val fileName: String,
    val url: String,
    val destPath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val bytesPerSecond: Long = 0,
    val attempt: Int = 0,
    val lastError: String? = null,
    val requiresUnmetered: Boolean = false,
    val updatedAt: Long = 0
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    val remainingBytes: Long get() = (totalBytes - downloadedBytes).coerceAtLeast(0)

    /** Null when there is no rate yet, rather than a fake estimate. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond <= 0 || status != DownloadStatus.RUNNING) null
        else remainingBytes / bytesPerSecond

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING ||
            status == DownloadStatus.VERIFYING
}

enum class PerformanceMode(val label: String, val description: String) {
    PERFORMANCE("Performance", "All performance cores. Fastest, warmest, heaviest on battery."),
    BALANCED("Balanced", "Backs off when the phone warms up. The default."),
    BATTERY_SAVER("Battery saver", "Half the threads. Noticeably slower, much cooler.")
}

data class AppSettings(
    val downloadRoot: String? = null,
    val wifiOnlyDownloads: Boolean = true,
    val defaultChatModelId: String? = null,
    val acceleratorOverride: Accelerator? = null,
    val threadOverride: Int? = null,
    val memoryCeilingPercent: Int = 50,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val idleUnloadSeconds: Int = 300,
    val contextLength: Int = 4096,
    val offlineMode: Boolean = false,
    val diagnosticsEnabled: Boolean = false,
    val acknowledgedLicenseIds: Set<String> = emptySet(),
    val hasCompletedOnboarding: Boolean = false,
    val calibrationJson: String? = null
)

/** One row in the Settings network log, so the privacy claim is inspectable. */
data class NetworkEvent(
    val id: Long = 0,
    val host: String,
    val purpose: String,
    val bytes: Long,
    val timestamp: Long
)
