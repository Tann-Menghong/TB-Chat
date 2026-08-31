package com.tannmenghong.tbchat.core.data.remote

import com.tannmenghong.tbchat.core.data.database.NetworkEventDao
import com.tannmenghong.tbchat.core.data.database.NetworkEventEntity
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.FileRole
import com.tannmenghong.tbchat.inference.api.License
import com.tannmenghong.tbchat.inference.api.LicenseClass
import com.tannmenghong.tbchat.inference.api.Modality
import com.tannmenghong.tbchat.inference.api.ModelFile
import com.tannmenghong.tbchat.inference.api.ModelFormat
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.RuntimeId
import com.tannmenghong.tbchat.core.common.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The only network dependency in the app, and only for discovering and fetching
 * models. Prompts, conversations and generated output never leave the device,
 * and every call made here is written to the network log so the Settings screen
 * can show the user exactly what was requested and why.
 */
@Singleton
class HuggingFaceClient @Inject constructor(
    private val client: OkHttpClient,
    private val networkEventDao: NetworkEventDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class HubError(message: String) : Exception(message) {
        data object Offline : HubError("No network connection.")
        data class Gated(val repoId: String) : HubError(
            "This model is gated. Accept its terms on huggingface.co, then import the file manually."
        )

        data class NotFound(val repoId: String) : HubError("Model not found on the Hub.")
        data class Transport(val detail: String) : HubError(detail)
    }

    /**
     * Search the Hub for GGUF repositories.
     *
     * The result is deliberately incomplete: `search` returns repository-level
     * data with no file sizes or SHAs, so a hit here is only a candidate. The
     * per-file metadata that download verification depends on comes from
     * [modelDetails], which is called when the user opens the model page.
     */
    suspend fun searchGguf(query: String, limit: Int): Result<List<HubSearchResult>> =
        withContext(ioDispatcher) {
            runCatching {
                val url = "https://huggingface.co/api/models" +
                    "?search=${query.encode()}&filter=gguf&sort=downloads&direction=-1&limit=$limit" +
                    "&full=false"
                val body = get(url, purpose = "Search the model hub")
                val array = json.parseToJsonElement(body).jsonArray
                array.mapNotNull { it.jsonObject.toSearchResult() }
            }
        }

    /**
     * Full metadata for one repository, including the LFS sha256 and byte size
     * of every file. `blobs=true` is what makes download verification possible;
     * without it there is nothing to check the bytes against.
     */
    suspend fun modelDetails(repoId: String): Result<HubModelDetails> = withContext(ioDispatcher) {
        runCatching {
            val body = get(
                "https://huggingface.co/api/models/${repoId.encodePath()}?blobs=true",
                purpose = "Read model file list"
            )
            val obj = json.parseToJsonElement(body).jsonObject
            obj.toModelDetails(repoId)
        }
    }

    /**
     * Turns one GGUF file inside a repository into a downloadable [AiModel].
     *
     * The revision is pinned to the commit SHA the metadata call returned, so a
     * queued download cannot silently change to different weights if the
     * repository is updated between browsing and downloading.
     */
    fun toAiModel(details: HubModelDetails, file: HubFile): AiModel {
        val quant = Quantization.fromFileName(file.path)
        val licenseId = details.license ?: "unknown"
        return AiModel(
            id = "hf:${details.repoId}:${file.path}".lowercase(),
            displayName = "${details.repoId.substringAfterLast('/')} ${quant.label}",
            publisher = details.repoId.substringBefore('/'),
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = quant,
            // Unknown until the header is parsed after download; the compatibility
            // checker falls back to a size-based estimate and says so.
            arch = null,
            files = listOf(
                ModelFile(
                    id = "${details.repoId}/${file.path}",
                    repoId = details.repoId,
                    revision = details.sha,
                    path = file.path,
                    sizeBytes = file.sizeBytes,
                    sha256 = file.sha256,
                    role = FileRole.WEIGHTS
                )
            ),
            license = License(
                id = licenseId,
                name = licenseId.uppercase(),
                clazz = classifyLicense(licenseId),
                url = "https://huggingface.co/${details.repoId}",
                restrictions = if (classifyLicense(licenseId) == LicenseClass.PERMISSIVE) {
                    emptyList()
                } else {
                    listOf("Read the licence on the model page before using outputs commercially.")
                }
            ),
            requiredRuntime = RuntimeId.LLAMA_CPP,
            sourceUrl = "https://huggingface.co/${details.repoId}",
            isGated = details.gated,
            description = "Imported from the Hugging Face Hub."
        )
    }

    private fun classifyLicense(id: String): LicenseClass = when {
        id.startsWith("apache") || id == "mit" || id.startsWith("bsd") || id == "cc0-1.0" ->
            LicenseClass.PERMISSIVE

        id.contains("nc") || id.contains("noncommercial") || id.contains("research") ->
            LicenseClass.NON_COMMERCIAL

        else -> LicenseClass.USE_RESTRICTED
    }

    private suspend fun get(url: String, purpose: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw HubError.Transport(e.message ?: "Network error")
        }

        response.use {
            val bodyText = it.body?.string().orEmpty()
            log(request.url.host, purpose, bodyText.length.toLong())

            when (it.code) {
                200 -> return bodyText
                401, 403 -> throw HubError.Gated(url.substringAfter("/models/").substringBefore('?'))
                404 -> throw HubError.NotFound(url.substringAfter("/models/").substringBefore('?'))
                else -> throw HubError.Transport("Hub returned HTTP ${it.code}")
            }
        }
    }

    private suspend fun log(host: String, purpose: String, bytes: Long) {
        runCatching {
            networkEventDao.insert(
                NetworkEventEntity(
                    host = host,
                    purpose = purpose,
                    bytes = bytes,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private fun JsonObject.toSearchResult(): HubSearchResult? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return HubSearchResult(
            repoId = id,
            downloads = this["downloads"]?.jsonPrimitive?.longOrNull ?: 0L,
            likes = this["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
            gated = this["gated"]?.jsonPrimitive?.contentOrNull.let { it != null && it != "false" },
            tags = (this["tags"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        )
    }

    private fun JsonObject.toModelDetails(repoId: String): HubModelDetails {
        val siblings = (this["siblings"] as? JsonArray).orEmpty()
        val files = siblings.mapNotNull { element ->
            val obj = element.jsonObject
            val path = obj["rfilename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            val lfs = obj["lfs"]?.jsonObject
            HubFile(
                path = path,
                sizeBytes = lfs?.get("size")?.jsonPrimitive?.longOrNull
                    ?: obj["size"]?.jsonPrimitive?.longOrNull
                    ?: 0L,
                sha256 = lfs?.get("sha256")?.jsonPrimitive?.contentOrNull
            )
        }
        val cardData = this["cardData"]?.jsonObject
        return HubModelDetails(
            repoId = repoId,
            // Falling back to `main` would defeat the point of pinning, so a
            // missing SHA is a hard error rather than a silent downgrade.
            sha = this["sha"]?.jsonPrimitive?.contentOrNull
                ?: throw HubError.Transport("Hub did not return a commit SHA to pin the download to."),
            license = cardData?.get("license")?.jsonPrimitive?.contentOrNull,
            gated = this["gated"]?.jsonPrimitive?.contentOrNull.let { it != null && it != "false" },
            downloads = this["downloads"]?.jsonPrimitive?.longOrNull ?: 0L,
            files = files.sortedBy { it.sizeBytes }
        )
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    /** Repo ids contain a slash that must survive; only the segments are escaped. */
    private fun String.encodePath(): String = split('/').joinToString("/") { it.encode() }

    private companion object {
        const val USER_AGENT = "TB-Chat/1.0 (Android; local inference client)"
    }
}

data class HubSearchResult(
    val repoId: String,
    val downloads: Long,
    val likes: Long,
    val gated: Boolean,
    val tags: List<String>
)

data class HubModelDetails(
    val repoId: String,
    val sha: String,
    val license: String?,
    val gated: Boolean,
    val downloads: Long,
    val files: List<HubFile>
)

data class HubFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?
) {
    val quantization: Quantization get() = Quantization.fromFileName(path)
}
