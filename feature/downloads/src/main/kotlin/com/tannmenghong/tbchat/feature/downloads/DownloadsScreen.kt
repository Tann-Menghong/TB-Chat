package com.tannmenghong.tbchat.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tannmenghong.tbchat.core.common.Format
import com.tannmenghong.tbchat.core.designsystem.Dimens
import com.tannmenghong.tbchat.core.designsystem.EmptyState
import com.tannmenghong.tbchat.core.designsystem.MonoNumberStyle
import com.tannmenghong.tbchat.core.designsystem.OutlinedSurface
import com.tannmenghong.tbchat.core.designsystem.ProgressBar
import com.tannmenghong.tbchat.core.designsystem.Tone
import com.tannmenghong.tbchat.core.designsystem.VerdictChip
import com.tannmenghong.tbchat.domain.model.DownloadJob
import com.tannmenghong.tbchat.domain.model.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    if (state.jobs.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearFinished) { Text("Clear finished") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.wifiOnly) {
                Text(
                    "Downloads wait for Wi-Fi. Change this in Settings if you want to use mobile data.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 6.dp)
                )
            }

            if (state.jobs.isEmpty()) {
                EmptyState(
                    title = "Nothing downloading",
                    body = "Models you queue appear here. Downloads resume where they left off, " +
                        "even after the app is closed."
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(Dimens.gutter),
                    verticalArrangement = Arrangement.spacedBy(Dimens.gutter)
                ) {
                    items(state.jobs, key = { it.id }) { job ->
                        JobCard(
                            job = job,
                            onPause = { viewModel.pause(job.id) },
                            onResume = { viewModel.resume(job.id) },
                            onRetry = { viewModel.retry(job.id) },
                            onCancel = { viewModel.cancel(job.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: DownloadJob,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    OutlinedSurface {
        Text(
            job.modelDisplayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            job.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        VerdictChip(job.status.label(), job.status.tone())

        Spacer(Modifier.height(8.dp))
        // Indeterminate until the first byte lands: a bar sitting at exactly 0%
        // looks broken, and downloads legitimately spend time there.
        ProgressBar(if (job.totalBytes > 0 && job.downloadedBytes > 0) job.progress else null)

        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append(Format.bytes(job.downloadedBytes))
                append(" / ")
                append(Format.bytes(job.totalBytes))
                if (job.status == DownloadStatus.RUNNING && job.bytesPerSecond > 0) {
                    append("  ").append(Format.bytesPerSecond(job.bytesPerSecond))
                }
                job.etaSeconds?.let { append("  ").append(Format.duration(it)).append(" left") }
            },
            style = MonoNumberStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        job.lastError?.let { error ->
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (job.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                    OutlinedButton(onClick = onPause) { Text("Pause") }

                DownloadStatus.PAUSED -> Button(onClick = onResume) { Text("Resume") }
                DownloadStatus.FAILED -> Button(onClick = onRetry) { Text("Try again") }
                DownloadStatus.VERIFYING, DownloadStatus.DONE, DownloadStatus.CANCELLED -> Unit
            }
            if (job.status != DownloadStatus.DONE) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.QUEUED -> "Waiting"
    DownloadStatus.RUNNING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.VERIFYING -> "Verifying"
    DownloadStatus.DONE -> "Finished"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

private fun DownloadStatus.tone(): Tone = when (this) {
    DownloadStatus.DONE -> Tone.OK
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Tone.BLOCKED
    DownloadStatus.PAUSED -> Tone.WARN
    else -> Tone.NEUTRAL
}
