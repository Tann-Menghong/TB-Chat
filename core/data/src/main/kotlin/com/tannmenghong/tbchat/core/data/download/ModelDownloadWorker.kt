package com.tannmenghong.tbchat.core.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tannmenghong.tbchat.core.data.database.DownloadJobDao
import com.tannmenghong.tbchat.core.data.database.DownloadJobEntity
import com.tannmenghong.tbchat.core.data.database.NetworkEventDao
import com.tannmenghong.tbchat.core.data.database.NetworkEventEntity
import com.tannmenghong.tbchat.core.data.gguf.GgufHeaderValidator
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads one model file, resumably and verifiably.
 *
 * The rules this worker exists to enforce:
 *  - A partial file is resumed with `Range`, but only after `If-Range` confirms
 *    the server still has the same bytes. A changed ETag means the partial is
 *    stale and is discarded rather than spliced onto new content.
 *  - The download lands on `.part` and is only renamed into place after both
 *    the SHA-256 and the GGUF header check pass. There is no window in which a
 *    half-written or corrupt file looks installed.
 *  - Cancellation and process death are normal, not exceptional. Progress is
 *    committed to the database continuously so either one leaves a resumable job.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val client: OkHttpClient,
    private val jobDao: DownloadJobDao,
    private val networkEventDao: NetworkEventDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = jobDao.get(jobId) ?: return Result.failure()

        if (job.status == DownloadStatus.PAUSED || job.status == DownloadStatus.CANCELLED) {
            return Result.success()
        }

        setForegroundSafely(job.modelDisplayName, 0)

        return try {
            download(job)
            Result.success()
        } catch (e: PausedException) {
            // Not a failure: the user asked for this, and the .part file stays.
            Result.success()
        } catch (e: VerificationException) {
            // Retrying byte-for-byte identical corrupt content is pointless, so
            // the partial is destroyed and the job fails with a real reason.
            File(job.destPath + PART_SUFFIX).delete()
            fail(job, e.message ?: "The downloaded file failed verification.")
            Result.failure()
        } catch (e: IOException) {
            fail(job, e.message ?: "Network error")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: Exception) {
            fail(job, e.message ?: e::class.java.simpleName)
            Result.failure()
        }
    }

    private suspend fun download(job: DownloadJobEntity) {
        val partFile = File(job.destPath + PART_SUFFIX)
        partFile.parentFile?.mkdirs()

        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        val builder = Request.Builder()
            .url(job.url)
            .header("User-Agent", USER_AGENT)

        if (existingBytes > 0) {
            builder.header("Range", "bytes=$existingBytes-")
            // Without If-Range a server that has changed the file would happily
            // send the tail of the NEW content to be appended to the OLD prefix,
            // producing a file that is corrupt but exactly the right length.
            job.etag?.let { builder.header("If-Range", it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            when (response.code) {
                200 -> {
                    // Full body: either a fresh start, or the server rejected our
                    // If-Range. Either way the old partial is worthless.
                    if (existingBytes > 0) partFile.delete()
                    existingBytes = 0L
                }

                206 -> Unit // Resume accepted.

                401, 403 -> throw VerificationException(
                    "This model is gated. Accept its licence on the model page, then import the file manually."
                )

                404 -> throw VerificationException("The file is no longer available at this address.")

                416 -> {
                    // Already have the whole thing; nothing left to fetch.
                    verifyAndInstall(job, partFile)
                    return
                }

                else -> throw IOException("Server returned HTTP ${response.code}")
            }

            response.header("ETag")?.let { jobDao.setEtag(job.id, it) }

            val totalBytes = totalBytesOf(response, existingBytes, job.totalBytes)
            writeBody(job, response, partFile, existingBytes, totalBytes)
        }

        verifyAndInstall(job, partFile)
    }

    private fun totalBytesOf(response: Response, alreadyHave: Long, declared: Long): Long {
        val contentLength = response.body?.contentLength() ?: -1L
        return when {
            contentLength > 0 -> alreadyHave + contentLength
            declared > 0 -> declared
            else -> -1L
        }
    }

    private suspend fun writeBody(
        job: DownloadJobEntity,
        response: Response,
        partFile: File,
        startOffset: Long,
        totalBytes: Long
    ) {
        val source = response.body?.byteStream() ?: throw IOException("Empty response body")
        val buffer = ByteArray(BUFFER_BYTES)

        java.io.RandomAccessFile(partFile, "rw").use { out ->
            out.seek(startOffset)

            var written = startOffset
            var lastCommitAt = System.currentTimeMillis()
            var lastCommitBytes = startOffset
            var lastNotifiedPercent = -1

            source.use { input ->
                while (true) {
                    if (isStopped) throw PausedException()

                    val read = input.read(buffer)
                    if (read == -1) break

                    out.write(buffer, 0, read)
                    written += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastCommitAt
                    if (elapsed >= PROGRESS_INTERVAL_MS) {
                        val rate = ((written - lastCommitBytes) * 1000L) / elapsed.coerceAtLeast(1)
                        jobDao.updateProgress(
                            id = job.id,
                            bytes = written,
                            total = totalBytes,
                            rate = rate,
                            status = DownloadStatus.RUNNING,
                            now = now
                        )
                        lastCommitAt = now
                        lastCommitBytes = written

                        val percent = if (totalBytes > 0) ((written * 100) / totalBytes).toInt() else 0
                        if (percent != lastNotifiedPercent) {
                            lastNotifiedPercent = percent
                            setForegroundSafely(job.modelDisplayName, percent)
                        }
                    }
                }
            }

            jobDao.updateProgress(
                id = job.id,
                bytes = written,
                total = totalBytes,
                rate = 0,
                status = DownloadStatus.VERIFYING,
                now = System.currentTimeMillis()
            )

            logBytes(job, written - startOffset)
        }
    }

    /**
     * Two independent checks before the file is allowed to exist under its real
     * name: the publisher's SHA-256 (was this the file we asked for?) and the
     * GGUF header (is it a model at all?). Only then is the rename done, which
     * on the same filesystem is atomic.
     */
    private suspend fun verifyAndInstall(job: DownloadJobEntity, partFile: File) {
        jobDao.setStatus(job.id, DownloadStatus.VERIFYING, null, System.currentTimeMillis())
        setForegroundSafely(job.modelDisplayName, 100, verifying = true)

        if (job.totalBytes > 0 && partFile.length() != job.totalBytes) {
            throw VerificationException(
                "The download is the wrong size (${partFile.length()} of ${job.totalBytes} bytes)."
            )
        }

        job.expectedSha256?.let { expected ->
            val actual = sha256(partFile)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw VerificationException(
                    "The file does not match the checksum published for it. It may have been " +
                        "corrupted in transit or tampered with."
                )
            }
        }

        if (job.fileName.endsWith(".gguf", ignoreCase = true)) {
            GgufHeaderValidator.validate(partFile).getOrElse { cause ->
                throw VerificationException(cause.message ?: "The file is not a valid GGUF model.")
            }
        }

        val target = File(job.destPath)
        target.delete()
        if (!partFile.renameTo(target)) {
            throw VerificationException("Could not move the finished download into place.")
        }

        jobDao.updateProgress(
            id = job.id,
            bytes = target.length(),
            total = target.length(),
            rate = 0,
            status = DownloadStatus.DONE,
            now = System.currentTimeMillis()
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun fail(job: DownloadJobEntity, reason: String) {
        jobDao.setStatus(job.id, DownloadStatus.FAILED, reason, System.currentTimeMillis())
    }

    private suspend fun logBytes(job: DownloadJobEntity, bytes: Long) {
        runCatching {
            networkEventDao.insert(
                NetworkEventEntity(
                    host = job.url.toHttpUrlOrNull()?.host ?: "huggingface.co",
                    purpose = "Download ${job.fileName}",
                    bytes = bytes,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * A download that survives the user leaving the app needs a foreground
     * service, and on Android 12+ a worker can be denied one. That is a reason
     * to keep downloading in the background, not a reason to abort.
     */
    private suspend fun setForegroundSafely(
        modelName: String,
        percent: Int,
        verifying: Boolean = false
    ) {
        runCatching { setForeground(foregroundInfo(modelName, percent, verifying)) }
    }

    private fun foregroundInfo(
        modelName: String,
        percent: Int,
        verifying: Boolean
    ): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Model downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Progress for models being downloaded." }
            )
        }

        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(if (verifying) "Verifying $modelName" else "Downloading $modelName")
            .setContentText(if (verifying) "Checking the file" else "$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, verifying)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private class PausedException : Exception("Paused")

    private class VerificationException(message: String) : Exception(message)

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val PART_SUFFIX = ".part"

        private const val CHANNEL_ID = "tbchat_downloads"
        private const val NOTIFICATION_ID = 4801
        private const val BUFFER_BYTES = 128 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 5
        private const val USER_AGENT = "TB-Chat/1.0 (Android; local inference client)"

        fun tagFor(jobId: String) = "download:$jobId"
    }
}
