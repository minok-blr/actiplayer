package com.flowstate.app.ui.session

import androidx.compose.material3.LocalTextStyle
import com.flowstate.app.ui.Tabular
import android.app.Activity as AndroidActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.flowstate.app.ui.ActiBottomSheet
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ArtTile
import com.flowstate.app.ui.Display
import androidx.compose.ui.text.font.FontFamily
import com.flowstate.app.ui.energyTint
import com.flowstate.app.ui.header
import com.flowstate.app.ui.rememberAlbumArt
import com.flowstate.engine.Activity
import com.flowstate.engine.Signal
import com.flowstate.session.OverrideMode
import kotlin.time.Duration
import kotlinx.coroutines.delay

/**
 * Screens B2 / B3 — the live session.
 *
 * Everything here is built for a glove and a glance in sunlight: the mode colour fills
 * the screen edge to edge so the state is readable without focusing, the mode name is the
 * biggest thing on it, and the interactive targets sit in the thumb zone. Mode colour is
 * the *only* saturated fill, so nothing else competes with it.
 */
@Composable
fun SessionScreen(state: SessionUiState, vm: SessionViewModel) {
    KeepScreenOnWhileCharging()
    DarkStatusBarIcons()

    var confirmEnd by remember { mutableStateOf(false) }
    val modeColor = state.mode?.color ?: ActiColors.paused
    // Plan §5: mode change is a 300 ms tween, so the screen never snaps between colours.
    val background by animateColorAsState(modeColor, tween(300), label = "modeColor")
    val haptics = LocalHapticFeedback.current
    var undoVisible by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(background),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SessionHeader(state, onEnd = { confirmEnd = true })
            val timer = state.timer
            if (timer != null) {
                // Plan §4: tap *anywhere* ends the set early — the whole space above the
                // card, not just the block of text, so a pocket-blind thumb finds it.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !timer.finished,
                            onClickLabel = "End this interval early",
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.endIntervalEarly()
                            undoVisible = true
                        },
                ) {
                    TimerBlock(
                        state,
                        timer,
                        undoVisible = undoVisible,
                        onUndoExpired = { undoVisible = false },
                        onUndo = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.undoEndEarly()
                            undoVisible = false
                        },
                    )
                }
            } else {
                ModeBlock(state)
                Spacer(Modifier.weight(1f))
            }
            NowPlayingCard(state, vm)
        }
    }

    if (confirmEnd) {
        ActiBottomSheet(onDismiss = { confirmEnd = false }) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("End this session?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "The music stops and the sensors shut down. You'll find it in History.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ActiColors.dim,
                )
                Spacer(Modifier.height(4.dp))
                SheetButton("End session", filled = true) {
                    confirmEnd = false
                    vm.end()
                }
                SheetButton("Keep going", filled = false) { confirmEnd = false }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) ActiColors.riding else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = if (filled) ActiColors.ground else ActiColors.snow,
        )
    }
}

/**
 * Plan §4: the screen stays awake only while charging. On battery it times out normally —
 * a session runs for hours in a pocket, and a permanently lit screen would be the single
 * biggest drain on the device.
 */
@Composable
private fun KeepScreenOnWhileCharging() {
    val view = LocalView.current
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        // Sticky broadcast: the current state arrives on registration, no polling needed.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            view.keepScreenOn = false
        }
    }

    DisposableEffect(charging) {
        view.keepScreenOn = charging
        onDispose {}
    }
}

/**
 * Every mode colour is light enough that white status-bar icons wash out on it (orange is
 * 3.2:1, ice blue under 2:1), so for the length of the session the icons go dark — as
 * drawn on B2 — and back to light for the dark tab screens.
 */
@Composable
private fun DarkStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = true
        onDispose { controller?.isAppearanceLightStatusBars = false }
    }
}

