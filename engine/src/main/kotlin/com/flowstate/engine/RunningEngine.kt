package com.flowstate.engine

/**
 * The running profile: three states, WALK, CRUISE and PUSH (plan §3.3).
 *
 *  - PUSH (hype): over ~12 km/h, or a cadence at or above
 *    [EngineConfig.runningPushCadence], or a merely brisk pace with the pulse already at
 *    [EngineConfig.runningPushHrPct] of max.
 *  - CRUISE (mid): running, but not hard. The default once moving.
 *  - WALK (chill): dropped to walking pace or walking cadence — the recovery block
 *    between efforts, and where a session usually starts.
 *
 * Cadence matters more here than anywhere else: a runner's gait is unmistakable in the
 * accelerometer, and it keeps working under trees, in a tunnel, or on a treadmill where
 * GPS reports nothing at all. Where speed and cadence disagree, cadence wins — it is
 * measuring the athlete rather than the fix.
 *
 * Same purity rules as [StateEngine]: no Android, no wall clock, 1 Hz frames in.
 */
class RunningEngine(private val config: EngineConfig = EngineConfig()) : ActivityEngine {

    var current: RideState = RideState.WALK
        private set

    private var enteredAtMs: Long = Long.MIN_VALUE
    private var candidate: RideState? = null
    private var candidateSinceMs: Long = 0L
    private var lastDecision: StateDecision? = null

    override fun onFrame(frame: FeatureFrame): StateDecision {
        val now = frame.tMillis
        if (enteredAtMs == Long.MIN_VALUE) {
            // Dwell pre-satisfied on the first frame, so starting a session mid-run does
            // not lock the runner into WALK (mirrors the other engines).
            enteredAtMs = now - minDwellMs(current)
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
        current = RideState.WALK
        enteredAtMs = Long.MIN_VALUE
        candidate = null
        lastDecision = null
    }

    // ------------------------------------------------------------------ rules

    private data class Fired(val state: RideState, val evidence: Set<Evidence>)

    private fun fired(f: FeatureFrame): Fired? {
        push(f)?.let { return Fired(RideState.PUSH, it) }
        walk(f)?.let { return Fired(RideState.WALK, it) }
        cruise(f)?.let { return Fired(RideState.CRUISE, it) }
        return null
    }

    /** Hard effort: fast, or a quick gait, or a merely-running pace with the pulse up. */
    private fun push(f: FeatureFrame): Set<Evidence>? {
        val fast = f.speed?.let { it > config.runningPushSpeed } ?: false
        val quickGait = f.cadence?.let { it >= config.runningPushCadence } ?: false
        val running = f.speed?.let { it > config.runningWalkSpeedMax } ?: false
        val redlining = f.hrPctMax?.let { it >= config.runningPushHrPct } ?: false
        if (!fast && !quickGait && !(running && redlining)) return null
        return buildSet {
            if (fast) add(Evidence.MOVING_FAST)
            if (quickGait) add(Evidence.STRIDING)
            if (redlining) add(Evidence.HR_ABOVE_BAR)
        }
    }

    /** Back to walking. Cadence overrules a noisy fix: a walking gait is a walk. */
    private fun walk(f: FeatureFrame): Set<Evidence>? {
        val walkingGait = f.cadence?.let { it < config.runningWalkCadence }
        if (walkingGait == true) return setOf(Evidence.STRIDING)
        if (walkingGait == false) return null // clearly running, whatever GPS says
        val slow = f.speed?.let { it <= config.runningWalkSpeedMax } ?: return null
        return if (slow) setOf(Evidence.SLOW) else null
    }

    /** Running, but not hard — needs some evidence of movement to fire at all. */
    private fun cruise(f: FeatureFrame): Set<Evidence>? {
        val running = f.speed?.let { it > config.runningWalkSpeedMax } ?: false
        val runningGait = f.cadence?.let { it >= config.runningWalkCadence } ?: false
        if (!running && !runningGait) return null
        return buildSet {
            if (running) add(Evidence.EASY_PACE)
            if (runningGait) add(Evidence.STRIDING)
        }
    }

    // ------------------------------------------------------------------ mechanics

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
        RideState.PUSH -> {
            val base = config.runningPushEnergy
            val pct = f.hrPctMax
            if (pct != null && pct >= config.ridingHighHrPct) base.last..base.last else base
        }
        RideState.CRUISE -> config.runningCruiseEnergy
        else -> {
            val base = config.runningWalkEnergy
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

    /**
     * Up-switches are fast and never scale with sensitivity; down-switches are sure. CRUISE
     * is both, depending on which side it is reached from, so the window follows the
     * direction of travel rather than the target alone.
     */
    private fun confirmMs(to: RideState): Long = when {
        to == RideState.PUSH -> config.runningPushConfirmMs
        to == RideState.CRUISE && current == RideState.WALK -> config.runningCruiseUpConfirmMs
        to == RideState.CRUISE -> config.down(config.runningCruiseConfirmMs)
        else -> config.down(config.runningWalkConfirmMs)
    }

    private fun minDwellMs(s: RideState): Long = when (s) {
        RideState.PUSH -> config.runningPushMinDwellMs
        RideState.CRUISE -> config.runningCruiseMinDwellMs
        else -> config.runningWalkMinDwellMs
    }
}
