package com.tannmenghong.tbchat.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tannmenghong.tbchat.domain.model.Conversation
import com.tannmenghong.tbchat.domain.model.DownloadJob
import com.tannmenghong.tbchat.domain.repository.ConversationRepository
import com.tannmenghong.tbchat.domain.repository.DeviceRepository
import com.tannmenghong.tbchat.domain.repository.DownloadRepository
import com.tannmenghong.tbchat.domain.repository.ModelRepository
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

data class HomeUiState(
    val profile: DeviceProfile? = null,
    val engineAvailable: Boolean = true,
    val installedCount: Int = 0,
    val installedBytes: Long = 0,
    val recentConversations: List<Conversation> = emptyList(),
    val activeDownloads: List<DownloadJob> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val models: ModelRepository,
    private val conversations: ConversationRepository,
    private val downloads: DownloadRepository,
    private val device: DeviceRepository,
    private val inference: InferenceClient
) : ViewModel() {

    private val engineAvailable = MutableStateFlow(true)

    val ui: StateFlow<HomeUiState> = combine(
        models.installedModels(),
        conversations.conversations(),
        downloads.activeJobs(),
        device.profile,
        engineAvailable
    ) { installed, threads, jobs, profile, engine ->
        HomeUiState(
            profile = profile,
            engineAvailable = engine,
            installedCount = installed.size,
            installedBytes = installed.sumOf { it.bytesOnDisk },
            recentConversations = threads.take(RECENT_LIMIT),
            activeDownloads = jobs.filter { it.isActive }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            engineAvailable.value = inference.isEngineAvailable()
            device.refresh()
        }
    }

    private companion object {
        const val RECENT_LIMIT = 5
    }
}
