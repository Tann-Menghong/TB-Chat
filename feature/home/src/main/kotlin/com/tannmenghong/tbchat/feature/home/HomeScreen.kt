package com.tannmenghong.tbchat.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tannmenghong.tbchat.core.common.Format
import com.tannmenghong.tbchat.core.designsystem.Dimens
import com.tannmenghong.tbchat.core.designsystem.OutlinedSurface
import com.tannmenghong.tbchat.core.designsystem.ProgressBar
import com.tannmenghong.tbchat.core.designsystem.SectionHeader
import com.tannmenghong.tbchat.core.designsystem.SpecRow
import com.tannmenghong.tbchat.core.designsystem.Tone
import com.tannmenghong.tbchat.core.designsystem.VerdictChip
import com.tannmenghong.tbchat.inference.api.DeviceTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (String?) -> Unit,
    onOpenModels: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("TB-Chat") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.gutter)
        ) {
            // The engine's absence is stated first and plainly. A build with no
            // native library cannot run anything, and pretending otherwise
            // wastes the user's bandwidth on downloads that will not work.
            if (!state.engineAvailable) {
                OutlinedSurface {
                    VerdictChip("Engine unavailable", Tone.BLOCKED)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This build has no inference engine, so no model can run. Install a build " +
                            "with the native library included.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            OutlinedSurface {
                SectionHeader("This phone")
                state.profile?.let { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VerdictChip(profile.tier.describe(), profile.tier.tone())
                    }
                    Spacer(Modifier.height(8.dp))
                    SpecRow("Device", "${profile.manufacturer} ${profile.deviceModel}")
                    SpecRow("Total RAM", Format.bytes(profile.memory.totalBytes))
                    // Usable, not total: the number that actually decides what
                    // can be loaded right now.
                    SpecRow("Usable for a model", Format.bytes(profile.memory.usableBytes))
                    SpecRow("Performance cores", profile.cpu.performanceCores.toString())
                    SpecRow(
                        "Int8 dot product",
                        if (profile.cpu.hasDotProduct) "Yes" else "No — large models will be slow"
                    )
                    SpecRow("Free storage", Format.bytes(profile.storage.freeBytes))
                    SpecRow("Models on disk", Format.bytes(profile.storage.modelDirBytes))

                    if (!profile.isCalibrated) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Speed figures are estimates until a model has actually run here.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedSurface {
                SectionHeader("Chat")
                if (state.installedCount == 0) {
                    Text(
                        "Nothing is installed yet. Models run entirely on this phone, so one has " +
                            "to be downloaded before anything can happen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenModels) { Text("Choose a model") }
                } else {
                    Text(
                        "${state.installedCount} model(s) installed, " +
                            "${Format.bytes(state.installedBytes)} on disk.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenChat(null) }) { Text("New chat") }
                        OutlinedButton(onClick = onOpenModels) { Text("Models") }
                    }
                }
            }

            if (state.recentConversations.isNotEmpty()) {
                OutlinedSurface {
                    SectionHeader("Recent")
                    state.recentConversations.forEach { conversation ->
                        TextButton(
                            onClick = { onOpenChat(conversation.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(conversation.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    Format.relativeTime(conversation.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (state.activeDownloads.isNotEmpty()) {
                OutlinedSurface {
                    SectionHeader("Downloading")
                    state.activeDownloads.forEach { job ->
                        Text(job.modelDisplayName, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        ProgressBar(if (job.totalBytes > 0) job.progress else null)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            OutlinedSurface {
                SectionHeader("Image and video")
                Text(
                    "Not in this release.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                // Stating this plainly is the point. A phone that can hold a 2 GB
                // language model still cannot hold the activation memory a
                // diffusion or video model needs at a usable resolution, and
                // shipping a stub that takes ten minutes per clip would be worse
                // than shipping nothing.
                Text(
                    "Image generation needs several gigabytes of peak working memory beyond the " +
                        "weights, and video generation needs far more than that. Neither fits in " +
                        "the memory Android will give a single app on a typical phone today. " +
                        "They are on the roadmap behind a hardware check, not hidden behind a " +
                        "button that would fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun DeviceTier.describe(): String = when (this) {
    DeviceTier.MINIMAL -> "Below the useful minimum"
    DeviceTier.ENTRY -> "Entry class"
    DeviceTier.MAINSTREAM -> "Mainstream"
    DeviceTier.FLAGSHIP -> "Flagship"
    DeviceTier.ACCELERATED -> "Flagship with NPU stack"
}

private fun DeviceTier.tone(): Tone = when (this) {
    DeviceTier.MINIMAL -> Tone.BLOCKED
    DeviceTier.ENTRY -> Tone.WARN
    DeviceTier.MAINSTREAM -> Tone.OK
    DeviceTier.FLAGSHIP, DeviceTier.ACCELERATED -> Tone.ACCENT
}
