package com.flowstate.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ScreenHeader

/**
 * Screen D1. Recording lands in phase 4 (`SessionRecorder` + recap + share), so today
 * this is the empty state only — deliberately, rather than a fake list.
 */
@Composable
fun HistoryScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "History", subtitle = "Every session, mode by mode")
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.Timeline,
                    contentDescription = null,
                    tint = ActiColors.dim,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    "No sessions yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = ActiColors.snow,
                )
                Text(
                    "Sessions are not being recorded yet — that arrives with the recap " +
                        "and share card. Ride now, and this fills up later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ActiColors.dim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
