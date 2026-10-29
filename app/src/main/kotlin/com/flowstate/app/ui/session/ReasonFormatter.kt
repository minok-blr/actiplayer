package com.flowstate.app.ui.session

import com.flowstate.engine.Activity
import com.flowstate.engine.Evidence
import com.flowstate.engine.HrEffect
import com.flowstate.engine.Reason
import com.flowstate.engine.RideState

/**
 * Turns a [Reason] into the sentence shown under the mode name (plan §3.7).
 *
 * This is the trust mechanism: the athlete has handed over control of their music, and
 * the one thing they get back is a plain statement of why. So the sentences say what the
 * engine actually concluded — never a threshold, never a variable name. The raw telemetry
 * is still one toggle away via [Reason.toDebugString].
 */
object ReasonFormatter {

    /**
     * @param activity the profile that decided — states are shared between profiles, and
     *   a running CRUISE is not a cycling one ("rolling easy" is no way to describe a jog).
     */
    fun sentence(reason: Reason?, activity: Activity = Activity.SNOWBOARDING): String =
        when (reason) {
            null, Reason.SessionStart -> "Listening to your sensors…"
            is Reason.Decayed -> "Nothing moving for ${duration(reason.quietMs)}, easing to mid."
            is Reason.HeartRate -> heartRate(reason)
            is Reason.Confirmed -> confirmed(reason, activity)
        }

    private fun confirmed(reason: Reason.Confirmed, activity: Activity): String {
        val held = duration(reason.heldMs)
        val e = reason.evidence
        return when (reason.state) {
            RideState.RIDING -> when {
                Evidence.DESCENDING in e -> "Descending fast with heavy motion for $held."
                Evidence.MOVING_FAST in e -> "Moving fast with heavy motion for $held."
                Evidence.NO_FIX in e -> "Heavy motion for $held, with no GPS or barometer to check it against."
                else -> "Heavy motion for $held."
            }
            RideState.LIFT -> "Steady climb, barely moving, for $held."
            RideState.PAUSED -> "Still and level for $held."
            RideState.EFFORT -> when {
                Evidence.HR_RISING in e -> "Speed and pulse climbing together for $held."
                else -> "Picking up speed for $held."
            }
            RideState.CRUISE ->
                if (activity == Activity.RUNNING) "Running at an easy pace for $held." else "Rolling easy for $held."
            RideState.BRISK -> when {
                Evidence.HR_ABOVE_BAR in e -> "Striding out with your pulse up, for $held."
                Evidence.BRISK_PACE in e -> "Walking briskly for $held."
                else -> "A steady stride for $held."
            }
            RideState.PUSH -> when {
                Evidence.HR_ABOVE_BAR in e -> "Running hard with your pulse up, for $held."
                Evidence.STRIDING in e -> "A fast cadence for $held."
                else -> "Running hard for $held."
            }
            RideState.WALK -> "Down to a walk for $held."
            RideState.ACTIVE -> "Working hard for $held."
            RideState.EASY -> "Ticking over for $held."
            // The clock decided these, not a signal, so the sentence says so plainly.
            RideState.WORK -> "The clock says go."
            // REST is shared: the timer's rest block, and the generic profile going still.
            RideState.REST ->
                if (Evidence.ON_THE_CLOCK in e) "Rest block, easing off." else "Still for $held."
            RideState.STROLL -> when {
                Evidence.HR_EASED in e -> "Your pulse has settled for $held."
                else -> "Ambling for $held."
            }
        }
    }

    private fun heartRate(reason: Reason.HeartRate): String = when (reason.effect) {
        HrEffect.REDLINING -> {
            val pct = reason.pctMax?.let { (it * 100).toInt() }
            if (pct != null) {
                "Pulse at $pct% of max, so only your top tracks."
            } else {
                "Pulse near your max, so only your top tracks."
            }
        }
        HrEffect.FALLING -> "Pulse coming down, easing to the calm end."
        HrEffect.ENDED -> "Pulse back to normal — the full range is open again."
    }

    /** "3 s" up to two minutes, then whole minutes; the plan's examples read in seconds. */
    private fun duration(ms: Long): String {
        val seconds = ms / 1000
        if (seconds < 120) return "$seconds s"
        return "${seconds / 60} min"
    }
}
