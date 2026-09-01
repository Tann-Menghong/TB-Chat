package com.tannmenghong.tbchat.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tannmenghong.tbchat.core.data.database.DownloadJobDao
import com.tannmenghong.tbchat.core.data.database.DownloadJobEntity
import com.tannmenghong.tbchat.core.data.download.ModelDownloadWorker
import com.tannmenghong.tbchat.core.data.settings.SettingsDataSource
import com.tannmenghong.tbchat.core.device.DeviceCapabilityManager
import com.tannmenghong.tbchat.domain.model.DownloadJob
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import com.tannmenghong.tbchat.domain.repository.DownloadRepository
import com.tannmenghong.tbchat.inference.api.AiModel
import java.io.File
import java.util.concurrent.TimeUnit
import com.tannmenghong.tbchat.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobDao: DownloadJobDao,
    private val settings: SettingsDataSource,
    private val device: DeviceCapabilityManager,
    private val models: ModelRepositoryImpl,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DownloadRepository {

    private val workManager get() = WorkManager.getInstance(context)

    override fun activeJobs(): Flow<List<DownloadJob>> =
        jobDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun jobsForModel(modelId: String): Flow<List<DownloadJob>> =
        jobDao.observeForModel(modelId).map { rows -> rows.map { it.toDomain() } }

    /**
     * Corrects rows left claiming to be RUNNING by a process death. Called once
     * from Application.onCreate: nothing is running after a cold start, so a
     * frozen progress bar with no worker behind it would be a lie.
     */
    suspend fun reconcileOnStartup() = withContext(ioDispatcher) {
        jobDao.reconcileOnStartup()
    }

    /**
     * Queues every file of a model.
     *
     * Storage is checked here rather than in the worker so the user finds out
     * immediately, not twenty minutes into a download. The headroom above the
     * model size is deliberate: filling the last byte of a phone's storage
     * breaks things far beyond this app.
     */
    override suspend fun enqueue(model: AiModel): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val storage = device.storageInfo()
            val needed = model.downloadBytes + STORAGE_HEADROOM_BYTES
            if (storage.freeBytes < needed) {
                throw InsufficientStorageException(
                    required = model.downloadBytes,
                    available = storage.freeBytes
                )
            }

            val existing = jobDao.forModel(model.id)
            if (existing.any { it.status == DownloadStatus.DONE }) {
                return@runCatching
            }

            val prefs = settings.current()
            val dir = File(device.modelsDirectory(), model.id.replace(':', '_'))
            dir.mkdirs()

            val now = System.currentTimeMillis()
            model.files.forEachIndexed { index, file ->
                val fileName = file.path.substringAfterLast('/')
                val jobId = "${model.id}#$index"

                // An existing job for this file is resumed rather than replaced,
                // so re-tapping Download does not throw away a partial file.
                val prior = existing.firstOrNull { it.id == jobId }
                val entity = DownloadJobEntity(
                    id = jobId,
                    modelId = model.id,
                    modelDisplayName = model.displayName,
                    fileId = file.id,
                    fileName = fileName,
                    url = file.downloadUrl,
                    destPath = File(dir, fileName).absolutePath,
                    totalBytes = file.sizeBytes,
                    downloadedBytes = prior?.downloadedBytes ?: 0L,
                    etag = prior?.etag,
                    expectedSha256 = file.sha256,
                    status = DownloadStatus.QUEUED,
                    bytesPerSecond = 0,
                    attempt = 0,
                    lastError = null,
                    requiresUnmetered = prefs.wifiOnlyDownloads,
                    createdAt = prior?.createdAt ?: now,
                    updatedAt = now
                )
                jobDao.insert(entity)
                schedule(entity)
            }
        }
    }

    /**
     * True when the system is still allowed to doze this app.
     *
     * Aggressive OEM skins -- vivo/iQOO, Xiaomi, OPPO in particular -- kill
     * background workers within seconds unless the app is exempt, which stops a
     * multi-gigabyte download dead with no error and no progress. Detecting it
     * lets the UI ask, rather than leaving the user staring at a frozen bar.
     */
    fun isBatteryRestricted(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system battery-optimisation list. The list screen is used rather
     * than the direct "exempt me" dialog because the latter needs a permission
     * that app stores treat as sensitive, and this achieves the same result.
     */
    fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * True when a queued download cannot start because Wi-Fi-only is on and the
     * phone is on mobile data.
     *
     * Without this the failure is invisible: WorkManager simply holds the job,
     * so the row sits at QUEUED with an empty progress bar and no error, which
     * reads to the user as "the download does nothing".
     */
    fun isBlockedByWifiOnly(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return connected && !unmetered
    }

    /**
     * Turns Wi-Fi-only off and re-schedules every job that was waiting on it, so
     * the change takes effect immediately instead of at the next app start.
     */
    suspend fun allowMeteredDownloads() = withContext(ioDispatcher) {
        settings.update { it.copy(wifiOnlyDownloads = false) }
        val now = System.currentTimeMillis()
        jobDao.forAllWaiting().forEach { job ->
            val updated = job.copy(requiresUnmetered = false, status = DownloadStatus.QUEUED, updatedAt = now)
            jobDao.update(updated)
            // The existing unique work carries the old constraint, so it must be
            // replaced rather than kept.
            workManager.cancelUniqueWork(ModelDownloadWorker.tagFor(job.id))
            schedule(updated, replace = true)
        }
    }

    override suspend fun pause(jobId: String) = withContext(ioDispatcher) {
        jobDao.setStatus(jobId, DownloadStatus.PAUSED, null, System.currentTimeMillis())
        // Cancelling the worker is what actually stops the transfer; the status
        // write above is only what the UI reads.
        workManager.cancelUniqueWork(ModelDownloadWorker.tagFor(jobId))
        Unit
    }

    override suspend fun resume(jobId: String) = withContext(ioDispatcher) {
        val job = jobDao.get(jobId) ?: return@withContext
        jobDao.setStatus(jobId, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        schedule(job)
    }

    override suspend fun cancel(jobId: String) = withContext(ioDispatcher) {
        val job = jobDao.get(jobId)
        workManager.cancelUniqueWork(ModelDownloadWorker.tagFor(jobId))
        job?.let { File(it.destPath + ModelDownloadWorker.PART_SUFFIX).delete() }
        jobDao.delete(jobId)
    }

    override suspend fun retry(jobId: String) = withContext(ioDispatcher) {
        val job = jobDao.get(jobId) ?: return@withContext
        jobDao.setStatus(jobId, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        schedule(job.copy(attempt = job.attempt + 1))
    }

    /**
     * Promotes a model whose files have all finished to an installed model.
     * Called by the observer in the app layer when the last job flips to DONE.
     */
    suspend fun finalizeIfComplete(modelId: String): Boolean = withContext(ioDispatcher) {
        val jobs = jobDao.forModel(modelId)
        if (jobs.isEmpty() || jobs.any { it.status != DownloadStatus.DONE }) return@withContext false

        val model = models.getModel(modelId) ?: return@withContext false
        val dir = File(jobs.first().destPath).parentFile ?: return@withContext false
        models.markInstalled(model, dir)
        jobDao.deleteForModel(modelId)
        true
    }

    private fun schedule(job: DownloadJobEntity, replace: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (job.requiresUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            // Deliberately NOT setRequiresStorageNotLow: that constraint makes
            // WorkManager hold the job silently, with the row stuck at QUEUED,
            // no error and no progress -- indistinguishable from the download
            // being broken. enqueue() already checks free space up front with a
            // 512 MB headroom and fails loudly with the actual numbers, which
            // is the same protection with an explanation attached.
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_JOB_ID, job.id).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag(ModelDownloadWorker.tagFor(job.id))
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.tagFor(job.id),
            // KEEP, not REPLACE: a running transfer should not be restarted from
            // zero because the user tapped the button twice. REPLACE only when
            // the constraints themselves changed, e.g. Wi-Fi-only was turned off.
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun DownloadJobEntity.toDomain() = DownloadJob(
        id = id,
        modelId = modelId,
        modelDisplayName = modelDisplayName,
        fileId = fileId,
        fileName = fileName,
        url = url,
        destPath = destPath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        status = status,
        bytesPerSecond = bytesPerSecond,
        attempt = attempt,
        lastError = lastError,
        requiresUnmetered = requiresUnmetered,
        updatedAt = updatedAt
    )

    class InsufficientStorageException(
        val required: Long,
        val available: Long
    ) : Exception("Not enough free storage for this model.")

    private companion object {
        const val TAG_ALL = "tbchat_download"

        /** Leave the phone room to breathe rather than filling the last byte. */
        const val STORAGE_HEADROOM_BYTES = 512L * 1024 * 1024
    }
}
