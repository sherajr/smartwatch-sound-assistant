package com.peaceantz.stagescope.ui.components

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

/** Keeps the screen on while [enabled], bounded by the caller's own keep-awake timer. */
@Composable
fun KeepScreenOnEffect(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(enabled, activity) {
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
fun BigMetric(value: String, unit: String, color: Color, description: String) {
    Column(modifier = Modifier.semantics { contentDescription = "$description: $value $unit" }) {
        Text(text = value, color = color, fontWeight = FontWeight.Bold, fontSize = 40.sp)
        Text(text = unit, color = color, fontSize = 14.sp)
    }
}

@Composable
fun StatusMessage(text: String, color: Color) {
    Text(text = text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}
