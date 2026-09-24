package com.peaceantz.stagescope.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.data.AppTheme
import com.peaceantz.stagescope.ui.components.DetailButton
import com.peaceantz.stagescope.ui.theme.StageScopePalette
import com.peaceantz.stagescope.ui.theme.StageScopePalettes

/**
 * Theme + Dim appearance are shown together: Dim is a separate multiplier that applies on top of
 * whichever palette is selected, not a theme of its own, so previews here reflect both at once.
 */
@Composable
fun AppearanceScreen(
    container: AppContainer,
    settingsViewModel: SettingsViewModel = viewModel { SettingsViewModel(container) },
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 18.dp),
    ) {
        item { Text("Appearance", color = MaterialTheme.colorScheme.onBackground) }

        item {
            DetailButton(
                onClick = { settingsViewModel.setDimAppearance(!settings.dimAppearanceEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (settings.dimAppearanceEnabled) "Dim: ON" else "Dim: OFF")
            }
        }
        item {
            Text(
                "Lowers on-screen luminance for a dark theatre, for any palette below. Does not change system brightness.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Text("Color theme", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        for ((theme, label) in StageScopePalettes.ALL) {
            item {
                ThemeRow(
                    label = label,
                    palette = StageScopePalettes.forTheme(theme),
                    dim = settings.dimAppearanceEnabled,
                    selected = settings.theme == theme,
                    onSelect = { settingsViewModel.setTheme(theme) },
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    label: String,
    palette: StageScopePalette,
    dim: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val live = if (dim) palette.LiveDim else palette.Live
    val held = if (dim) palette.HeldDim else palette.Held
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (selected) palette.Surface else MaterialTheme.colorScheme.background)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) live else MaterialTheme.colorScheme.outline,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(live)
        Swatch(held)
        Text(
            text = if (selected) "$label ✓" else label,
            color = if (selected) live else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(color))
}
