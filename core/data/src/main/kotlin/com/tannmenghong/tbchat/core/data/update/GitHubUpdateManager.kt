package com.tannmenghong.tbchat.core.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.tannmenghong.tbchat.core.common.IoDispatcher
import com.tannmenghong.tbchat.core.data.database.NetworkEventDao
import com.tannmenghong.tbchat.core.data.database.NetworkEventEntity
import com.tannmenghong.tbchat.core.data.settings.SettingsDataSource
import com.tannmenghong.tbchat.domain.model.AppRelease
import com.tannmenghong.tbchat.domain.model.AppVersion
import com.tannmenghong.tbchat.domain.model.UpdateCheck
import com.tannmenghong.tbchat.domain.model.UpdateProgress
import com.tannmenghong.tbchat.domain.repository.UpdateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Self-update over the sideloaded distribution channel (GitHub Releases).
 *
 * The security posture matches the rest of the app: the only network call is to
 * the release metadata and the APK itself, both logged to the network log; the
 * download is verified against the published digest before it is offered; and
 * the install is handed to Android's own package installer, which shows the
 * system confirmation dialog. Nothing is installed silently, and the OS still
 * enforces that the new APK is signed with the same key as the installed one.
 */
@Singleton
class GitHubUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val networkEventDao: NetworkEventDao,
    private val settings: SettingsDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UpdateRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val currentVersion: AppVersion by lazy { readInstalledVersion() }

    override suspend fun check(): Result<UpdateCheck> = withContext(ioDispatcher) {
        runCatching {
            if (settings.current().offlineMode) return@runCatching UpdateCheck.OfflineBlocked

            val body = get(LATEST_RELEASE_URL, purpose = "Check for an app update")
            val release = parseRelease(body)
                ?: return@runCatching UpdateCheck.UpToDate(currentVersion)

            if (isNewer(release.versionName, currentVersion.name)) {
                UpdateCheck.Available(currentVersion, release)
            } else {
                UpdateCheck.UpToDate(currentVersion)
            }
        }
    }

    override fun downloadAndPrepare(release: AppRelease): Flow<UpdateProgress> = flow {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // A fresh file every time: a stale partial from a previous version must
        // never be mistaken for this release's APK.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.apkName.ifBlank { "tbchat-update.apk" })

        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            emit(UpdateProgress.Failed("Could not reach the update server: ${e.message}"))
            return@flow
        }

        response.use {
            if (!it.isSuccessful) {
                emit(UpdateProgress.Failed("The update server returned HTTP ${it.code}."))
                return@flow
            }
            val source = it.body?.byteStream()
                ?: run { emit(UpdateProgress.Failed("The update response was empty.")); return@flow }

            val total = if (release.apkSizeBytes > 0) release.apkSizeBytes
            else it.body?.contentLength() ?: -1L

            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            var lastEmit = 0L
            var lastBytes = 0L
            var startedAt = 0L

            target.outputStream().use { out ->
                source.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive() // cancellation = Stop
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read

                        val now = System.nanoTime() / 1_000_000
                        if (startedAt == 0L) startedAt = now
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            val rate = if (now > lastEmit && lastEmit > 0) {
                                ((written - lastBytes) * 1000L) / (now - lastEmit)
                            } else 0L
                            emit(UpdateProgress.Downloading(written, total, rate))
                            lastEmit = now
                            lastBytes = written
                        }
                    }
                }
            }

            logBytes(written)

            if (total > 0 && written != total) {
                emit(UpdateProgress.Failed("The update download was incomplete ($written of $total bytes)."))
                target.delete()
                return@flow
            }

            emit(UpdateProgress.Verifying)
            // GitHub publishes the digest as "sha256:<hex>"; take the hex part.
            val rawDigest = release.sha256
            val digest = if (rawDigest.isNullOrBlank()) null else rawDigest.substringAfter(':', rawDigest)
            if (digest != null) {
                val actual = sha256(target)
                if (!actual.equals(digest, ignoreCase = true)) {
                    emit(UpdateProgress.Failed("The update failed its integrity check and was discarded."))
                    target.delete()
                    return@flow
                }
            }

            emit(UpdateProgress.ReadyToInstall(target.absolutePath))
        }
    }.flowOn(ioDispatcher)

    override fun installPrepared(apkPath: String) {
        val file = File(apkPath)
        if (!file.isFile) {
            Log.w(TAG, "install requested for a missing file: $apkPath")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "could not launch the package installer", it) }
    }

    override fun canRequestInstall(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override fun requestInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun readInstalledVersion(): AppVersion {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = pkg.longVersionCode
        // Strip a debug/build suffix so "1.0.1-debug" still compares as 1.0.1.
        val name = (pkg.versionName ?: "0").substringBefore('-')
        return AppVersion(name = name, code = code)
    }

    private fun parseRelease(body: String): AppRelease? {
        val obj = json.parseToJsonElement(body).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = tag.trimStart('v', 'V')
        val notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val assets = (obj["assets"] as? JsonArray).orEmpty()
        val apk = assets.map { it.jsonObject }
            .filter { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").endsWith(".apk", true) }
            // An unsigned APK can never install; prefer a signed release build,
            // then any other installable APK the release happens to carry.
            .filterNot { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").contains("unsigned", true) }
            .minByOrNull { asset ->
                val n = (asset["name"]?.jsonPrimitive?.contentOrNull ?: "").lowercase()
                if ("release" in n) 0 else 1
            } ?: return null

        return AppRelease(
            versionName = version,
            tag = tag,
            notes = notes,
            apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
            apkName = apk["name"]?.jsonPrimitive?.contentOrNull ?: "tbchat-$version.apk",
            apkSizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            sha256 = apk["digest"]?.jsonPrimitive?.contentOrNull
        )
    }

    /** Numeric, component-wise semver compare. Missing components count as 0. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
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

    private suspend fun get(url: String, purpose: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("No network connection.", e)
        }
        return response.use {
            val text = it.body?.string().orEmpty()
            logBytes(text.length.toLong(), host = it.request.url.host, purpose = purpose)
            if (!it.isSuccessful) throw IOException("Update check failed (HTTP ${it.code}).")
            text
        }
    }

    private suspend fun logBytes(
        bytes: Long,
        host: String = "api.github.com",
        purpose: String = "Download app update"
    ) {
        runCatching {
            networkEventDao.insert(
                NetworkEventEntity(host = host, purpose = purpose, bytes = bytes, timestamp = System.currentTimeMillis())
            )
        }
    }

    private companion object {
        const val TAG = "UpdateManager"
        const val USER_AGENT = "TB-Chat/updater (Android)"
        const val BUFFER_BYTES = 128 * 1024
        const val PROGRESS_INTERVAL_MS = 400L

        // The distribution channel. Kept here so a fork only changes one line.
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Tann-Menghong/TB-Chat/releases/latest"
    }
}
