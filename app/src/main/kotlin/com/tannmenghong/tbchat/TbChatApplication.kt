package com.tannmenghong.tbchat

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tannmenghong.tbchat.core.common.ApplicationScope
import com.tannmenghong.tbchat.core.data.repository.DownloadRepositoryImpl
import com.tannmenghong.tbchat.core.data.repository.ModelRepositoryImpl
import com.tannmenghong.tbchat.domain.model.DownloadStatus
import com.tannmenghong.tbchat.domain.repository.DownloadRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class TbChatApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var models: ModelRepositoryImpl

    @Inject lateinit var downloads: DownloadRepositoryImpl

    @Inject lateinit var downloadRepository: DownloadRepository

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            models.seedIfEmpty()
            // Nothing is running after a cold start, so any row still claiming
            // to be RUNNING is a leftover from a killed process.
            downloads.reconcileOnStartup()
        }

        // Finished downloads are promoted to installed models here rather than
        // in a ViewModel, so a download that completes while the app is in the
        // background still lands instead of waiting for a screen to be opened.
        appScope.launch {
            downloadRepository.activeJobs()
                // Keyed on completion, not on the set of model ids: the ids do
                // not change when a job flips to DONE, so distinguishing on
                // them alone would never fire.
                .map { jobs ->
                    jobs.groupBy { it.modelId }
                        .filterValues { group -> group.all { it.status == DownloadStatus.DONE } }
                        .keys
                }
                .distinctUntilChanged()
                .collect { modelIds ->
                    modelIds.forEach { downloads.finalizeIfComplete(it) }
                }
        }
    }
}