private tailrec fun Context.findActivity(): AndroidActivity? = when (this) {
    is AndroidActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SessionHeader(state: SessionUiState, onEnd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp),
    ) {
        Text(
            state.timer?.let {
                // As drawn on B4 — the plan rides along with the round — but on two lines:
                // on one, a larger font scale ellipsised away "OF 8", the part that matters.
                "${state.activity.header()} · ${it.planLine}\nRound ${it.round} of ${it.totalRounds}"
            } ?: "${state.activity.header()} · ${state.elapsed.clock()}",
            fontSize = 15.sp,
            lineHeight = 19.sp,
            style = LocalTextStyle.current.merge(Tabular),
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = ActiColors.ground.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        // Ground-coloured text on a darker tint of the mode, as drawn: white on ice blue
        // or orange was the least legible thing on the screen.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(ActiColors.ground.copy(alpha = 0.16f))
                .clickable(onClickLabel = "End session", role = Role.Button, onClick = onEnd)
                .padding(horizontal = 24.dp),
        ) {
            Text("End", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ActiColors.ground)
        }
    }
}

/**
 * Screen B4 — the clock-driven session.
 *
 * There is nothing to explain here: no signal decided this, the plan did. So the sentence,
 * the signal chips and the band meter all give way to the one number that matters.
 */
@Composable
private fun TimerBlock(
    state: SessionUiState,
    timer: TimerUi,
    undoVisible: Boolean,
    onUndoExpired: () -> Unit,
    onUndo: () -> Unit,
) {
    // A pocket tap must not cost a set, so ending early stays reversible for a moment.
    LaunchedEffect(undoVisible) {
        if (undoVisible) {
            delay(UNDO_WINDOW_MS)
            onUndoExpired()
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(
            state.workLabel(timer),
            fontFamily = Display,
            fontSize = 64.sp,
            lineHeight = 68.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.5).sp,
            color = ActiColors.ground,
        )
        // Plan §5: the countdown is 96 sp — the one number worth reading mid-set.
        Text(
            timer.remainingMs.countdown(),
            fontSize = 96.sp,
            lineHeight = 100.sp,
            fontFamily = Display,
            // Tabular, so the seconds tick in place rather than shuffling the line.
            style = LocalTextStyle.current.merge(Tabular),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-2).sp,
            maxLines = 1,
            color = ActiColors.ground,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ActiColors.ground.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(timer.progress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ActiColors.ground),
            )
        }
        Spacer(Modifier.height(12.dp))
        val interval = if (timer.working) state.workNoun() else "rest"
        Text(
            when {
                timer.finished -> "Plan complete. End the session when you're ready."
                undoVisible -> "Ended early. Tap Undo to put it back."
                timer.paused -> "Paused. The clock and the music are both holding."
                timer.nextLabel == SessionViewModel.DONE ->
                    "Last one. Tap anywhere to end the $interval early."
                else -> "Next: ${timer.nextLabel} → ${if (timer.working) "chill" else "hype"} " +
                    "tracks. Tap anywhere to end the $interval early."
            },
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = ActiColors.ground.copy(alpha = 0.85f),
        )
        if (undoVisible) {
            Spacer(Modifier.height(10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ActiColors.ground)
                    .clickable(role = Role.Button, onClick = onUndo)
                    .padding(horizontal = 30.dp),
            ) {
                Text("Undo", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ActiColors.snow)
            }
        }
    }
}

/** Lower-case form of the work block, for the sentence under the clock. */
private fun SessionUiState.workNoun(): String =
    if (activity == Activity.STRENGTH) "set" else "interval"

/** Strength calls a work block a SET; HIIT calls it WORK. Same clock, different word. */
private fun SessionUiState.workLabel(timer: TimerUi): String = when {
    timer.finished -> "DONE"
    !timer.working -> "REST"
    activity == Activity.STRENGTH -> "SET"
    else -> "WORK"
}

private fun Long.countdown(): String {
    val total = (this + 999) / 1000 // round up: a clock should not show 0:00 while running
    return "%d:%02d".format(total / 60, total % 60)
}

private const val UNDO_WINDOW_MS = 4_000L

