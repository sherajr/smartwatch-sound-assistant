package com.peaceantz.stagescope.ui.help

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * Static instructions for the two watch-native entry points added alongside the app: the Tile
 * (swipeable, beside the watch face) and the complication (a watch-face slot). Neither can be
 * added programmatically -- there is no public system API for an app to pin its own Tile or set
 * itself as a chosen complication, so this just tells the wearer where to do it themselves.
 */
@Composable
fun WatchShortcutsHelpScreen() {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 20.dp),
    ) {
        item { Text("Watch shortcuts", color = MaterialTheme.colorScheme.onBackground) }

        item { Text("Add the Tile", color = MaterialTheme.colorScheme.primary) }
        item {
            Text(
                "From the watch face, swipe right (or press the crown and swipe) to the Tile carousel, " +
                    "scroll to the end, tap Add, and choose StageScope.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Text("Add the complication", color = MaterialTheme.colorScheme.primary) }
        item {
            Text(
                "Long-press the watch face, tap Edit, select a complication slot the face supports, " +
                    "and choose StageScope. Which slots are offered depends on the watch face.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text(
                "Both show your last saved reading and ring frequency -- neither uses the microphone " +
                    "on its own.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
