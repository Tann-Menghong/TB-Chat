package com.tannmenghong.tbchat.feature.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tannmenghong.tbchat.core.common.Format
import com.tannmenghong.tbchat.core.designsystem.CompatibilityChip
import com.tannmenghong.tbchat.core.designsystem.Dimens
import com.tannmenghong.tbchat.core.designsystem.EmptyState
import com.tannmenghong.tbchat.core.designsystem.OutlinedSurface
import com.tannmenghong.tbchat.core.designsystem.ProgressBar
import com.tannmenghong.tbchat.core.designsystem.SectionHeader
import com.tannmenghong.tbchat.core.designsystem.SpecRow
import com.tannmenghong.tbchat.core.designsystem.Tone
import com.tannmenghong.tbchat.core.designsystem.VerdictChip
import com.tannmenghong.tbchat.inference.api.Compatibility
import com.tannmenghong.tbchat.inference.api.LicenseClass
import com.tannmenghong.tbchat.inference.api.Reason

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFrom(it.toString(), "") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                actions = {
                    // Only GGUF: the picker is deliberately narrow rather than
                    // accepting anything and failing later.
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            state.message?.let { message ->
                Column(modifier = Modifier.fillMaxWidth().padding(Dimens.gutter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                    // Offered only when that is genuinely what is blocking the
                    // queue, so it never appears as noise.
                    if (viewModel.isBlockedByWifiOnly()) {
                        Button(onClick = viewModel::useMobileData) { Text("Use mobile data") }
                    }
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter),
                placeholder = { Text("Filter installed, or search the hub") },
                singleLine = true,
                trailingIcon = {
                    TextButton(
                        onClick = viewModel::searchHub,
                        enabled = state.query.isNotBlank() && !state.offline
                    ) { Text("Hub") }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(Dimens.gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelFilter.entries.forEach { option ->
                    FilterChip(
                        selected = state.filter == option,
                        onClick = { viewModel.setFilter(option) },
                        label = { Text(option.label) }
                    )
                }
            }

            if (state.filter == ModelFilter.RUNNABLE && state.blockedCount > 0) {
                Text(
                    text = "${state.blockedCount} model(s) hidden because this phone cannot run them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.gutter)
                )
            }

            if (state.visible.isEmpty()) {
                EmptyState(
                    title = "Nothing here",
                    body = if (state.filter == ModelFilter.INSTALLED) {
                        "No models are installed yet."
                    } else {
                        "No models match. Try a different filter, or search the hub."
                    }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(Dimens.gutter),
                    verticalArrangement = Arrangement.spacedBy(Dimens.gutter)
                ) {
                    items(state.visible, key = { it.model.id }) { listing ->
                        ModelCard(
                            listing = listing,
                            expanded = expandedId == listing.model.id,
                            onToggle = {
                                expandedId = if (expandedId == listing.model.id) null else listing.model.id
                            },
                            onDownload = { viewModel.download(listing) },
                            onDelete = { viewModel.delete(listing.model.id) },
                            onVerify = { viewModel.verify(listing.model.id) },
                            onPause = { listing.activeJob?.let { viewModel.pause(it.id) } },
                            onResume = { listing.activeJob?.let { viewModel.resume(it.id) } },
                            onCancel = { listing.activeJob?.let { viewModel.cancel(it.id) } }
                        )
                    }
                }
            }
        }
    }

    state.pendingLicense?.let { model ->
        LicenseDialog(
            name = model.displayName,
            licenseName = model.license.name,
            licenseClass = model.license.clazz,
            url = model.license.url,
            restrictions = model.license.restrictions,
            onAccept = { viewModel.acceptLicense(model) },
            onDismiss = viewModel::declineLicense
        )
    }
}

@Composable
private fun ModelCard(
    listing: ModelListing,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val model = listing.model
    val uriHandler = LocalUriHandler.current

    OutlinedSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${model.publisher} · ${Format.parameters(model.parameterCount)} · " +
                        "${model.quantization.label} · ${Format.bytes(model.downloadBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onToggle) { Text(if (expanded) "Less" else "Details") }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompatibilityChip(listing.compatibility)
            if (listing.isInstalled) VerdictChip("Installed", Tone.ACCENT)
            if (model.license.clazz == LicenseClass.NON_COMMERCIAL) {
                VerdictChip("Non-commercial", Tone.WARN)
            }
            if (model.isGated) VerdictChip("Gated", Tone.WARN)
        }

        // The reasons, always. A verdict with no explanation is just an opinion.
        val reasons = when (val compatibility = listing.compatibility) {
            is Compatibility.Supported -> emptyList()
            is Compatibility.Marginal -> compatibility.reasons
            is Compatibility.Unsupported -> compatibility.reasons
        }
        if (reasons.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            reasons.forEach { reason ->
                Text(
                    "• ${reason.explain()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        listing.activeJob?.let { job ->
            Spacer(Modifier.height(8.dp))
            ProgressBar(if (job.totalBytes > 0) job.progress else null)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(Format.bytes(job.downloadedBytes))
                    append(" of ")
                    append(Format.bytes(job.totalBytes))
                    if (job.bytesPerSecond > 0) {
                        append(" · ").append(Format.bytesPerSecond(job.bytesPerSecond))
                    }
                    job.etaSeconds?.let { append(" · ").append(Format.duration(it)).append(" left") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            job.lastError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("What this costs")
            SpecRow("Download", Format.bytes(model.downloadBytes))
            SpecRow("Context", Format.contextLength(model.contextLength))
            model.arch?.let { arch ->
                SpecRow("Layers", arch.layers.toString())
                SpecRow("KV heads", arch.kvHeads.toString())
            }
            listing.compatibility.estimatedTokensPerSec?.let {
                SpecRow("Expected speed", Format.tokensPerSecond(it))
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Licence")
            Text(model.license.name, style = MaterialTheme.typography.bodySmall)
            model.license.restrictions.forEach {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.sourceUrl.isNotBlank()) {
                TextButton(onClick = { uriHandler.openUri(model.sourceUrl) }) {
                    Text("Open the original model page")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                listing.isDownloading -> {
                    OutlinedButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }

                listing.activeJob != null -> {
                    Button(onClick = onResume) { Text("Resume") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }

                listing.isInstalled -> {
                    OutlinedButton(onClick = onVerify) { Text("Verify") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }

                else -> Button(
                    onClick = onDownload,
                    // Disabled rather than hidden: the user should see the
                    // option exists and read why it is not available.
                    enabled = listing.compatibility.canRun
                ) { Text("Download ${Format.bytes(model.downloadBytes)}") }
            }
        }
    }
}

@Composable
private fun LicenseDialog(
    name: String,
    licenseName: String,
    licenseClass: LicenseClass,
    url: String,
    restrictions: List<String>,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you download $name") },
        text = {
            Column {
                Text(
                    "This model is released under $licenseName, which is " +
                        when (licenseClass) {
                            LicenseClass.NON_COMMERCIAL -> "not a commercial-use licence."
                            LicenseClass.USE_RESTRICTED -> "an open licence with conditions."
                            LicenseClass.PERMISSIVE -> "a permissive licence."
                        },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                restrictions.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
                if (url.isNotBlank()) {
                    TextButton(onClick = { uriHandler.openUri(url) }) { Text("Read the full licence") }
                }
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("I accept") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

private fun Reason.explain(): String = when (this) {
    is Reason.InsufficientMemory ->
        "Needs about ${Format.bytes(needed)} of RAM; this phone can spare ${Format.bytes(available)}."

    is Reason.InsufficientStorage ->
        "Needs ${Format.bytes(needed)} free; there is ${Format.bytes(free)}."

    is Reason.AndroidTooOld -> "Requires Android API $required or newer."
    is Reason.UnsupportedAbi -> "This build only runs on 64-bit Arm; this device is $abi."
    is Reason.NoRuntime -> "No runtime in this build can execute this model format."
    is Reason.TightMemory -> detail
    is Reason.SlowCpu -> detail
    is Reason.SlowGeneration ->
        "Expected around ${Format.tokensPerSecond(tokensPerSec)}, which is slow enough to be tedious."
}
