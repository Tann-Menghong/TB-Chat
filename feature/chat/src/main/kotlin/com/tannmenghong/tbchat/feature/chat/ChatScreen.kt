package com.tannmenghong.tbchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tannmenghong.tbchat.core.common.Format
import com.tannmenghong.tbchat.core.designsystem.Dimens
import com.tannmenghong.tbchat.core.designsystem.EmptyState
import com.tannmenghong.tbchat.core.designsystem.MonoNumberStyle
import com.tannmenghong.tbchat.core.designsystem.ProgressBar
import com.tannmenghong.tbchat.core.designsystem.Tone
import com.tannmenghong.tbchat.core.designsystem.VerdictChip
import com.tannmenghong.tbchat.domain.model.Message
import com.tannmenghong.tbchat.inference.api.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String?,
    onBrowseModels: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(conversationId) { viewModel.open(conversationId) }

    // Follow the stream, but only from the bottom: yanking the view back while
    // the user is scrolling up to reread something is infuriating.
    LaunchedEffect(state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.conversation?.title ?: "Chat",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        state.activeModel?.let {
                            Text(
                                it.model.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { modelMenuOpen = true }) {
                            Text(if (state.activeModel == null) "Choose model" else "Switch")
                        }
                        DropdownMenu(
                            expanded = modelMenuOpen,
                            onDismissRequest = { modelMenuOpen = false }
                        ) {
                            state.installedModels.forEach { installed ->
                                DropdownMenuItem(
                                    text = { Text(installed.model.displayName) },
                                    onClick = {
                                        modelMenuOpen = false
                                        viewModel.selectModel(installed.model.id)
                                    }
                                )
                            }
                            if (state.installedModels.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Get a model") },
                                    onClick = { modelMenuOpen = false; onBrowseModels() }
                                )
                            }
                        }
                    }
                    IconButton(onClick = viewModel::newConversation) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            state.thermalNotice?.let { notice ->
                Notice(notice, Tone.WARN)
            }

            state.error?.let { message ->
                Notice(message, Tone.BLOCKED, onDismiss = viewModel::dismissError)
            }

            state.loadProgress?.let { progress ->
                Column(Modifier.padding(Dimens.gutter)) {
                    Text(
                        "Loading ${state.activeModel?.model?.displayName.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(progress.takeIf { it > 0f })
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    !state.hasAnyModel -> EmptyState(
                        title = "No models installed",
                        body = "Everything here runs on your phone, so there is nothing to talk to " +
                            "until you download a model. The smallest one is under 250 MB.",
                        modifier = Modifier.align(Alignment.Center),
                        action = { TextButton(onClick = onBrowseModels) { Text("Browse models") } }
                    )

                    state.messages.isEmpty() -> EmptyState(
                        title = "Ready",
                        body = "This conversation never leaves your phone. Ask anything.",
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(Dimens.gutter),
                        verticalArrangement = Arrangement.spacedBy(Dimens.gutter)
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(message)
                        }
                    }
                }
            }

            state.prefillProgress?.let { (done, total) ->
                Text(
                    "Reading the conversation: $done of $total tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.gutter)
                )
            }

            state.lastStats?.let { stats ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.gutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "${Format.tokensPerSecond(stats.decodeTokensPerSec)} · " +
                            "first token ${stats.firstTokenMs} ms · " +
                            "context ${stats.contextUsed}/${stats.contextTotal}",
                        style = MonoNumberStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                enabled = state.canSend,
                isGenerating = state.isGenerating,
                canRegenerate = !state.isGenerating &&
                    state.messages.any { it.role == ChatMessage.Role.ASSISTANT },
                onSend = {
                    viewModel.send(input)
                    input = ""
                },
                onStop = viewModel::stop,
                onRegenerate = viewModel::regenerate
            )
        }
    }
}

@Composable
private fun Notice(text: String, tone: Tone, onDismiss: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VerdictChip(if (tone == Tone.BLOCKED) "Error" else "Note", tone)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == ChatMessage.Role.USER
    val background = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 1f)
                .background(background, RoundedCornerShape(Dimens.cardRadius))
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = message.content.ifBlank { if (message.isStreaming) "…" else "" },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (message.isStreaming) {
                    Spacer(Modifier.height(6.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                }
            }
        }

        // Per-turn speed, shown because the number is the honest answer to "is
        // this model worth its size on this phone?".
        message.decodeTokensPerSec?.let { rate ->
            Text(
                text = Format.tokensPerSecond(rate),
                style = MonoNumberStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    canRegenerate: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(Dimens.gutter)
    ) {
        if (canRegenerate) {
            TextButton(onClick = onRegenerate) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Regenerate")
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = enabled || isGenerating,
                maxLines = 6
            )
            Spacer(Modifier.width(8.dp))

            if (isGenerating) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop generating")
                }
            } else {
                IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }

        Text(
            text = "Runs entirely on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
