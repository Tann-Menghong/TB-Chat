package com.tannmenghong.tbchat.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelCatalogDao {

    @Query("SELECT * FROM model_catalog ORDER BY totalSizeBytes ASC")
    fun observeAll(): Flow<List<ModelCatalogEntity>>

    @Query("SELECT * FROM model_catalog WHERE id = :id")
    suspend fun get(id: String): ModelCatalogEntity?

    @Upsert
    suspend fun upsertAll(entities: List<ModelCatalogEntity>)

    @Query("DELETE FROM model_catalog WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM model_catalog WHERE isRemote = 1")
    suspend fun clearRemote()
}

@Dao
interface InstalledModelDao {

    @Query("SELECT * FROM installed_model ORDER BY lastUsedAt DESC, installedAt DESC")
    fun observeAll(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_model WHERE modelId = :id")
    suspend fun get(id: String): InstalledModelEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM installed_model WHERE modelId = :id)")
    suspend fun exists(id: String): Boolean

    @Upsert
    suspend fun upsert(entity: InstalledModelEntity)

    @Query("DELETE FROM installed_model WHERE modelId = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE installed_model SET lastUsedAt = :now, useCount = useCount + 1 WHERE modelId = :id"
    )
    suspend fun markUsed(id: String, now: Long)

    @Query("UPDATE installed_model SET integrityVerified = :verified WHERE modelId = :id")
    suspend fun setVerified(id: String, verified: Boolean)

    @Query("SELECT COALESCE(SUM(bytesOnDisk), 0) FROM installed_model")
    fun observeTotalBytes(): Flow<Long>
}

@Dao
interface DownloadJobDao {

    @Query("SELECT * FROM download_job ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query(
        "SELECT * FROM download_job WHERE status IN ('QUEUED','RUNNING','PAUSED','VERIFYING','FAILED') " +
            "ORDER BY createdAt ASC"
    )
    fun observeActive(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_job WHERE modelId = :modelId ORDER BY createdAt ASC")
    fun observeForModel(modelId: String): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_job WHERE id = :id")
    suspend fun get(id: String): DownloadJobEntity?

    @Query("SELECT * FROM download_job WHERE modelId = :modelId")
    suspend fun forModel(modelId: String): List<DownloadJobEntity>

    /** Jobs held before any bytes moved, e.g. by an unmet Wi-Fi constraint. */
    @Query("SELECT * FROM download_job WHERE status IN ('QUEUED','PAUSED')")
    suspend fun forAllWaiting(): List<DownloadJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadJobEntity)

    @Update
    suspend fun update(entity: DownloadJobEntity)

    @Query(
        "UPDATE download_job SET downloadedBytes = :bytes, totalBytes = :total, " +
            "bytesPerSecond = :rate, status = :status, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        bytes: Long,
        total: Long,
        rate: Long,
        status: DownloadStatus,
        now: Long
    )

    @Query("UPDATE download_job SET status = :status, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: DownloadStatus, error: String?, now: Long)

    @Query("UPDATE download_job SET etag = :etag WHERE id = :id")
    suspend fun setEtag(id: String, etag: String?)

    @Query("DELETE FROM download_job WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM download_job WHERE modelId = :modelId")
    suspend fun deleteForModel(modelId: String)

    /**
     * A process death mid-download leaves rows claiming to be RUNNING. Nothing
     * is running after a cold start, so they are corrected to PAUSED and the
     * user gets a resume button instead of a permanently frozen bar.
     */
    @Query("UPDATE download_job SET status = 'PAUSED' WHERE status IN ('RUNNING','VERIFYING')")
    suspend fun reconcileOnStartup()
}

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversation ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :id")
    suspend fun messageCount(id: String): Int
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun list(conversationId: String): List<MessageEntity>

    @Upsert
    suspend fun upsert(entity: MessageEntity)

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteAllIn(conversationId: String)

    /** Regenerate: drop the assistant turn and everything after it. */
    @Query(
        "DELETE FROM message WHERE conversationId = :conversationId AND createdAt >= " +
            "(SELECT createdAt FROM message WHERE id = :fromId)"
    )
    suspend fun truncateFrom(conversationId: String, fromId: String)
}

@Dao
interface BenchmarkDao {

    @Query("SELECT * FROM benchmark_result WHERE osFingerprint = :fingerprint ORDER BY measuredAt DESC LIMIT 1")
    suspend fun latestFor(fingerprint: String): BenchmarkEntity?

    @Upsert
    suspend fun upsert(entity: BenchmarkEntity)

    @Query("DELETE FROM benchmark_result")
    suspend fun clear()
}

@Dao
interface NetworkEventDao {

    @Query("SELECT * FROM network_event ORDER BY timestamp DESC LIMIT 200")
    fun observeRecent(): Flow<List<NetworkEventEntity>>

    @Insert
    suspend fun insert(entity: NetworkEventEntity)

    @Query("DELETE FROM network_event")
    suspend fun clear()

    /** Keeps the log from growing without bound; it is a diagnostic, not an archive. */
    @Query("DELETE FROM network_event WHERE timestamp < :cutoff")
    suspend fun trim(cutoff: Long)
}