@Composable
private fun ModeBlock(state: SessionUiState) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = state.mode?.name ?: "STARTING",
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
            label = "modeName",
        ) { name ->
            Text(
                name,
                fontFamily = Display,
                fontSize = 72.sp,
                lineHeight = 76.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.5).sp,
                maxLines = 1,
                color = ActiColors.ground,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            state.mode?.sentence ?: "Listening to your sensors…",
            fontSize = 16.sp,
            lineHeight = 22.sp,
            // Plan §5: content on mode colour is ground; sentences sit back at 85%.
            color = ActiColors.ground.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(16.dp))
        SignalChips(state)
        Spacer(Modifier.height(18.dp))
        BandMeter(state)
        Spacer(Modifier.height(8.dp))
        Text(
            "Energy ${state.band.label()} · ${state.override.label()}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.merge(Tabular),
            color = ActiColors.ground.copy(alpha = 0.75f),
        )
        if (state.showSignals && state.debugReason != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                state.debugReason,
                // The one monospace left: raw telemetry behind a developer toggle.
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = ActiColors.ground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * The four signal families, lit when they actually contributed to the current decision.
 * A dark chip means "this is why"; a washed-out one means the engine read it and moved on.
 */
@Composable
private fun SignalChips(state: SessionUiState) {
    val s = state.signals
    val lit = state.mode?.contributing.orEmpty()
    val accent = state.mode?.color ?: ActiColors.paused
    // Four chips at intrinsic width just overflow this screen, and a Row clips silently
    // rather than shrinking — which hid the HR chip's value entirely. Scroll as a
    // guarantee, tighter metrics so they normally fit without needing it.
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        SignalChip("Motion", s.motionRms?.let { "%.1f".format(it) }, Signal.MOTION in lit, accent)
        SignalChip("Alt", s.vertRate?.let { "%+.1f".format(it) }, Signal.ALTITUDE in lit, accent)
        SignalChip("Speed", s.speed?.let { "%.0f".format(it) }, Signal.SPEED in lit, accent)
        SignalChip(
            "HR",
            s.hrBpm?.let { bpm -> "$bpm${trendArrow(s.hrTrend)}" },
            Signal.HR in lit,
            accent,
        )
    }
}

