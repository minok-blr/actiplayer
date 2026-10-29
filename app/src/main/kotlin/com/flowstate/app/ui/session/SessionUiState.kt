package com.flowstate.app.ui.session

import androidx.compose.ui.graphics.Color
import com.flowstate.app.ui.ActiColors
import com.flowstate.engine.Activity
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.RideState
import com.flowstate.data.Units
import com.flowstate.engine.Signal
import com.flowstate.session.EnergyTier
import com.flowstate.session.OverrideMode
import com.flowstate.session.displayName
import com.flowstate.session.label
import com.flowstate.session.tier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

enum class Phase { IDLE, STARTING, ACTIVE }

/**
 * The mode as the athlete sees it: a word, a colour, a sentence, and which signals
 * earned it. [contributing] is what lights the chips on B2/B3.
 */
data class ModeUi(
    val name: String,
    val color: Color,
    val sentence: String,
    val contributing: Set<Signal>,
)

/** The last frame, in the units the chips display. */
data class SignalsUi(
    val motionRms: Double? = null,
    val vertRate: Double? = null,
    /** In the user's units (km/h or mph); see [speedUnit]. */
    val speed: Double? = null,
    val speedUnit: String = "km/h",
    val hrBpm: Int? = null,
    val hrPct: Double? = null,
    val hrTrend: Double? = null,
) {
    companion object {
        fun from(frame: FeatureFrame?, units: Units) = SignalsUi(
            motionRms = frame?.motionRms,
            vertRate = frame?.vertRate,
            speed = frame?.speed?.let(units::speed),
            speedUnit = units.speedUnit,
            hrBpm = frame?.hrBpm,
            hrPct = frame?.hrPctMax,
            hrTrend = frame?.hrTrend,
        )
    }
}

/** The clock, for screen B4. Null for every profile that reads sensors instead. */
data class TimerUi(
    val working: Boolean,
    val round: Int,
    val totalRounds: Int,
    val remainingMs: Long,
    val progress: Float,
    val paused: Boolean,
    val finished: Boolean,
    /** "1:30 rest" — what comes after this interval, and for how long. */
    val nextLabel: String,
    /** The plan in the header's shorthand: "30/90", "EMOM". */
    val planLine: String,
)

data class NowPlayingUi(
    val title: String,
    val artist: String,
    val band: Int?,
    val progress: Float?,
    val mediaStoreId: Long? = null,
)

/**
 * Everything the session screens draw, in one object (plan §3.6). The screens never
 * reach past this into the coordinator, the engine or the player — which is what makes
 * B2/B3 the same screen whether the music is coming from local files or Spotify.
 */
data class SessionUiState(
    val phase: Phase = Phase.IDLE,
    val activity: Activity = Activity.SNOWBOARDING,
    val mode: ModeUi? = null,
    val band: IntRange = 2..3,
    val override: OverrideMode = OverrideMode.AUTO,
    val nowPlaying: NowPlayingUi? = null,
    val elapsed: Duration = ZERO,
    val signals: SignalsUi = SignalsUi(),
    /** A line above the now-playing card: Spotify, or a pool that fell back. */
    val sourceLine: String? = null,
    /** Only the Spotify line earns Spotify's green (plan §2: the mark, nothing else). */
    val sourceIsSpotify: Boolean = false,
    val showSignals: Boolean = false,
    val debugReason: String? = null,
    val hasBarometer: Boolean = true,
    val timer: TimerUi? = null,
    /** The listener paused — in the app, from the notification, or a headset. */
    val paused: Boolean = false,
)

/** Mode colour is the primary state channel, so it is derived in exactly one place. */
fun EnergyTier?.color(): Color = when (this) {
    EnergyTier.HYPE -> ActiColors.riding
    EnergyTier.MID, null -> ActiColors.paused
    EnergyTier.CHILL -> ActiColors.lift
}

/**
 * The colour of a mode *for this activity*: a running cruise is mid energy and reads in
 * slate, where a cycling cruise is the chill state and reads in ice. See [tier].
 */
fun RideState?.modeColor(activity: Activity): Color = this?.tier(activity).color()

/** What the mode is called on screen; "STARTING" before the first decision. */
fun RideState?.modeName(activity: Activity): String = this?.displayName(activity) ?: "STARTING"

/** The same name in sentence case, for everything smaller than the session headline. */
fun RideState?.modeLabel(activity: Activity): String = this?.label(activity) ?: "Starting"
