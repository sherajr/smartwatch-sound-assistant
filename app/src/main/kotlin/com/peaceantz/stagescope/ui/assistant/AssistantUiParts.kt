package com.peaceantz.stagescope.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.R
import com.peaceantz.stagescope.ui.components.DetailButton
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle
import com.peaceantz.stagescope.ui.theme.secondaryStyle

/** Small centered explanatory text. Never carries meaning by color alone: [Tone] only adds emphasis to words already there. */
enum class Tone { NEUTRAL, GOOD, WARN, BAD }

@Composable
fun toneColor(tone: Tone): Color {
    val palette = LocalStageScopePalette.current
    return when (tone) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.GOOD -> palette.Live
        Tone.WARN, Tone.BAD -> palette.Held
    }
}

@Composable
fun Hint(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    Text(text, color = toneColor(tone), style = secondaryStyle(), textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onBackground, style = secondaryStyle(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

/** A full-width list button with an optional second line. */
@Composable
fun ChipButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, secondary: String? = null, enabled: Boolean = true) {
    DetailButton(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (secondary != null) {
                Text(secondary, style = chartAnnotationStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The big microphone control. Tapping it only ever *opens* the listening screen; nothing records until that screen is showing. */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 76.dp) {
    val palette = LocalStageScopePalette.current
    Box(
        modifier = modifier
            .size(size)
            .background(if (enabled) palette.Live else palette.Grid, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "Ask the assistant by voice"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_assistant_mic), contentDescription = null, tint = palette.Background, modifier = Modifier.size(size * 0.46f))
    }
}
