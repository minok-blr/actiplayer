package com.flowstate.engine

/**
 * Deterministic, rule-based activity state machine (build brief §7).
 *
 * Design invariants:
 *  - Pure Kotlin. No Android imports, no wall-clock access: time only arrives on frames.
 *  - Motion + elevation decide the mode. Heart rate only modulates WITHIN one (§2.3):
 *    HR lags activity by minutes, so letting it pick modes would queue bangers exactly
 *    when the rider sits down on the lift.
 *  - Asymmetric hysteresis: RIDING confirms in seconds, calmer states need sustained proof.
 *  - Every transition carries a human-readable reason.
 *
 * Mechanics per 1 Hz frame:
 *  1. Evaluate rules in priority order (RIDING > LIFT > PAUSED); at most one fires.
 *  2. A fired state different from the current one becomes the *candidate*; it must keep
 *     firing continuously for its confirmation window. Any different rule firing cancels
 *     the confirmation-in-progress (so a drop-in instantly kills a pending LIFT switch).
 *  3. A transition additionally requires the current state's minimum dwell to have passed.
 *  4. If nothing fires for [EngineConfig.decayToPausedMs], decay to PAUSED.
 *  5. Between transitions, HR can narrow the target band within the current mode; a fresh
 *     decision is emitted only when the modulated band actually changes.
 */
class StateEngine(private val config: EngineConfig = EngineConfig()) : ActivityEngine {

    var current: RideState = RideState.PAUSED
        private set

    private var enteredAtMs: Long = Long.MIN_VALUE
    private var candidate: RideState? = null
    private var candidateSinceMs: Long = 0L
    private var lastRuleFiredMs: Long = 0L
    private var lastDecision: StateDecision? = null

    /** Feed one frame; returns the (possibly unchanged) current decision. */
    override fun onFrame(frame: FeatureFrame): StateDecision {
        val now = frame.tMillis
        if (enteredAtMs == Long.MIN_VALUE) {
            // First frame of the session: treat dwell as already satisfied, so a rider who
            // starts the session and drops in immediately isn't locked in PAUSED.
            enteredAtMs = now - minDwellMs(current)
            lastRuleFiredMs = now
        }

        val fired = fired(frame)
        if (fired != null) lastRuleFiredMs = now

        val dwellElapsed = now - enteredAtMs >= minDwellMs(current)

        if (fired == null || fired.state == current) {
            candidate = null
            if (current != RideState.PAUSED && dwellElapsed &&
                now - lastRuleFiredMs >= config.decayToPausedMs
            ) {
                val quiet = now - lastRuleFiredMs
                return transition(RideState.PAUSED, now, frame, Reason.Decayed(quiet))
            }
            return settle(now, frame)
        }

        // A different state's rule fired.
        if (candidate != fired.state) {
            candidate = fired.state
            candidateSinceMs = now
        }
        val heldMs = now - candidateSinceMs
        if (heldMs >= confirmMs(fired.state) && dwellElapsed) {
            candidate = null
            return transition(
                fired.state,
                now,
                frame,
                Reason.Confirmed(fired.state, heldMs, fired.evidence),
            )
        }
        return settle(now, frame)
    }

    /** Reset to a clean PAUSED state at the start of a session. */
    override fun reset() {
        current = RideState.PAUSED
        enteredAtMs = Long.MIN_VALUE
        candidate = null
        lastDecision = null
    }

    // ------------------------------------------------------------------ internals

    private fun transition(
        to: RideState,
        now: Long,
        frame: FeatureFrame,
        reason: Reason,
    ): StateDecision {
        current = to
        enteredAtMs = now
        lastRuleFiredMs = now
        return StateDecision(to, energyFor(to, frame), reason, now).also { lastDecision = it }
    }

    /**
     * No state change this frame — but heart rate can still move the target band WITHIN
     * the current mode. Emits a fresh decision only when the modulated band differs from
     * the last one announced, so the queue isn't poked every second.
     */
    private fun settle(now: Long, frame: FeatureFrame): StateDecision {
        val last = lastDecision
            ?: return StateDecision(current, energyFor(current, frame), Reason.SessionStart, now)
                .also { lastDecision = it }
        val band = energyFor(current, frame)
        if (band != last.targetEnergy) {
            return StateDecision(current, band, hrReason(frame, band), now)
                .also { lastDecision = it }
        }
        return last
    }

    /** A rule that fired, and the conditions that carried it. */
    private data class Fired(val state: RideState, val evidence: Set<Evidence>)

