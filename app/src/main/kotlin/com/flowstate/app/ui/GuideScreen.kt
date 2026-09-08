package com.flowstate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The quick guide. Shown full-screen on first launch (before anything else); afterwards
 * reachable anytime from the "?" button next to the tabs.
 */
@Composable
fun GuideScreen(onClose: () -> Unit) {
    BackHandler { onClose() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            Text(
                "FLOWSTATE",
                style = MaterialTheme.typography.labelMedium,
                color = ActiColors.riding,
                letterSpacing = 2.sp,
            )
            Text(
                "Music that rides with you",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 32.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "ActiPlayer watches how you're moving and matches the music's energy — " +
                    "hands in gloves, phone in pocket. Here's the whole setup:",
                style = MaterialTheme.typography.bodyMedium,
                color = ActiColors.dim,
                modifier = Modifier.padding(top = 10.dp),
            )
            HorizontalDivider(Modifier.padding(top = 16.dp), color = ActiColors.outline)
        }

        GuideStep(
            "1", "Rate your music",
            "In the Library tab, rate tracks by energy: 1 = couch, 5 = send it. " +
                "A handful of each is enough to start. Unrated tracks play as middle energy.",
        )
        GuideStep(
            "2", "Pair a heart-rate monitor (optional)",
            "In the Session tab, tap Set up monitor — the app will ask to turn on " +
                "Bluetooth if it's off. On a Garmin watch, enable Broadcast Heart Rate " +
                "first (Settings → Sensors & Accessories → Wrist Heart Rate). " +
                "Chest straps just work.",
        )
        GuideStep(
            "3", "Pick your activity, start, pocket the phone",
            "Choose Snowboarding or Cycling on the Session tab (more coming), then tap " +
                "Start session and ride. Working hard gets your high-energy tracks, " +
                "easing off calms the music — automatically.",
        )
        GuideStep(
            "4", "Override anytime",
            "CHILL / AUTO / HYPE buttons at the bottom of the session screen. " +
                "AUTO hands control back to the engine.",
        )

        Spacer(Modifier.weight(1f))
        Text(
            "Reopen this guide anytime with the ? button at the top.",
            style = MaterialTheme.typography.bodySmall,
            color = ActiColors.dim,
        )
        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Got it", fontSize = 17.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun GuideStep(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .background(ActiColors.riding, CircleShape),
        ) {
            Text(
                number,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = ActiColors.ground,
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = ActiColors.dim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
