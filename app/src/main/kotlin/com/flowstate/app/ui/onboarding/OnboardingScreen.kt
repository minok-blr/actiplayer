package com.flowstate.app.ui.onboarding

import com.flowstate.app.ui.modesLine
import com.flowstate.app.ui.label
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.Display
import com.flowstate.app.ui.glyph
import com.flowstate.data.MediaLibrary
import com.flowstate.data.SettingsStore
import com.flowstate.data.SourceKind
import com.flowstate.engine.Activity
import com.flowstate.sensors.HeartRateMonitor

/** The wizard's four numbered steps; Welcome sits outside the count, as in the design. */
private enum class Step { WELCOME, ACTIVITY, SOURCE, HEART_RATE, PERMISSIONS }

/**
 * Screens A1–A5 (plan §6.5).
 *
 * The wizard asks for one thing at a time and explains each in the athlete's terms rather
 * than the system's — especially the permissions, which are requested individually, at the
 * moment their reason has just been read, rather than as one opaque wall at launch.
 */
@Composable
fun OnboardingScreen(
    settings: SettingsStore,
    heartRate: HeartRateMonitor,
    library: MediaLibrary,
    onDone: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.WELCOME) }
    // Skipping the heart-rate step means Bluetooth is never asked for (plan §4, A5).
    var wantsHeartRate by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ActiColors.ground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        when (step) {
            Step.WELCOME -> WelcomeStep { step = Step.ACTIVITY }
            Step.ACTIVITY -> ActivityStep(settings) { step = Step.SOURCE }
            Step.SOURCE -> SourceStep(settings, library) { step = Step.HEART_RATE }
            Step.HEART_RATE -> HeartRateStep(
                settings = settings,
                heartRate = heartRate,
                onContinue = {
                    wantsHeartRate = true
                    step = Step.PERMISSIONS
                },
                onSkip = {
                    wantsHeartRate = false
                    step = Step.PERMISSIONS
                },
            )
            Step.PERMISSIONS -> PermissionsStep(
                includeBluetooth = wantsHeartRate,
                title = "What the app reads",
                onFinish = onDone,
            )
        }
    }
}

// ------------------------------------------------------------------ A1

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "ActiPlayer",
            fontFamily = Display,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
            color = ActiColors.riding,
        )
        Spacer(Modifier.weight(1f))

        // The idea in one picture: modes in sequence, each its own colour.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "Riding" to ActiColors.riding,
                "Lift" to ActiColors.lift,
                "Riding" to ActiColors.riding,
                "Break" to ActiColors.paused,
            ).forEachIndexed { index, (label, color) ->
                Column(Modifier.weight(if (index == 1 || index == 3) 0.6f else 1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(color),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ActiColors.dim)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Music that follows your body.",
            fontSize = 38.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
            color = ActiColors.snow,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Ride, walk, run or cruise. ActiPlayer reads your motion and heart rate and " +
                "switches the energy of the music for you. Phone in pocket, gloves on.",
            fontSize = 16.sp,
            lineHeight = 23.sp,
            color = ActiColors.dim,
        )
        Spacer(Modifier.weight(2f))
        PrimaryButton("Set up in 1 minute", onClick = onStart)
        Spacer(Modifier.height(24.dp))
        // Edge to edge: keep the button clear of the navigation bar.
        Spacer(Modifier.navigationBarsPadding())
    }
}

// ------------------------------------------------------------------ A2

