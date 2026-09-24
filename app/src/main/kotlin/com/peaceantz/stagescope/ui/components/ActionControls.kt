package com.peaceantz.stagescope.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.theme.LocalStageScopePalette
import com.peaceantz.stagescope.ui.theme.StageScopeDimens

/**
 * A single compact, round-screen-friendly action: a glyph inside a 48dp hit target on a subtle
 * (non-luminous) surface. Deliberately not a big filled pill -- that was the first-pass problem.
 */
@Composable
fun CompactGlyphButton(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val palette = LocalStageScopePalette.current
    Box(
        modifier = modifier
            .size(StageScopeDimens.minTouchTarget)
            .background(palette.Surface, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = StageScopeDimens.iconGlyphSp,
        )
    }
}

/** At most two of these belong in the lower action area of any main page (per the round-screen spec). */
@Composable
fun PrimaryActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        content = content,
    )
}
