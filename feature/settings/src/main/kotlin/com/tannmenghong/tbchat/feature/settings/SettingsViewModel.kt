package com.tannmenghong.tbchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tannmenghong.tbchat.core.device.ThermalGovernor
import com.tannmenghong.tbchat.domain.model.AppRelease
import com.tannmenghong.tbchat.domain.model.AppSettings
import com.tannmenghong.tbchat.domain.model.NetworkEvent
import com.tannmenghong.tbchat.domain.model.PerformanceMode
import com.tannmenghong.tbchat.domain.model.UpdateCheck
import com.tannmenghong.tbchat.domain.model.UpdateProgress
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.NetworkLogRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import com.tannmenghong.tbchat.domain.repository.UpdateRepository
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.service.InferenceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The self-update flow, surfaced as an explicit state the Settings screen renders. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data object Offline : UpdateUiState
    data class Available(val release: AppRelease) : UpdateUiState
    data class Downloading(
        val fraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long
    ) : UpdateUiState

    data object Verifying : UpdateUiState
    data class ReadyToInstall(val apkPath: String, val needsPermission: Boolean) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val profile: DeviceProfile? = null,
    val networkEvents: List<NetworkEvent> = emptyList(),
    val residentBytes: Long? = null,
    val thermalLabel: String = "Normal"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val device: DeviceRepository,
    private val networkLog: NetworkLogRepository,
    private val inference: InferenceClient,
    private val thermal: ThermalGovernor,
    private val update: UpdateRepository
) : ViewModel() {

    private val resident = MutableStateFlow<Long?>(null)

    /** Shown in the Updates section; read once from the installed package. */
    val currentVersionName: String = update.currentVersion.name

    private val _update = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateUi: StateFlow<UpdateUiState> = _update.asStateFlow()

    private var downloadJob: Job? = null

    val ui: StateFlow<SettingsUiState> = combine(
        settings.settings,
        device.profile,
        networkLog.events(),
        resident,
        thermal.thermalStatus
    ) { prefs, profile, events, residentBytes, _ ->
        SettingsUiState(
            settings = prefs,
            profile = profile,
            networkEvents = events,
            residentBytes = residentBytes,
            thermalLabel = thermal.statusLabel()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        thermal.start()
        viewModelScope.launch {
            resident.value = (inference.state.value as? InferenceClient.ClientState.Ready)
                ?.residentBytes
        }
    }

    fun setPerformanceMode(mode: PerformanceMode) = update { it.copy(performanceMode = mode) }

    fun setWifiOnly(value: Boolean) = update { it.copy(wifiOnlyDownloads = value) }

    fun setOfflineMode(value: Boolean) = update { it.copy(offlineMode = value) }

    fun setContextLength(value: Int) = update { it.copy(contextLength = value) }

    fun setIdleUnload(enabled: Boolean) =
        update { it.copy(idleUnloadSeconds = if (enabled) 300 else 0) }

    /**
     * Changing the ceiling changes what will fit, so the profile is re-read
     * immediately. Leaving the old verdicts on screen would be showing the user
     * an answer to a question they have just changed.
     */
    fun setMemoryCeiling(percent: Int) {
        viewModelScope.launch {
            settings.update { it.copy(memoryCeilingPercent = percent) }
            device.refresh()
        }
    }

    fun refreshProfile() {
        viewModelScope.launch { device.refresh() }
    }

    fun clearNetworkLog() {
        viewModelScope.launch { networkLog.clear() }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _update.value = UpdateUiState.Checking
            update.check()
                .onSuccess { result ->
                    _update.value = when (result) {
                        is UpdateCheck.Available -> UpdateUiState.Available(result.release)
                        is UpdateCheck.UpToDate -> UpdateUiState.UpToDate
                        UpdateCheck.OfflineBlocked -> UpdateUiState.Offline
                    }
                }
                .onFailure {
                    _update.value = UpdateUiState.Failed(it.message ?: "Could not check for updates.")
                }
        }
    }

    fun downloadUpdate(release: AppRelease) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            update.downloadAndPrepare(release).collect { progress ->
                _update.value = when (progress) {
                    is UpdateProgress.Downloading -> UpdateUiState.Downloading(
                        fraction = progress.fraction,
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                        bytesPerSecond = progress.bytesPerSecond
                    )

                    UpdateProgress.Verifying -> UpdateUiState.Verifying
                    is UpdateProgress.ReadyToInstall -> UpdateUiState.ReadyToInstall(
                        apkPath = progress.apkPath,
                        needsPermission = !update.canRequestInstall()
                    )

                    is UpdateProgress.Failed -> UpdateUiState.Failed(progress.message)
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        downloadJob?.cancel()
        _update.value = UpdateUiState.Idle
    }

    /**
     * Hands the verified APK to Android's installer, or sends the user to grant
     * the install permission first. Never installs silently.
     */
    fun installUpdate() {
        val ready = _update.value as? UpdateUiState.ReadyToInstall ?: return
        if (update.canRequestInstall()) {
            update.installPrepared(ready.apkPath)
        } else {
            update.requestInstallPermission()
            _update.value = ready.copy(needsPermission = true)
        }
    }

    fun grantInstallPermission() = update.requestInstallPermission()

    fun dismissUpdate() {
        downloadJob?.cancel()
        _update.value = UpdateUiState.Idle
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    override fun onCleared() {
        thermal.stop()
        super.onCleared()
    }
}
