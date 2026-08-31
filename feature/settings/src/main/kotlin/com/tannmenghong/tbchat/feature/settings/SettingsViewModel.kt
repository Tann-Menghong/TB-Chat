package com.tannmenghong.tbchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tannmenghong.tbchat.core.device.ThermalGovernor
import com.tannmenghong.tbchat.domain.model.AppSettings
import com.tannmenghong.tbchat.domain.model.NetworkEvent
import com.tannmenghong.tbchat.domain.model.PerformanceMode
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.NetworkLogRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import com.tannmenghong.tbchat.inference.api.DeviceProfile
import com.tannmenghong.tbchat.inference.service.InferenceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val thermal: ThermalGovernor
) : ViewModel() {

    private val resident = MutableStateFlow<Long?>(null)

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

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    override fun onCleared() {
        thermal.stop()
        super.onCleared()
    }
}
