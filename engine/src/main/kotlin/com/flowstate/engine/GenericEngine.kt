package com.flowstate.engine

/**
 * The catch-all profile: REST, EASY and ACTIVE, decided on body motion alone (plan §3.3).
 *
 * Deliberately the least clever engine in the set. It makes no assumption about what the
 * athlete is doing, so it can only read how hard they are moving — which is exactly what
 * makes it the right answer for everything the named profiles do not cover. Heart rate
 * modulates within a state as everywhere else, and never picks one.
 */
class GenericEngine(private val config: EngineConfig = EngineConfig()) : ActivityEngine {

    var current: RideState = RideState.EASY
        private set

    private var enteredAtMs: Long = Long.MIN_VALUE
    private var candidate: RideState? = null
    private var candidateSinceMs: Long = 0L
    private var lastDecision: StateDecision? = null

    override fun onFrame(frame: FeatureFrame): StateDecision {
        val now = frame.tMillis
        if (enteredAtMs == Long.MIN_VALUE) enteredAtMs = now - minDwellMs(current)

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
        current = RideState.EASY
        enteredAtMs = Long.MIN_VALUE
        candidate = null
        lastDecision = null
    }

    private data class Fired(val state: RideState, val evidence: Set<Evidence>)

    private fun fired(f: FeatureFrame): Fired? {
        val motion = f.motionRms ?: return null
        return when {
            motion > config.genericActiveMotion ->
                Fired(RideState.ACTIVE, setOf(Evidence.HEAVY_MOTION))
            motion < config.genericRestMotion ->
                Fired(RideState.REST, setOf(Evidence.STILL))
            else -> Fired(RideState.EASY, setOf(Evidence.BARELY_MOVING))
        }
    }

    private fun transition(
        to: RideState,
        now: Long,
        frame: FeatureFrame,
        reason: Reason,
    ): StateDecision {
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

    private fun energyFor(s: RideState, f: FeatureFrame): IntRange = when (s) {
        RideState.ACTIVE -> {
            val base = config.genericActiveEnergy
            val pct = f.hrPctMax
            if (pct != null && pct >= config.ridingHighHrPct) base.last..base.last else base
        }
        RideState.EASY -> config.genericEasyEnergy
        else -> {
            val base = config.genericRestEnergy
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

    /** Direction-aware, as in [RunningEngine]: EASY is an up-switch when coming from REST. */
    private fun confirmMs(to: RideState): Long = when {
        to == RideState.ACTIVE -> config.genericActiveConfirmMs
        to == RideState.EASY && current == RideState.REST -> config.genericEasyUpConfirmMs
        to == RideState.EASY -> config.down(config.genericEasyConfirmMs)
        else -> config.down(config.genericRestConfirmMs)
    }

    private fun minDwellMs(s: RideState): Long = when (s) {
        RideState.ACTIVE -> config.genericActiveMinDwellMs
        RideState.EASY -> config.genericEasyMinDwellMs
        else -> config.genericRestMinDwellMs
    }
}
