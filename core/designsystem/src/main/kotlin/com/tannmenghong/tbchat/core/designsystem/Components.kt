package com.tannmenghong.tbchat.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tannmenghong.tbchat.inference.api.Compatibility

/**
 * The verdict chip. Used on every surface that offers a model, because the
 * honesty rule is that nothing is tappable before the user knows what it costs.
 */
@Composable
fun VerdictChip(
    label: String,
    tone: Tone,
    modifier: Modifier = Modifier
) {
    val verdict = LocalVerdictColors.current
    val (fg, bg) = when (tone) {
        Tone.OK -> verdict.ok to verdict.okContainer
        Tone.WARN -> verdict.warn to verdict.warnContainer
        Tone.BLOCKED -> verdict.blocked to verdict.blockedContainer
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
        Tone.ACCENT -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
    }

    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(Dimens.chipRadius))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (tone != Tone.NEUTRAL) {
            Box(Modifier.size(6.dp).background(fg, CircleShape))
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

enum class Tone { OK, WARN, BLOCKED, NEUTRAL, ACCENT }

@Composable
fun CompatibilityChip(compatibility: Compatibility, modifier: Modifier = Modifier) {
    when (compatibility) {
        is Compatibility.Supported -> VerdictChip("Runs on this phone", Tone.OK, modifier)
        is Compatibility.Marginal -> VerdictChip("Runs, with caveats", Tone.WARN, modifier)
        is Compatibility.Unsupported -> VerdictChip("Will not run", Tone.BLOCKED, modifier)
    }
}

/** A label/value pair with the value in tabular mono, for spec sheets. */
@Composable
fun SpecRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MonoNumberStyle,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        action?.invoke()
    }
}

@Composable
fun OutlinedSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Dimens.gutter),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Dimens.cardRadius))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(Dimens.cardRadius)
            )
            .padding(contentPadding),
        content = content
    )
}

/**
 * A progress bar that distinguishes "no progress yet" from "zero progress".
 * Downloads spend real time in the former state and a stuck-looking 0% bar is a
 * bug report waiting to happen.
 */
@Composable
fun ProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier
) {
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        action?.let {
            Spacer(Modifier.size(8.dp))
            it()
        }
    }
}
