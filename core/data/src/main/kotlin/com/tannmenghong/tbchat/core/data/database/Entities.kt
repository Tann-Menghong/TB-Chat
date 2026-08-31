package com.tannmenghong.tbchat.core.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import com.tannmenghong.tbchat.inference.api.LicenseClass

/**
 * The catalog: everything the app knows about, installed or not. Refreshable
 * from the network.
 *
 * The full AiModel lives in `json`, with only the fields that get sorted or
 * filtered promoted to real columns. The catalog is tens of rows, not millions,
 * so paying for a wide schema and a pile of type converters would buy nothing.
 */
@Entity(tableName = "model_catalog")
data class ModelCatalogEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val publisher: String,
    val paramCount: Long,
    val totalSizeBytes: Long,
    val licenseClass: LicenseClass,
    val isRemote: Boolean,
    val json: String,
    val updatedAt: Long
)

/**
 * What is actually on this phone.
 *
 * Deliberately separate from the catalog and never touched by a catalog
 * refresh. `snapshotJson` freezes the metadata as it was at install time, so a
 * model that later disappears upstream stays completely usable here.
 */
@Entity(tableName = "installed_model")
data class InstalledModelEntity(
    @PrimaryKey val modelId: String,
    val localDir: String,
    val installedAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int,
    val bytesOnDisk: Long,
    val integrityVerified: Boolean,
    val importedByUser: Boolean,
    val snapshotJson: String
)

@Entity(
    tableName = "download_job",
    indices = [Index("modelId"), Index("status")]
)
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val modelDisplayName: String,
    val fileId: String,
    val fileName: String,
    val url: String,
    val destPath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    /** Revalidated with If-Range before resuming, so a stale partial restarts instead of corrupting. */
    val etag: String?,
    val expectedSha256: String?,
    val status: DownloadStatus,
    val bytesPerSecond: Long,
    val attempt: Int,
    val lastError: String?,
    val requiresUnmetered: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "conversation", indices = [Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String?,
    val systemPrompt: String?,
    val samplingJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean
)

@Entity(
    tableName = "message",
    indices = [Index("conversationId", "createdAt")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val tokenCount: Int?,
    val decodeTokensPerSec: Double?,
    val firstTokenMs: Long?,
    /** A thread can span models; record which one produced each turn. */
    val modelIdUsed: String?,
    val createdAt: Long,
    val isError: Boolean
)

/**
 * The calibration measurement. Keyed by OS fingerprint so a system update
 * invalidates it rather than silently keeping stale numbers.
 */
@Entity(tableName = "benchmark_result")
data class BenchmarkEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val paramCount: Long,
    val accelerator: String,
    val threads: Int,
    val prefillTokensPerSec: Double,
    val decodeTokensPerSec: Double,
    val peakRssBytes: Long,
    val thermalStatusAtEnd: Int,
    val osFingerprint: String,
    val measuredAt: Long
)

/** Every outbound request, so the privacy claim is inspectable rather than promised. */
@Entity(tableName = "network_event", indices = [Index("timestamp")])
data class NetworkEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val purpose: String,
    val bytes: Long,
    val timestamp: Long
)
