package com.tannmenghong.tbchat.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tannmenghong.tbchat.core.data.repository.DownloadRepositoryImpl
import com.tannmenghong.tbchat.domain.compat.ModelCompatibilityChecker
import com.tannmenghong.tbchat.domain.model.DownloadJob
import com.tannmenghong.tbchat.domain.model.InstalledModel
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.DownloadRepository
import com.tannmenghong.tbchat.domain.repository.ModelRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.Compatibility
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.api.LicenseClass
import com.tannmenghong.tbchat.inference.api.RuntimeId
import com.tannmenghong.tbchat.inference.service.InferenceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One catalog row with its verdict already computed for this specific phone. */
data class ModelListing(
    val model: AiModel,
    val compatibility: Compatibility,
    val installed: InstalledModel?,
    val activeJob: DownloadJob?
) {
    val isInstalled: Boolean get() = installed != null
    val isDownloading: Boolean get() = activeJob?.isActive == true
}

enum class ModelFilter(val label: String) {
    RUNNABLE("Runs on this phone"),
    INSTALLED("Installed"),
    ALL("Everything")
}

data class ModelsUiState(
    val listings: List<ModelListing> = emptyList(),
    val profile: DeviceProfile? = null,
    val filter: ModelFilter = ModelFilter.RUNNABLE,
    val query: String = "",
    val searchingHub: Boolean = false,
    val pendingLicense: AiModel? = null,
    val message: String? = null,
    val offline: Boolean = false
) {
    val visible: List<ModelListing>
        get() = listings
            .filter { listing ->
                when (filter) {
                    // canDownload, not canRun: a model blocked only by how much
                    // memory happens to be free this second would otherwise
                    // vanish from the default view, leaving an empty list that
                    // reads as "there are no models".
                    ModelFilter.RUNNABLE -> listing.compatibility.canDownload || listing.isInstalled
                    ModelFilter.INSTALLED -> listing.isInstalled
                    ModelFilter.ALL -> true
                }
            }
            .filter { listing ->
                query.isBlank() ||
                    listing.model.displayName.contains(query, ignoreCase = true) ||
                    listing.model.publisher.contains(query, ignoreCase = true)
            }

    /** Shown above the list so the numbers below have something to stand against. */
    val blockedCount: Int get() = listings.count { !it.compatibility.canDownload }
}

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val models: ModelRepository,
    private val downloads: DownloadRepository,
    private val device: DeviceRepository,
    private val settings: SettingsRepository,
    private val inference: InferenceClient
) : ViewModel() {

    /**
     * The concrete repository, for the two operations that are implementation
     * detail rather than part of the domain contract: detecting a Wi-Fi-blocked
     * queue and lifting that restriction.
     */
    private val downloadsImpl: DownloadRepositoryImpl?
        get() = downloads as? DownloadRepositoryImpl

    /** True when a queued download is stalled purely because of Wi-Fi-only. */
    fun isBlockedByWifiOnly(): Boolean = downloadsImpl?.isBlockedByWifiOnly() == true

    /** Turns Wi-Fi-only off and restarts everything that was waiting on it. */
    fun useMobileData() {
        viewModelScope.launch {
            downloadsImpl?.allowMeteredDownloads()
            transient.value = "Mobile data allowed. Queued downloads are starting."
        }
    }

    private val filter = MutableStateFlow(ModelFilter.RUNNABLE)
    private val query = MutableStateFlow("")
    private val transient = MutableStateFlow<String?>(null)
    private val pendingLicense = MutableStateFlow<AiModel?>(null)

    /**
     * The runtimes this build can actually execute.
     *
     * Queried from the service rather than assumed: a build with the native
     * engine disabled must report every model as unrunnable instead of offering
     * downloads that cannot possibly work.
     */
    private val availableRuntimes = MutableStateFlow<Set<RuntimeId>>(emptySet())

    val ui: StateFlow<ModelsUiState> = combine(
        combine(models.catalog(), models.installedModels(), downloads.activeJobs()) { c, i, j ->
            Triple(c, i, j)
        },
        device.profile,
        availableRuntimes,
        combine(filter, query, transient, pendingLicense, settings.settings) { f, q, m, p, s ->
            listOf(f, q, m, p, s)
        }
    ) { data, profile, runtimes, controls ->
        val (catalog, installed, jobs) = data
        val currentFilter = controls[0] as ModelFilter
        val currentQuery = controls[1] as String
        val message = controls[2] as String?
        val pending = controls[3] as AiModel?
        val prefs = controls[4] as com.tannmenghong.tbchat.domain.model.AppSettings

        val installedById = installed.associateBy { it.model.id }
        val jobsByModel = jobs.groupBy { it.modelId }

        ModelsUiState(
            listings = catalog.map { model ->
                ModelListing(
                    model = model,
                    compatibility = ModelCompatibilityChecker.check(
                        model = model,
                        profile = profile,
                        contextLength = prefs.contextLength,
                        availableRuntimes = runtimes
                    ),
                    installed = installedById[model.id],
                    activeJob = jobsByModel[model.id]?.firstOrNull { it.isActive }
                )
            }.sortedWith(
                compareByDescending<ModelListing> { it.isInstalled }
                    .thenByDescending { it.compatibility.canRun }
                    .thenBy { it.model.downloadBytes }
            ),
            profile = profile,
            filter = currentFilter,
            query = currentQuery,
            pendingLicense = pending,
            message = message,
            offline = prefs.offlineMode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    init {
        viewModelScope.launch {
            availableRuntimes.value =
                if (inference.isEngineAvailable()) setOf(RuntimeId.LLAMA_CPP) else emptySet()
            device.refresh()
        }
    }

    fun setFilter(value: ModelFilter) { filter.value = value }

    fun setQuery(value: String) { query.value = value }

    /**
     * The download entry point.
     *
     * A model the checker says cannot run is never queued, no matter how the
     * user got to the button -- that is the whole point of the verdict. A
     * licence that needs acknowledging interrupts here rather than after the
     * bytes are already on disk.
     */
    fun download(listing: ModelListing) {
        // Only a permanent incompatibility blocks the fetch. Low free memory is
        // a snapshot of this moment, not of when the model will be loaded, and
        // refusing on it left every model undownloadable on a busy phone.
        if (!listing.compatibility.canDownload) {
            transient.value = "This phone cannot run this model at all, so there is nothing to " +
                "gain from downloading it."
            return
        }
        if (!listing.compatibility.canRun) {
            transient.value = "Downloading, but memory is tight right now. Close some apps before " +
                "running it."
        }

        viewModelScope.launch {
            val prefs = settings.current()
            val license = listing.model.license
            if (license.clazz != LicenseClass.PERMISSIVE &&
                license.id !in prefs.acknowledgedLicenseIds
            ) {
                pendingLicense.value = listing.model
                return@launch
            }
            enqueue(listing.model)
        }
    }

    fun acceptLicense(model: AiModel) {
        viewModelScope.launch {
            settings.update {
                it.copy(acknowledgedLicenseIds = it.acknowledgedLicenseIds + model.license.id)
            }
            pendingLicense.value = null
            enqueue(model)
        }
    }

    fun declineLicense() { pendingLicense.value = null }

    private suspend fun enqueue(model: AiModel) {
        if (model.isGated) {
            transient.value = "This model is gated on Hugging Face. Accept its terms there and " +
                "import the file, or pick an open alternative."
            return
        }

        downloads.enqueue(model)
            .onSuccess {
                // A Wi-Fi-only job on mobile data is held by WorkManager with no
                // error and no progress, which reads as "the download does
                // nothing". Say so, and offer the one-tap way out.
                transient.value = if (downloadsImpl?.isBlockedByWifiOnly() == true) {
                    "${model.displayName} is queued, but downloads are set to Wi-Fi only and " +
                        "you are on mobile data. Tap \"Use mobile data\" to start it now."
                } else {
                    "Downloading ${model.displayName}."
                }
            }
            .onFailure { cause ->
                transient.value = when (cause) {
                    is DownloadRepositoryImpl.InsufficientStorageException ->
                        "Not enough free storage. This model needs " +
                            "${cause.required / (1024 * 1024)} MB and there is " +
                            "${cause.available / (1024 * 1024)} MB free."

                    else -> cause.message ?: "The download could not be started."
                }
            }
    }

    fun delete(modelId: String) {
        viewModelScope.launch {
            models.deleteModel(modelId)
                .onSuccess { freed ->
                    transient.value = "Freed ${freed / (1024 * 1024)} MB."
                }
                .onFailure { transient.value = it.message ?: "Could not delete the model." }
        }
    }

    fun verify(modelId: String) {
        viewModelScope.launch {
            transient.value = "Checking the file…"
            models.verifyIntegrity(modelId)
                .onSuccess { ok ->
                    transient.value = if (ok) {
                        "The file matches its published checksum."
                    } else {
                        "This file does not match its checksum. Delete and download it again."
                    }
                }
                .onFailure { transient.value = it.message }
        }
    }

    fun importFrom(uri: String, displayName: String) {
        viewModelScope.launch {
            models.importModel(uri, displayName)
                .onSuccess { transient.value = "Imported ${it.displayName}." }
                .onFailure { transient.value = it.message ?: "That file could not be imported." }
        }
    }

    fun searchHub() {
        val term = query.value.trim()
        if (term.isBlank()) return

        viewModelScope.launch {
            if (settings.current().offlineMode) {
                transient.value = "Offline mode is on, so the hub is not being contacted."
                return@launch
            }
            transient.value = "Searching Hugging Face…"
            models.searchRemote(term)
                .onSuccess { found ->
                    transient.value = if (found.isEmpty()) {
                        "Nothing matched on the hub."
                    } else {
                        "Found ${found.size} files."
                    }
                }
                .onFailure { transient.value = it.message ?: "The hub could not be reached." }
        }
    }

    fun pause(jobId: String) = viewModelScope.launch { downloads.pause(jobId) }
    fun resume(jobId: String) = viewModelScope.launch { downloads.resume(jobId) }
    fun cancel(jobId: String) = viewModelScope.launch { downloads.cancel(jobId) }

    fun dismissMessage() { transient.value = null }
}
