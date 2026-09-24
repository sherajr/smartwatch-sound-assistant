package com.peaceantz.stagescope.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.theme.StageScopeDimens
import com.peaceantz.stagescope.ui.theme.chartAnnotationStyle

/**
 * Shared skeleton for a main pager page: a small centered mode label that doubles as the
 * (always-visible, labeled) route to Details/Actions, centered primary content, and a lower row
 * of the page's own compact controls. Ring is the only remaining user of this scaffold (Analyzer
 * moved to its own full-bleed circular composition -- see AnalyzerScreen/InstrumentGeometry), so
 * its chrome is kept deliberately tight: the Ring page's 2-1-2 tile grid needs most of the
 * available vertical room, and a round 240dp display doesn't leave much of it to spare.
 *
 * Details deliberately is NOT a third button in the lower row: on this round display, 3 circular
 * 48dp controls side by side get clipped by the bezel that close to the bottom edge (confirmed
 * on-device -- the outer two lose their edges). Two is the proven-safe maximum for that row, so
 * every page keeps to at most two, and Details rides along on the mode title instead.
 */
@Composable
fun ModePageScaffold(
    modeTitle: String,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
    lowerActions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenScaffold(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = StageScopeDimens.edgeInset, end = StageScopeDimens.edgeInset, top = 14.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$modeTitle ›",
                style = chartAnnotationStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(onClick = onOpenDetails)
                    .semantics {
                        contentDescription = "Open $modeTitle details and actions"
                        role = Role.Button
                    },
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
            PrimaryActionRow(content = lowerActions)
        }
    }
}
