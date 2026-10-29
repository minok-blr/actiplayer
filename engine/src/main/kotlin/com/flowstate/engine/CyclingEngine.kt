package com.flowstate.engine

/**
 * The cycling profile: two states, CRUISE and EFFORT.
 *
 *  - CRUISE (chill music): rolling at or below [EngineConfig.cyclingChillSpeedMax]
 *    (~15 km/h) — includes being stopped at a light. Also the starting state.
 *  - EFFORT (hype music): the rider starts pushing — speed has gained at least
 *    [EngineConfig.cyclingEffortSpeedDelta] m/s across the effort window (~6 s), and,
 *    when a heart-rate monitor is present, HR starts climbing within
 *    [EngineConfig.cyclingEffortHrGraceMs] of that gain.
 *
 * Deliberate deviation from the snowboard invariant (logged in DECISIONS.md): here HR
 * helps *decide* the up-switch instead of only modulating within a mode, because
 * requiring both kills false positives from a downhill speed gain the rider isn't working
 * for. The two signals do not arrive together, though: a speed gain is over in seconds,
 * while the pulse lags the effort and the 60 s trend lags the pulse — after a stop at a
 * light the trend is still negative when the rider is already back up to speed. So a
 * gain opens a window, and the pulse has until the window closes to agree.
 *
 * Hysteresis stays asymmetric: EFFORT confirms in ~3 s, the way back to CRUISE needs
 * ~20 s of sustained low speed, so a coast or a red light never kills the song. Between
 * transitions HR narrows the band within the mode exactly like the snowboard engine
 * (redline -> only 5s; falling HR while cruising -> only 1s).
 *
 * Same purity rules as [StateEngine]: no Android, no wall clock, 1 Hz frames in.
 */
class CyclingEngine(private val config: EngineConfig = EngineConfig()) : ActivityEngine {

    var current: RideState = RideState.CRUISE
        private set

    private var enteredAtMs: Long = Long.MIN_VALUE
    private var candidate: RideState? = null
    private var candidateSinceMs: Long = 0L
    private var lastDecision: StateDecision? = null

    /** (tMillis, speed) samples covering at least the effort window. */
    private val speedHistory = ArrayDeque<Pair<Long, Double>>()

    /** When the speed-gain condition last held; opens the window for HR to agree. */
    private var lastGainMs: Long = Long.MIN_VALUE

    override fun onFrame(frame: FeatureFrame): StateDecision {
        val now = frame.tMillis
        if (enteredAtMs == Long.MIN_VALUE) {
            // First frame: dwell pre-satisfied, so a rider who starts the session
            // mid-climb isn't locked in CRUISE (mirrors the snowboard engine).
            enteredAtMs = now - minDwellMs(current)
        }
        frame.speed?.let { s ->
            speedHistory.addLast(now to s)
            val horizon = config.cyclingEffortWindowMs + 2_000
            while (speedHistory.isNotEmpty() && now - speedHistory.first().first > horizon) {
                speedHistory.removeFirst()
            }
        }

        val fired = fired(frame)
        val dwellElapsed = now - enteredAtMs >= minDwellMs(current)

        if (fired == null || fired.state == current) {
            candidate = null
            return settle(now, frame)
        }
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

    override fun reset() {
        current = RideState.CRUISE
        enteredAtMs = Long.MIN_VALUE
        candidate = null
        lastDecision = null
        speedHistory.clear()
        lastGainMs = Long.MIN_VALUE
    }

    // ------------------------------------------------------------------ rules

    private data class Fired(val state: RideState, val evidence: Set<Evidence>)

    private fun fired(f: FeatureFrame): Fired? {
        effort(f)?.let { return Fired(RideState.EFFORT, it) }
        cruise(f)?.let { return Fired(RideState.CRUISE, it) }
        return null
    }

    /**
     * Pushing: faster than cruise speed, meaningfully faster than ~6 s ago, and — when HR
     * is available — the pulse climbing within the grace window after that gain.
     */
    private fun effort(f: FeatureFrame): Set<Evidence>? {
        val speed = f.speed ?: return null
        if (speed <= config.cyclingChillSpeedMax) return null
        val gaining = speedDelta(f.tMillis)?.let { it >= config.cyclingEffortSpeedDelta } ?: false
        if (gaining) lastGainMs = f.tMillis
        val trend = f.hrTrend
        if (trend == null) {
            // No monitor: the speed evidence stands alone, and only while it holds.
            return if (gaining) setOf(Evidence.SPEED_GAIN) else null
        }
        // With a monitor, the pulse has to agree — but it is allowed to arrive late.
        val recentGain = lastGainMs != Long.MIN_VALUE &&
            f.tMillis - lastGainMs <= config.cyclingEffortHrGraceMs
        if (!recentGain || trend < config.cyclingEffortHrTrend) return null
        return setOf(Evidence.SPEED_GAIN, Evidence.HR_RISING)
    }

    /** Back to cruising: at or below chill speed (a stop at a light also lands here). */
    private fun cruise(f: FeatureFrame): Set<Evidence>? {
        val speed = f.speed ?: return null
        return if (speed <= config.cyclingChillSpeedMax) setOf(Evidence.EASY_PACE) else null
    }

    /** Speed gained since ~[EngineConfig.cyclingEffortWindowMs] ago; null while history is short. */
    private fun speedDelta(now: Long): Double? {
        val target = now - config.cyclingEffortWindowMs
        // Oldest sample must roughly reach back to the window start to make a fair claim.
        val oldest = speedHistory.firstOrNull() ?: return null
        if (oldest.first > target + 1_500) return null
        val baseline = speedHistory.last { it.first <= target + 1_500 }
        return speedHistory.last().second - baseline.second
    }

    // ------------------------------------------------------------------ mechanics

    private fun transition(to: RideState, now: Long, frame: FeatureFrame, reason: Reason): StateDecision {
        current = to
        enteredAtMs = now
        return StateDecision(to, energyFor(to, frame), reason, now).also { lastDecision = it }
    }

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

    /** Band within the mode, HR-narrowed exactly like the snowboard engine. */
    private fun energyFor(s: RideState, f: FeatureFrame): IntRange = when (s) {
        RideState.EFFORT -> {
            val base = config.cyclingEffortEnergy
            val pct = f.hrPctMax
            if (pct != null && pct >= config.ridingHighHrPct) base.last..base.last else base
        }
        else -> {
            val base = config.cyclingChillEnergy
            val trend = f.hrTrend
            if (trend != null && trend <= config.pausedFallingHrTrend) base.first..base.first else base
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
        RideState.EFFORT -> config.cyclingEffortConfirmMs
        else -> config.down(config.cyclingCruiseConfirmMs)
    }

    private fun minDwellMs(s: RideState): Long = when (s) {
        RideState.EFFORT -> config.cyclingEffortMinDwellMs
        else -> config.cyclingCruiseMinDwellMs
    }

}