@Composable
private fun SignalChip(label: String, value: String?, lit: Boolean, accent: Color) {
    Text(
        if (value == null) "$label —" else "$label $value",
        fontSize = 13.sp,
        style = LocalTextStyle.current.merge(Tabular),
        fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
        // As drawn: a lit chip is a dark pill with the mode colour in it.
        color = if (lit) accent else ActiColors.ground.copy(alpha = 0.75f),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .semantics { contentDescription = "$label ${value ?: "no reading"}${if (lit) ", contributing" else ""}" }
            .clip(RoundedCornerShape(50))
            .background(
                if (lit) ActiColors.ground else ActiColors.ground.copy(alpha = 0.14f),
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

private fun trendArrow(trend: Double?): String = when {
    trend == null -> ""
    trend >= 3 -> " ↑"
    trend <= -3 -> " ↓"
    else -> ""
}

/** Five segments; the ones inside the current band are filled. */
@Composable
private fun BandMeter(state: SessionUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        (1..5).forEach { segment ->
            val inBand = segment in state.band
            val alpha by animateFloatAsState(
                if (inBand) 1f else 0.28f,
                tween(200),
                label = "segment$segment",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ActiColors.ground.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun NowPlayingCard(state: SessionUiState, vm: SessionViewModel) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .padding(12.dp)
            .navigationBarsPadding()
            .clip(RoundedCornerShape(22.dp))
            .background(ActiColors.ground)
            .padding(14.dp),
    ) {
        if (state.sourceLine != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Spotify's green belongs to the Spotify mark only (plan §2); a fallback
                // warning is not Spotify.
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (state.sourceIsSpotify) ActiColors.spotify else ActiColors.hot,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.sourceLine,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ActiColors.dim,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtTile(
                tint = energyTint(state.nowPlaying?.band),
                size = 48.dp,
                art = rememberAlbumArt(state.nowPlaying?.mediaStoreId),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // Two lines: beside pause and skip, one line cut most titles after a word.
                Text(
                    state.nowPlaying?.title ?: "Picking a track…",
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = ActiColors.snow,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val artist = state.nowPlaying?.artist.orEmpty()
                    if (artist.isNotEmpty()) {
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ActiColors.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    state.nowPlaying?.band?.let { band ->
                        if (artist.isNotEmpty()) {
                            Text(" · ", style = MaterialTheme.typography.bodyMedium, color = ActiColors.dim)
                        }
                        Box(Modifier.size(8.dp).background(energyTint(band), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text("$band", style = MaterialTheme.typography.bodyMedium, color = ActiColors.dim)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            // Play/pause sits beside skip: headphones dropping or another app taking the
            // audio pauses the music, and resuming must not mean hunting for the
            // notification with gloves on. On a running clock, B4's own PAUSE does it.
            val clockRunning = state.timer?.let { !it.finished } == true
            if (!clockRunning) {
                CardButton(
                    icon = if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    description = if (state.paused) "Resume" else "Pause",
                    highlighted = state.paused,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.togglePause()
                }
                Spacer(Modifier.width(8.dp))
            }
            CardButton(icon = Icons.Rounded.SkipNext, description = "Skip track") {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.skip()
            }
        }

        Spacer(Modifier.height(14.dp))
        if (state.paused) {
            // Said where the progress bar would be moving, and saying what still happens:
            // the session keeps reading the athlete while the music holds.
            Text(
                "Paused. The session keeps listening.",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ActiColors.hot,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ActiColors.surfaceHigh),
            ) {
                val progress = state.nowPlaying?.progress ?: 0f
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(state.mode?.color ?: ActiColors.paused),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        // Once the plan is finished the clock has nothing left to pause or skip, so the
        // cooldown gets the ordinary band controls back.
        val timer = state.timer
        if (timer != null && !timer.finished) {
            // Overriding a clock means controlling the clock, not the energy band.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OverrideButton(
                    if (timer.paused) "Resume" else "Pause",
                    ActiColors.snow,
                    selected = false,
                    modifier = Modifier.weight(1f),
                ) { vm.togglePause() }
                OverrideButton(
                    if (timer.working) "Skip ${state.workNoun()}" else "Skip rest",
                    ActiColors.riding,
                    selected = false,
                    modifier = Modifier.weight(1f),
                ) { vm.endIntervalEarly() }
            }
            return@Column
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            OverrideButton("Chill", ActiColors.lift, state.override == OverrideMode.CHILL, Modifier.weight(1f), selectable = true) {
                vm.setOverride(OverrideMode.CHILL)
            }
            OverrideButton("Auto", ActiColors.snow, state.override == OverrideMode.AUTO, Modifier.weight(1f), selectable = true) {
                vm.setOverride(OverrideMode.AUTO)
            }
            OverrideButton("Hype", ActiColors.riding, state.override == OverrideMode.HYPE, Modifier.weight(1f), selectable = true) {
                vm.setOverride(OverrideMode.HYPE)
            }
        }
    }
}

/** 56 dp square beside the track: plan §4's skip size, and pause beside it. */
@Composable
private fun CardButton(
    icon: ImageVector,
    description: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) ActiColors.snow else ActiColors.surfaceHigh)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (highlighted) ActiColors.ground else ActiColors.snow,
        )
    }
}

/**
 * 64 dp minimum, thumb zone, a 0.96 press scale and a haptic tick so a gloved tap still
 * reads without looking. The label is centred in a box: centring by top padding pushed
 * it off-centre, and out of the button, as the system font scale grew.
 */
@Composable
private fun OverrideButton(
    label: String,
    accent: Color,
    selected: Boolean,
    modifier: Modifier,
    selectable: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    val haptics = LocalHapticFeedback.current
    val tap = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onClick()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .scale(scale)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent else ActiColors.surfaceHigh)
            .then(
                if (selectable) {
                    Modifier.selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = tap,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = tap,
                    )
                },
            ),
    ) {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = if (selected) ActiColors.ground else accent,
        )
    }
}

private fun IntRange.label(): String =
    if (first == last) "$first of 5" else "$first–$last"

/** "Auto", "Hype", "Chill" — the override as the buttons name it. */
private fun OverrideMode.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun Duration.clock(): String {
    val total = inWholeSeconds
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
