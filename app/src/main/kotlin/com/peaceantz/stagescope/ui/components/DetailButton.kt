package com.peaceantz.stagescope.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults

/**
 * A Details/Actions-screen button on the restrained "subtle surface" tone, not the vivid
 * live-green accent -- the default `Button` fills with `colorScheme.primary`, which would
 * reproduce the first pass's oversized-colored-button problem (just green instead of yellow).
 * Green/red stay reserved for live/held state, per the visual system.
 */
@Composable
fun DetailButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    // No default width: single-button list rows should pass Modifier.fillMaxWidth() explicitly
    // (long labels need the room to wrap rather than overflowing the round-safe area); buttons
    // grouped side-by-side in a Row should stay wrap-content or use Modifier.weight(1f).
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(),
        content = content,
    )
}
