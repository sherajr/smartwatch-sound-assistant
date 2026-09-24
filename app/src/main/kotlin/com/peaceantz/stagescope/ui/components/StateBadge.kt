package com.peaceantz.stagescope.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.peaceantz.stagescope.ui.theme.secondaryStyle

/** A symbol+text status label -- color alone never carries the meaning (accessibility + spec). */
@Composable
fun StateBadge(symbol: String, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = symbol, color = color, style = secondaryStyle())
        Text(text = text, color = color, style = secondaryStyle())
    }
}
