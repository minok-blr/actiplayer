package com.flowstate.engine

/**
 * The four signal families the athlete can see on screen. A decision names the ones that
 * actually carried it, which is what lights the chips on the session screen — an adaptive
 * player that can't show its working is one nobody trusts.
 */
enum class Signal { MOTION, ALTITUDE, SPEED, HR }

/**
 * One condition that helped a rule fire, in human terms rather than raw numbers.
 *
 * These exist so the sentence on screen can be written from what the engine actually
 * concluded ("descending fast with heavy motion") instead of being reverse-engineered
 * from a debug string. Each engine emits only the ones its own rules can produce.
 */
enum class Evidence(
    /** The chip this lights on screen; null for evidence that came from no sensor. */
    val signal: Signal?,
) {
    // snowboard
    HEAVY_MOTION(Signal.MOTION),
    DESCENDING(Signal.ALTITUDE),
    MOVING_FAST(Signal.SPEED),
    NO_FIX(Signal.MOTION),
    STEADY_CLIMB(Signal.ALTITUDE),
    BARELY_MOVING(Signal.MOTION),
    STILL(Signal.MOTION),
    LEVEL(Signal.ALTITUDE),
    SLOW(Signal.SPEED),

    // cycling
    SPEED_GAIN(Signal.SPEED),
    HR_RISING(Signal.HR),
    EASY_PACE(Signal.SPEED),

    // walking / running
    BRISK_PACE(Signal.SPEED),
    STRIDING(Signal.MOTION),
    HR_ABOVE_BAR(Signal.HR),
    HR_EASED(Signal.HR),

    /**
     * Timer profiles: the plan said so. No signal contributed, and none should — it used
     * to claim MOTION, which would light a chip for a decision no sensor made.
     */
    ON_THE_CLOCK(null),
}

/** What heart rate did to the band inside the current mode. */
enum class HrEffect { REDLINING, FALLING, ENDED }

/**
 * Why the engine decided what it decided.
 *
 * Replaces the old free-text `reason` string (plan §3.7): the UI needs the structure to
 * write a sentence and light the right chips, and [toDebugString] keeps the original
 * telemetry available behind the "show live signals" toggle.
 */
sealed interface Reason {

    /** Signals that actually contributed to this decision. */
    val contributing: Set<Signal>

    fun toDebugString(): String

    /** The opening decision of a session, before any rule has had time to fire. */
    data object SessionStart : Reason {
        override val contributing = emptySet<Signal>()
        override fun toDebugString() = "session start"
    }

    /** A rule fired continuously for [heldMs] and the state changed. */
    data class Confirmed(
        val state: RideState,
        val heldMs: Long,
        val evidence: Set<Evidence>,
    ) : Reason {
        override val contributing: Set<Signal> = evidence.mapNotNullTo(mutableSetOf()) { it.signal }
        override fun toDebugString(): String =
            "$state after ${heldMs / 1000}s: ${evidence.joinToString(",") { it.name }}"
    }

    /** Nothing fired for long enough that standing around is the only explanation. */
    data class Decayed(val quietMs: Long) : Reason {
        override val contributing = emptySet<Signal>()
        override fun toDebugString() = "decay: no signals for ${quietMs / 1000}s"
    }

    /** No state change — heart rate moved the band within the mode. */
    data class HeartRate(
        val effect: HrEffect,
        val pctMax: Double?,
        val trend: Double?,
        val band: IntRange,
    ) : Reason {
        override val contributing = setOf(Signal.HR)
        override fun toDebugString(): String {
            val detail = when (effect) {
                HrEffect.REDLINING -> "HR at ${pctMax?.let { (it * 100).toInt() }}% of max"
                HrEffect.FALLING -> "HR falling"
                HrEffect.ENDED -> "HR modulation ended"
            }
            return "$detail -> energy ${band.first}-${band.last}"
        }
    }
}
