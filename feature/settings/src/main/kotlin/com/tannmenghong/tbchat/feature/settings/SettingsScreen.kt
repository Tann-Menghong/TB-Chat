package com.tannmenghong.tbchat.feature.settings

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.tannmenghong.tbchat.core.designsystem.MonoNumberStyle
import com.tannmenghong.tbchat.core.designsystem.OutlinedSurface
import com.tannmenghong.tbchat.core.designsystem.ProgressBar
import com.tannmenghong.tbchat.core.designsystem.SectionHeader
import com.tannmenghong.tbchat.core.designsystem.SpecRow
import com.tannmenghong.tbchat.domain.model.PerformanceMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val settings = state.settings
    val updateState by viewModel.updateUi.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.gutter)
        ) {
            OutlinedSurface {
                SectionHeader("Performance")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PerformanceMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.performanceMode == mode,
                            onClick = { viewModel.setPerformanceMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    settings.performanceMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                Text("Memory ceiling", style = MaterialTheme.typography.bodyMedium)
                Text(
                    // The honest framing: this is not free headroom, it is a
                    // trade against every other app staying alive in the
                    // background.
                    "The share of total RAM a model is allowed to occupy. Raising this lets " +
                        "bigger models load, and makes Android more likely to kill your other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.memoryCeilingPercent.toFloat(),
                    onValueChange = { viewModel.setMemoryCeiling(it.toInt()) },
                    valueRange = 30f..70f,
                    steps = 7
                )
                state.profile?.let { profile ->
                    SpecRow(
                        "${settings.memoryCeilingPercent}% of ${Format.bytes(profile.memory.totalBytes)}",
                        Format.bytes(profile.memory.usableBytes)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Context length", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "How much conversation the model can see. Doubling this roughly doubles the " +
                        "memory the KV cache needs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096, 8192, 16384).forEach { length ->
                        FilterChip(
                            selected = settings.contextLength == length,
                            onClick = { viewModel.setContextLength(length) },
                            label = { Text(Format.contextLength(length)) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Unload the model when idle",
                    detail = "Frees ${
                        state.residentBytes?.let { Format.bytes(it) } ?: "the model's memory"
                    } after ${settings.idleUnloadSeconds / 60} minutes without a message.",
                    checked = settings.idleUnloadSeconds > 0,
                    onChange = viewModel::setIdleUnload
                )
            }

            OutlinedSurface {
                SectionHeader("Downloads")
                ToggleRow(
                    label = "Wi-Fi only",
                    detail = "Models are hundreds of megabytes to several gigabytes.",
                    checked = settings.wifiOnlyDownloads,
                    onChange = viewModel::setWifiOnly
                )
                state.profile?.let { profile ->
                    Spacer(Modifier.height(8.dp))
                    SpecRow("Free storage", Format.bytes(profile.storage.freeBytes))
                    SpecRow("Used by models", Format.bytes(profile.storage.modelDirBytes))
                }
            }

            OutlinedSurface {
                SectionHeader("Privacy")
                Text(
                    "Prompts, conversations and generated text never leave this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The only network traffic this app makes is fetching model files and their " +
                        "metadata. Every request is listed below so you can check that claim " +
                        "rather than take it on trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Offline mode",
                    detail = "Blocks all network access, including model search and downloads.",
                    checked = settings.offlineMode,
                    onChange = viewModel::setOfflineMode
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Network log", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::clearNetworkLog) { Text("Clear") }
                }

                if (state.networkEvents.isEmpty()) {
                    Text(
                        "Nothing has been requested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.networkEvents.take(20).forEach { event ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${event.host} · ${Format.bytes(event.bytes)}",
                                style = MonoNumberStyle
                            )
                            Text(
                                "${event.purpose} · ${Format.relativeTime(event.timestamp)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedSurface {
                SectionHeader("This device")
                state.profile?.let { profile ->
                    SpecRow("Model", "${profile.manufacturer} ${profile.deviceModel}")
                    SpecRow("Android API", profile.androidApi.toString())
                    SpecRow("ABI", profile.abi)
                    SpecRow("Tier", profile.tier.name)
                    SpecRow("Performance cores", profile.cpu.performanceCores.toString())
                    SpecRow("Total cores", profile.cpu.totalCores.toString())
                    SpecRow("Int8 dot product", if (profile.cpu.hasDotProduct) "Yes" else "No")
                    SpecRow("i8mm", if (profile.cpu.hasI8mm) "Yes" else "No")
                    SpecRow("Vulkan", profile.gpu.vulkanVersion ?: "Not reported")
                    SpecRow("Thermal status", state.thermalLabel)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::refreshProfile) { Text("Re-read device") }
            }

            OutlinedSurface {
                SectionHeader("Updates")
                SpecRow("Installed version", viewModel.currentVersionName)
                Spacer(Modifier.height(8.dp))
                UpdatesBody(
                    state = updateState,
                    onCheck = viewModel::checkForUpdates,
                    onDownload = viewModel::downloadUpdate,
                    onInstall = viewModel::installUpdate,
                    onGrantPermission = viewModel::grantInstallPermission,
                    onCancel = viewModel::cancelUpdateDownload,
                    onDismiss = viewModel::dismissUpdate
                )
            }

            OutlinedSurface {
                SectionHeader("About")
                Text(
                    "TB-Chat runs open language models entirely on your phone using llama.cpp. " +
                        "There is no account, no server, and no cloud fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UpdatesBody(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (com.tannmenghong.tbchat.domain.model.AppRelease) -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        UpdateUiState.Idle,
        UpdateUiState.UpToDate,
        UpdateUiState.Offline,
        is UpdateUiState.Failed -> {
            val note = when (state) {
                UpdateUiState.UpToDate -> "You are on the latest version."
                UpdateUiState.Offline -> "Offline mode is on, so no update check was made."
                is UpdateUiState.Failed -> state.message
                else -> "Updates are downloaded from the app's GitHub releases and verified before install."
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is UpdateUiState.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCheck) { Text("Check for updates") }
        }

        UpdateUiState.Checking -> {
            ProgressBar(null)
            Spacer(Modifier.height(6.dp))
            Text("Checking…", style = MaterialTheme.typography.bodySmall)
        }

        is UpdateUiState.Available -> {
            Text(
                "Version ${state.release.versionName} is available.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (state.release.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.release.notes.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDownload(state.release) }) {
                    Text("Download ${Format.bytes(state.release.apkSizeBytes)}")
                }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }

        is UpdateUiState.Downloading -> {
            ProgressBar(if (state.totalBytes > 0) state.fraction else null)
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(Format.bytes(state.downloadedBytes)).append(" / ")
                    append(Format.bytes(state.totalBytes))
                    if (state.bytesPerSecond > 0) {
                        append("  ").append(Format.bytesPerSecond(state.bytesPerSecond))
                    }
                },
                style = MonoNumberStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }

        UpdateUiState.Verifying -> {
            ProgressBar(null)
            Spacer(Modifier.height(6.dp))
            Text("Verifying the download…", style = MaterialTheme.typography.bodySmall)
        }

        is UpdateUiState.ReadyToInstall -> {
            if (state.needsPermission) {
                Text(
                    "Allow TB-Chat to install apps to finish updating. Android will ask you to " +
                        "confirm the install itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGrantPermission) { Text("Allow installs") }
                    Button(onClick = onInstall) { Text("Try again") }
                }
            } else {
                Text(
                    "Downloaded and verified. Android will ask you to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onInstall) { Text("Install now") }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
