package com.flowstate.engine

/**
 * The cycling profile: two states, CRUISE and EFFORT.
 *
 *  - CRUISE (chill music): rolling at or below [EngineConfig.cyclingChillSpeedMax]
 *    (~15 km/h) — includes being stopped at a light. Also the starting state.
 *  - EFFORT (hype music): the rider starts pushing — speed has gained at least
 *    [EngineConfig.cyclingEffortSpeedDelta] m/s across the effort window (~6 s), and,
 *    when a heart-rate monitor is present, HR is climbing too.
 *
 * Deliberate deviation from the snowboard invariant (logged in DECISIONS.md): here HR
 * helps *decide* the up-switch instead of only modulating within a mode. On a bike,
 * effort and rising HR are near-simultaneous, and requiring both kills false positives
 * from a downhill speed gain the rider isn't working for.
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

        val fired = firedState(frame)
        val dwellElapsed = now - enteredAtMs >= minDwellMs(current)

        if (fired == null || fired == current) {
            candidate = null
            return settle(now, frame)
        }
        if (candidate != fired) {
            candidate = fired
            candidateSinceMs = now
        }
        val heldMs = now - candidateSinceMs
        if (heldMs >= confirmMs(fired) && dwellElapsed) {
            candidate = null
            return transition(fired, now, frame, describe(fired, frame, heldMs))
        }
        return settle(now, frame)
    }

    override fun reset() {
        current = RideState.CRUISE
        enteredAtMs = Long.MIN_VALUE
        candidate = null
        lastDecision = null
        speedHistory.clear()
    }

    // ------------------------------------------------------------------ rules

    private fun firedState(f: FeatureFrame): RideState? = when {
        effortRule(f) -> RideState.EFFORT
        cruiseRule(f) -> RideState.CRUISE
        else -> null
    }

    /**
     * Pushing: faster than cruise speed, meaningfully faster than ~6 s ago, and — when HR
     * is available — the pulse is climbing with it.
     */
    private fun effortRule(f: FeatureFrame): Boolean {
        val speed = f.speed ?: return false
        if (speed <= config.cyclingChillSpeedMax) return false
        val delta = speedDelta(f.tMillis) ?: return false
        if (delta < config.cyclingEffortSpeedDelta) return false
        val trend = f.hrTrend ?: return true // no monitor: speed evidence stands alone
        return trend >= config.cyclingEffortHrTrend
    }

    /** Back to cruising: at or below chill speed (a stop at a light also lands here). */
    private fun cruiseRule(f: FeatureFrame): Boolean {
        val speed = f.speed ?: return false
        return speed <= config.cyclingChillSpeedMax
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

    private fun transition(to: RideState, now: Long, frame: FeatureFrame, reason: String): StateDecision {
        current = to
        enteredAtMs = now
        return StateDecision(to, energyFor(to, frame), reason, now).also { lastDecision = it }
    }

    private fun settle(now: Long, frame: FeatureFrame): StateDecision {
        val last = lastDecision
            ?: return StateDecision(current, energyFor(current, frame), "session start", now)
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

    private fun hrReason(f: FeatureFrame, band: IntRange): String {
        val pct = f.hrPctMax
        val trend = f.hrTrend
        val cause = when {
            pct != null && pct >= config.ridingHighHrPct -> "HR at ${(pct * 100).toInt()}% of max"
            trend != null && trend <= config.pausedFallingHrTrend -> "HR falling"
            else -> "HR modulation ended"
        }
        return "$current: $cause -> energy ${band.first}-${band.last}"
    }

    private fun confirmMs(s: RideState): Long = when (s) {
        RideState.EFFORT -> config.cyclingEffortConfirmMs
        else -> config.cyclingCruiseConfirmMs
    }

    private fun minDwellMs(s: RideState): Long = when (s) {
        RideState.EFFORT -> config.cyclingEffortMinDwellMs
        else -> config.cyclingCruiseMinDwellMs
    }

    private fun describe(s: RideState, f: FeatureFrame, heldMs: Long): String {
        fun fmt(x: Double?) = x?.let { "%.1f".format(it) } ?: "-"
        val kmh = f.speed?.let { "%.0f".format(it * 3.6) } ?: "-"
        return "$s after ${heldMs / 1000}s: speed=${kmh}km/h hrTrend=${fmt(f.hrTrend)}"
    }
}