    // Rules, checked in priority order. RIDING outranks everything (brief §7).
    private fun fired(f: FeatureFrame): Fired? {
        riding(f)?.let { return Fired(RideState.RIDING, it) }
        lift(f)?.let { return Fired(RideState.LIFT, it) }
        paused(f)?.let { return Fired(RideState.PAUSED, it) }
        return null
    }

    private fun riding(f: FeatureFrame): Set<Evidence>? {
        val m = f.motionRms ?: return null
        if (m <= config.ridingMotionRms) return null
        val descending = f.vertRate?.let { it < config.ridingVertRate } ?: false
        val fast = f.speed?.let { it > config.ridingSpeed } ?: false
        // GPS and barometer both dark (trees, tight tree runs, indoor warm-up): allow motion
        // alone, but demand a stricter bar so brisk walking never reads as riding.
        val blind = f.vertRate == null && f.speed == null && m > config.ridingMotionRmsBlind
        if (!descending && !fast && !blind) return null
        return buildSet {
            add(Evidence.HEAVY_MOTION)
            if (descending) add(Evidence.DESCENDING)
            if (fast) add(Evidence.MOVING_FAST)
            if (blind) add(Evidence.NO_FIX)
        }
    }

    private fun lift(f: FeatureFrame): Set<Evidence>? {
        val m = f.motionRms ?: return null
        if (m >= config.calmMotionRms) return null
        val v = f.vertRate ?: return null // the climb IS the lift's fingerprint; no climb signal, no LIFT
        if (v <= config.liftVertRate) return null
        val evidence = setOf(Evidence.STEADY_CLIMB, Evidence.BARELY_MOVING)
        val s = f.speed ?: return evidence // barometer doesn't care about tree cover; GPS optional
        return if (s in config.liftSpeedMin..config.liftSpeedMax) evidence else null
    }

    private fun paused(f: FeatureFrame): Set<Evidence>? {
        val m = f.motionRms ?: return null
        if (m >= config.calmMotionRms) return null
        val vertStill = f.vertRate?.let { kotlin.math.abs(it) < config.pausedVertRateMax } ?: true
        val slow = f.speed?.let { it < config.pausedSpeedMax } ?: true
        if (!vertStill || !slow) return null
        return buildSet {
            add(Evidence.STILL)
            if (f.vertRate != null) add(Evidence.LEVEL)
            if (f.speed != null) add(Evidence.SLOW)
        }
    }

    /**
     * State -> energy band, HR-modulated (brief §7). Modulation only ever narrows the
     * band within the mode's own range; it never crosses into another mode's territory.
     * Flap analysis: a band that narrows (4..5 -> 5..5) can force at most one track swap,
     * and widening back never swaps a fitting track, so no extra hysteresis is needed.
     */
    private fun energyFor(s: RideState, f: FeatureFrame): IntRange {
        val base = when (s) {
            RideState.RIDING -> config.ridingEnergy
            RideState.LIFT -> config.liftEnergy
            RideState.PAUSED -> config.pausedEnergy
            else -> error("StateEngine is the snowboard profile; got $s")
        }
        return when (s) {
            // Redlining mid-run: only the top-shelf bangers will do.
            RideState.RIDING -> {
                val pct = f.hrPctMax
                if (pct != null && pct >= config.ridingHighHrPct) base.last..base.last else base
            }
            // Winding down while standing around: ease toward the calm end of the band.
            RideState.PAUSED -> {
                val trend = f.hrTrend
                if (trend != null && trend <= config.pausedFallingHrTrend) {
                    base.first..base.first
                } else {
                    base
                }
            }
            else -> base
        }
    }

    private fun hrReason(f: FeatureFrame, band: IntRange): Reason {
        val pct = f.hrPctMax
        val trend = f.hrTrend
        val effect = when {
            pct != null && pct >= config.ridingHighHrPct -> HrEffect.REDLINING
            trend != null && trend <= config.pausedFallingHrTrend -> HrEffect.FALLING
            else -> HrEffect.ENDED
        }
        return Reason.HeartRate(effect, pct, trend, band)
    }

    private fun confirmMs(s: RideState): Long = when (s) {
        // RIDING is the up-switch and never scales; the calm states do.
        RideState.RIDING -> config.ridingConfirmMs
        RideState.LIFT -> config.down(config.liftConfirmMs)
        else -> config.down(config.pausedConfirmMs)
    }

    private fun minDwellMs(s: RideState): Long = when (s) {
        RideState.RIDING -> config.ridingMinDwellMs
        RideState.LIFT -> config.liftMinDwellMs
        else -> config.pausedMinDwellMs
    }

}
