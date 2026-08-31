package com.tannmenghong.tbchat.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tannmenghong.tbchat.domain.model.DownloadJob
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import com.tannmenghong.tbchat.domain.repository.DownloadRepository
import com.tannmenghong.tbchat.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val jobs: List<DownloadJob> = emptyList(),
    val wifiOnly: Boolean = true
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    settings: SettingsRepository
) : ViewModel() {

    val ui: StateFlow<DownloadsUiState> =
        combine(downloads.activeJobs(), settings.settings) { jobs, prefs ->
            DownloadsUiState(
                // Active work first, so the thing the user came to check is at
                // the top rather than buried under finished rows.
                jobs = jobs.sortedWith(
                    compareByDescending<DownloadJob> { it.isActive }.thenBy { it.modelDisplayName }
                ),
                wifiOnly = prefs.wifiOnlyDownloads
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun pause(jobId: String) = viewModelScope.launch { downloads.pause(jobId) }
    fun resume(jobId: String) = viewModelScope.launch { downloads.resume(jobId) }
    fun retry(jobId: String) = viewModelScope.launch { downloads.retry(jobId) }
    fun cancel(jobId: String) = viewModelScope.launch { downloads.cancel(jobId) }

    fun clearFinished() {
        viewModelScope.launch {
            ui.value.jobs
                .filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
                .forEach { downloads.cancel(it.id) }
        }
    }
}