@Composable
private fun ActivityStep(settings: SettingsStore, onContinue: () -> Unit) {
    val selected by settings.activity.collectAsStateWithLifecycle()
    StepScaffold(
        index = 1,
        title = "What do you do most?",
        subtitle = "Each activity has its own detection profile. You can switch anytime " +
            "from Home.",
        primary = "Continue",
        onPrimary = onContinue,
    ) {
        // Two columns, as drawn — the plan's full grid, every card live since phase 6.
        Activity.entries.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // Intrinsic min height so a card whose name wraps to two lines does not
                // leave the one beside it short.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(bottom = 10.dp),
            ) {
                row.forEach { activity ->
                    ActivityCard(
                        activity = activity,
                        selected = selected == activity,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { settings.setActivity(activity) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: Activity,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val content = if (selected) ActiColors.ground else ActiColors.snow
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) ActiColors.riding else ActiColors.surface)
            .clickable(onClick = onSelect)
            .padding(14.dp),
    ) {
        Icon(
            activity.glyph,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            activity.label(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = content,
        )
        Text(
            activity.modesLine(),
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = if (selected) ActiColors.ground.copy(alpha = 0.8f) else ActiColors.dim,
        )
    }
}

// ------------------------------------------------------------------ A3

@Composable
private fun SourceStep(settings: SettingsStore, library: MediaLibrary, onContinue: () -> Unit) {
    val source by settings.musicSource.collectAsStateWithLifecycle()
    var trackCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { trackCount = library.tracks().count { !it.isPodcast } }

    StepScaffold(
        index = 2,
        title = "Where's your music?",
        subtitle = "Pick one now, add the other later in Music.",
        primary = "Continue",
        onPrimary = onContinue,
    ) {
        SourceCard(
            title = "On this phone",
            selected = source == SourceKind.LOCAL,
            accent = null,
            body = when (trackCount) {
                null -> "Scanning your library…"
                0 -> "No music found yet. Copy files into Music/ and they'll appear."
                else -> "Found $trackCount tracks. Works offline, on the mountain, " +
                    "no account."
            },
            chips = listOf("Free", "Works offline", "Crossfades"),
        ) { settings.setMusicSource(SourceKind.LOCAL) }

        Spacer(Modifier.height(10.dp))

        SourceCard(
            title = "Spotify",
            selected = source == SourceKind.SPOTIFY,
            accent = ActiColors.spotify,
            body = "Choose a playlist per energy level. ActiPlayer tells the Spotify app " +
                "what to play. Needs Spotify Premium and a signal.",
            chips = listOf("Premium", "Falls back to local"),
        ) { settings.setMusicSource(SourceKind.SPOTIFY) }
    }
}

@Composable
private fun SourceCard(
    title: String,
    selected: Boolean,
    accent: Color?,
    body: String,
    chips: List<String>,
    onSelect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ActiColors.surface)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) ActiColors.snow else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onSelect)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (accent != null) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(10.dp))
            }
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ActiColors.snow)
            Spacer(Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) ActiColors.snow else ActiColors.dim, CircleShape),
            ) {
                if (selected) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(ActiColors.snow))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = ActiColors.dim)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach {
                Text(
                    it,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ActiColors.dim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ActiColors.surfaceHigh)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ shared chrome

/**
 * The frame every numbered step shares: progress, "n / 4", title, scrolling content, and
 * a bottom action that never scrolls away.
 */
@Composable
internal fun StepScaffold(
    /** Null outside the wizard: no progress bar, no step counter. */
    index: Int?,
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit,
    optional: Boolean = false,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        if (index != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                (1..STEP_COUNT).forEach { segment ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (segment <= index) ActiColors.snow else ActiColors.surfaceHigh,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Step $index of $STEP_COUNT${if (optional) " · optional" else ""}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ActiColors.dim,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            title,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = ActiColors.snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ActiColors.dim)
        Spacer(Modifier.height(20.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }

        Spacer(Modifier.height(12.dp))
        PrimaryButton(primary, enabled = primaryEnabled, onClick = onPrimary)
        if (secondary != null && onSecondary != null) {
            Text(
                secondary,
                color = ActiColors.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSecondary)
                    .padding(vertical = 16.dp),
            )
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = if (enabled) ActiColors.ground else ActiColors.dim,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) ActiColors.snow else ActiColors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 22.dp),
    )
}

internal const val STEP_COUNT = 4

