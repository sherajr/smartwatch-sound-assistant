package com.peaceantz.stagescope.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme

/**
 * Centralized type sizes (design starting points from the spec, not per-watch fixed dimensions --
 * they compose on top of `MaterialTheme.typography.numeral*`, which already carries tabular
 * figures, so widths stay stable as values change). Screens should read from here rather than
 * hardcoding sp values so a single tweak after on-device testing applies everywhere.
 */
object StageScopeType {
    /** Primary reading, e.g. the Level dBFS numeral. Spec range: 32-40sp. */
    val primaryLevelSp = 36.sp

    /** A frequency readout, e.g. Ring hero frequency (roomy context, e.g. Ring's own page). */
    val frequencySp = 30.sp

    /** The Analyzer dial's compact cursor readout -- deliberately smaller than [frequencySp]: it
     *  shares the dial's tight inner "safe zone" with the primary reading, unit, and tags. */
    val compactReadoutSp = 16.sp

    /** Secondary metrics (PK/MAX/AVG, units, status text). Spec range: 12-14sp. */
    val secondarySp = 13.sp

    /** Sparse chart axis/annotation text. Spec range: 11-12sp. */
    val chartAnnotationSp = 11.sp
}

object StageScopeDimens {
    val minTouchTarget = 48.dp
    val iconGlyphSp = 20.sp
    val edgeInset = 14.dp
}

@Composable
@ReadOnlyComposable
fun primaryLevelStyle(): TextStyle = MaterialTheme.typography.numeralLarge.copy(fontSize = StageScopeType.primaryLevelSp)

@Composable
@ReadOnlyComposable
fun frequencyStyle(): TextStyle = MaterialTheme.typography.numeralMedium.copy(fontSize = StageScopeType.frequencySp)

@Composable
@ReadOnlyComposable
fun compactReadoutStyle(): TextStyle = MaterialTheme.typography.numeralMedium.copy(fontSize = StageScopeType.compactReadoutSp)

@Composable
@ReadOnlyComposable
fun secondaryStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(fontSize = StageScopeType.secondarySp)

@Composable
@ReadOnlyComposable
fun chartAnnotationStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontSize = StageScopeType.chartAnnotationSp)
