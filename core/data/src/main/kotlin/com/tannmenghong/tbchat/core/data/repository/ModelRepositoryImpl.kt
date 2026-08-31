package com.tannmenghong.tbchat.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tannmenghong.tbchat.core.data.database.InstalledModelDao
import com.tannmenghong.tbchat.core.data.database.InstalledModelEntity
import com.tannmenghong.tbchat.core.data.database.ModelCatalogDao
import com.tannmenghong.tbchat.core.data.database.ModelCatalogEntity
import com.tannmenghong.tbchat.core.data.gguf.GgufHeaderValidator
import com.tannmenghong.tbchat.core.data.remote.HuggingFaceClient
import com.tannmenghong.tbchat.core.device.DeviceCapabilityManager
import com.tannmenghong.tbchat.core.data.settings.SettingsDataSource
import com.tannmenghong.tbchat.domain.catalog.SeedCatalog
import com.tannmenghong.tbchat.domain.model.InstalledModel
import com.tannmenghong.tbchat.domain.repository.ModelRepository
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.FileRole
import com.tannmenghong.tbchat.inference.api.License
import com.tannmenghong.tbchat.inference.api.LicenseClass
import com.tannmenghong.tbchat.inference.api.Modality
import com.tannmenghong.tbchat.inference.api.ModelFile
import com.tannmenghong.tbchat.inference.api.ModelFormat
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.RuntimeId
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.tannmenghong.tbchat.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class ModelRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogDao: ModelCatalogDao,
    private val installedDao: InstalledModelDao,
    private val hub: HuggingFaceClient,
    private val device: DeviceCapabilityManager,
    private val settings: SettingsDataSource,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ModelRepository {

    /**
     * Seeds the built-in catalog on first run.
     *
     * The seed entries carry real pinned commit SHAs, byte sizes and checksums
     * gathered from the Hub at build time, which is what lets the app show
     * accurate size and memory figures -- and verify a download -- with no
     * network access at all.
     */
    suspend fun seedIfEmpty() = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        catalogDao.upsertAll(SeedCatalog.models.map { it.toEntity(isRemote = false, now = now) })
    }

    override fun catalog(): Flow<List<AiModel>> =
        catalogDao.observeAll().map { rows -> rows.mapNotNull { it.toAiModelOrNull() } }

    override fun installedModels(): Flow<List<InstalledModel>> =
        installedDao.observeAll().map { rows -> rows.mapNotNull { it.toInstalledOrNull() } }

    /** Storage totals for the Models screen header. */
    fun installedBytes(): Flow<Long> = installedDao.observeTotalBytes()

    /** True when a model is both installed and still physically present. */
    fun installedAndPresent(): Flow<List<InstalledModel>> =
        combine(installedModels(), settings.settings) { installed, _ ->
            installed.filter { File(it.weightsPath).exists() }
        }

    override suspend fun getModel(id: String): AiModel? = withContext(ioDispatcher) {
        installedDao.get(id)?.toInstalledOrNull()?.model
            ?: catalogDao.get(id)?.toAiModelOrNull()
    }

    override suspend fun getInstalled(id: String): InstalledModel? = withContext(ioDispatcher) {
        installedDao.get(id)?.toInstalledOrNull()
    }

    override suspend fun isInstalled(id: String): Boolean = withContext(ioDispatcher) {
        installedDao.exists(id) && getInstalled(id)?.let { File(it.weightsPath).exists() } == true
    }

    /**
     * Records a finished download as an installed model, freezing the metadata
     * as it stands today so the model keeps working even if it later vanishes
     * from the catalog upstream.
     */
    suspend fun markInstalled(model: AiModel, localDir: File, importedByUser: Boolean = false) =
        withContext(ioDispatcher) {
            val bytes = localDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            installedDao.upsert(
                InstalledModelEntity(
                    modelId = model.id,
                    localDir = localDir.absolutePath,
                    installedAt = System.currentTimeMillis(),
                    lastUsedAt = null,
                    useCount = 0,
                    bytesOnDisk = bytes,
                    integrityVerified = true,
                    importedByUser = importedByUser,
                    snapshotJson = json.encodeToString(AiModel.serializer(), model)
                )
            )
        }

    override suspend fun deleteModel(id: String): Result<Long> = withContext(ioDispatcher) {
        runCatching {
            val installed = installedDao.get(id)
                ?: return@runCatching 0L
            val dir = File(installed.localDir)
            val freed = if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else 0L

            if (dir.exists() && !dir.deleteRecursively()) {
                throw java.io.IOException("Could not delete the model files.")
            }
            installedDao.delete(id)

            // An imported model has no upstream to redownload from, so its
            // catalog row goes too rather than becoming an unusable ghost entry.
            if (installed.importedByUser) {
                catalogDao.delete(id)
            }
            freed
        }
    }

    /**
     * Imports a GGUF the user already has.
     *
     * The file is copied rather than referenced: a content URI is not a stable
     * path, the source may live on removable storage, and llama.cpp needs a real
     * file descriptor it can mmap. The header is parsed for whatever metadata it
     * can supply, and anything that is not a valid GGUF is refused before a byte
     * is committed.
     */
    override suspend fun importModel(uriString: String, displayName: String): Result<AiModel> =
        withContext(ioDispatcher) {
            runCatching {
                val uri = Uri.parse(uriString)
                val sourceName = queryDisplayName(uri) ?: "imported.gguf"

                if (!sourceName.endsWith(".gguf", ignoreCase = true)) {
                    throw IllegalArgumentException(
                        "Only GGUF files can be imported. Pickle-based formats such as .ckpt and " +
                            ".pt execute code when loaded and are not supported."
                    )
                }

                val id = "local:${UUID.randomUUID()}"
                val dir = File(device.modelsDirectory(), id.replace(':', '_'))
                dir.mkdirs()
                val dest = File(dir, sourceName)

                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    } ?: throw java.io.IOException("Could not open the selected file.")

                    val info = GgufHeaderValidator.validate(dest).getOrThrow()

                    val model = AiModel(
                        id = id,
                        displayName = displayName.ifBlank { sourceName.removeSuffix(".gguf") },
                        publisher = "Imported",
                        modalities = setOf(Modality.CHAT),
                        format = ModelFormat.GGUF,
                        quantization = Quantization.fromFileName(sourceName),
                        // No catalog entry means no architecture facts; the
                        // compatibility checker degrades to a size-based estimate
                        // and labels the result as an estimate.
                        arch = null,
                        files = listOf(
                            ModelFile(
                                id = id,
                                repoId = "",
                                revision = "",
                                path = sourceName,
                                sizeBytes = dest.length(),
                                sha256 = null,
                                role = FileRole.WEIGHTS
                            )
                        ),
                        license = License(
                            id = "user-supplied",
                            name = "Supplied by you",
                            clazz = LicenseClass.USE_RESTRICTED,
                            url = "",
                            restrictions = listOf(
                                "This file was imported from your device. Its licence terms are " +
                                    "whatever the original publisher set."
                            )
                        ),
                        requiredRuntime = RuntimeId.LLAMA_CPP,
                        sourceUrl = "",
                        description = buildString {
                            append("Imported from your device")
                            info.architecture?.let { append(" (architecture: ").append(it).append(')') }
                            append('.')
                        }
                    )

                    markInstalled(model, dir, importedByUser = true)
                    catalogDao.upsertAll(
                        listOf(model.toEntity(isRemote = false, now = System.currentTimeMillis()))
                    )
                    model
                } catch (e: Exception) {
                    // A failed import must not leave a half-copied multi-gigabyte
                    // file eating the user's storage.
                    dir.deleteRecursively()
                    throw e
                }
            }
        }

    override suspend fun markUsed(id: String) = withContext(ioDispatcher) {
        installedDao.markUsed(id, System.currentTimeMillis())
    }

    /**
     * Re-hashes the file on disk against the checksum recorded at install time.
     * Slow and deliberately manual -- it is a diagnostic for "the model stopped
     * loading", not something to run on every launch.
     */
    override suspend fun verifyIntegrity(id: String): Result<Boolean> = withContext(ioDispatcher) {
        runCatching {
            val installed = getInstalled(id) ?: return@runCatching false
            val file = File(installed.weightsPath)
            if (!file.exists()) {
                installedDao.setVerified(id, false)
                return@runCatching false
            }

            val expected = installed.model.weightsFile?.sha256
            val ok = if (expected == null) {
                // Nothing to compare against (an imported file); a valid header
                // is the strongest claim that can honestly be made.
                GgufHeaderValidator.isValid(file)
            } else {
                sha256(file).equals(expected, ignoreCase = true)
            }

            installedDao.setVerified(id, ok)
            ok
        }
    }

    override suspend fun searchRemote(query: String, limit: Int): Result<List<AiModel>> =
        withContext(ioDispatcher) {
            if (settings.current().offlineMode) {
                return@withContext Result.success(emptyList())
            }

            hub.searchGguf(query, limit).mapCatching { results ->
                // Only the top hits get a detail call: each one is a separate
                // request, and the list view does not need every file.
                results.take(DETAIL_FETCH_LIMIT).flatMap { hit ->
                    hub.modelDetails(hit.repoId).getOrNull()?.let { details ->
                        details.files
                            .filter { it.sizeBytes > 0 }
                            .map { hub.toAiModel(details, it) }
                    }.orEmpty()
                }.also { models ->
                    catalogDao.upsertAll(
                        models.map { it.toEntity(isRemote = true, now = System.currentTimeMillis()) }
                    )
                }
            }
        }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun AiModel.toEntity(isRemote: Boolean, now: Long) = ModelCatalogEntity(
        id = id,
        displayName = displayName,
        publisher = publisher,
        paramCount = parameterCount,
        totalSizeBytes = downloadBytes,
        licenseClass = license.clazz,
        isRemote = isRemote,
        json = json.encodeToString(AiModel.serializer(), this),
        updatedAt = now
    )

    // A row whose JSON no longer parses (a schema change shipped in an update)
    // is dropped rather than crashing the list it appears in.
    private fun ModelCatalogEntity.toAiModelOrNull(): AiModel? =
        runCatching {
            this@ModelRepositoryImpl.json.decodeFromString(AiModel.serializer(), this.json)
        }.getOrNull()

    private fun InstalledModelEntity.toInstalledOrNull(): InstalledModel? = runCatching {
        InstalledModel(
            model = json.decodeFromString(AiModel.serializer(), snapshotJson),
            localDir = localDir,
            installedAt = installedAt,
            lastUsedAt = lastUsedAt,
            useCount = useCount,
            bytesOnDisk = bytesOnDisk,
            integrityVerified = integrityVerified,
            importedByUser = importedByUser
        )
    }.getOrNull()

    private companion object {
        const val DETAIL_FETCH_LIMIT = 12
    }
}
