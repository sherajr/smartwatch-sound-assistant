package com.peaceantz.stagescope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.peaceantz.stagescope.ui.theme.StageScopeColors

/** A restrained, single-purpose dBFS position bar -- no "good/bad" zones, just where the level sits. */
@Composable
fun LevelBar(
    currentDbfs: Double,
    isClipping: Boolean,
    modifier: Modifier = Modifier,
    minDbfs: Double = -60.0,
    maxDbfs: Double = 0.0,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(5.dp)) {
        val h = size.height
        val radius = CornerRadius(h / 2)
        drawRoundRect(color = StageScopeColors.Grid, size = Size(size.width, h), cornerRadius = radius)
        val fraction = ((currentDbfs - minDbfs) / (maxDbfs - minDbfs)).coerceIn(0.0, 1.0).toFloat()
        val fillColor = if (isClipping) StageScopeColors.Held else StageScopeColors.Live
        if (fraction > 0f) {
            drawRoundRect(color = fillColor, size = Size(size.width * fraction, h), cornerRadius = radius)
        }
    }
}
