package com.peaceantz.stagescope.ui.analyzer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.peaceantz.stagescope.ui.components.DetailButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.data.SpectrumSnapshot

private val NAME_PRESETS = listOf("Snapshot", "Baseline", "Before EQ", "After EQ", "Ring test", "Reference")

@Composable
fun SnapshotManagerScreen(container: AppContainer, onSelectCompare: (String?) -> Unit) {
    val snapshots by container.snapshotRepository.snapshots.collectAsStateWithLifecycle()
    var renamingId by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 18.dp),
    ) {
        item { Text("Snapshots (${snapshots.size}/5)", color = MaterialTheme.colorScheme.onBackground) }

        if (snapshots.isEmpty()) {
            item { Text("None saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(snapshots, key = { it.id }) { snapshot ->
            if (renamingId == snapshot.id) {
                RenamePresetPicker(
                    onPick = { newName ->
                        scope.launch { container.snapshotRepository.rename(snapshot.id, newName) }
                        renamingId = null
                    },
                    onCancel = { renamingId = null },
                )
            } else {
                SnapshotRow(
                    snapshot = snapshot,
                    onCompare = { onSelectCompare(snapshot.id) },
                    onRename = { renamingId = snapshot.id },
                    onDelete = { scope.launch { container.snapshotRepository.delete(snapshot.id) } },
                )
            }
        }

        item { DetailButton(onClick = { onSelectCompare(null) }, modifier = Modifier.fillMaxWidth()) { Text("Clear comparison") } }
    }
}

@Composable
private fun SnapshotRow(
    snapshot: SpectrumSnapshot,
    onCompare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.foundation.layout.Column {
        Text(snapshot.name, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "${snapshot.sampleRate} Hz · ${if (snapshot.isDemo) "demo" else snapshot.sourceLabel}" +
                if (snapshot.calibrationApplied) " · SPL-aligned" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            DetailButton(onClick = onCompare) { Text("Compare") }
            DetailButton(onClick = onRename) { Text("Rename") }
            DetailButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun RenamePresetPicker(onPick: (String) -> Unit, onCancel: () -> Unit) {
    androidx.compose.foundation.layout.Column {
        Text("Choose a name:", color = MaterialTheme.colorScheme.primary)
        for (preset in NAME_PRESETS) {
            DetailButton(onClick = { onPick(preset) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(preset) }
        }
        DetailButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
