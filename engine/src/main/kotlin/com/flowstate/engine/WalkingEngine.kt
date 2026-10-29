package com.flowstate.engine

/**
 * The walking profile: two states, STROLL and BRISK.
 *
 *  - STROLL (chill music): ambling at or below [EngineConfig.walkingStrollSpeedMax]
 *    (~4 km/h) and moving calmly — a wait at a crossing lands here too. Also the
 *    starting state.
 *  - BRISK (hype music): properly stepping out — faster than
 *    [EngineConfig.walkingBriskSpeed] (~5.4 km/h) *or* a clear cadence in the
 *    accelerometer, and, when a heart-rate monitor is present, a pulse at or above
 *    [EngineConfig.walkingBriskHrBpm].
 *
 * Two deliberate choices, both logged in DECISIONS.md:
 *
 *  - **HR co-decides the up-switch**, as in [CyclingEngine] and unlike the snowboard
 *    invariant. Walking speed alone is a weak signal — GPS noise is a large fraction of
 *    1.5 m/s — and a rising pulse is what separates striding out from drifting downhill.
 *  - **The HR bar is absolute bpm, not a fraction of max.** At walking intensity a
 *    percentage-of-max threshold would never be crossed by anyone.
 *
 * Motion alone can carry the up-switch when there is no fix, which is what makes this
 * profile usable indoors — walking a corridor with the phone in a pocket is a real test.
 *
 * Hysteresis stays asymmetric: BRISK confirms in ~3 s, the way back to STROLL needs ~20 s
 * of sustained calm. Same purity rules as [StateEngine]: no Android, no wall clock.
 */
class WalkingEngine(private val config: EngineConfig = EngineConfig()) : ActivityEngine {

    var current: RideState = RideState.STROLL
        private set

    private var enteredAtMs: Long = Long.MIN_VALUE
    private var candidate: RideState? = null
    private var candidateSinceMs: Long = 0L
    private var lastDecision: StateDecision? = null

    override fun onFrame(frame: FeatureFrame): StateDecision {
        val now = frame.tMillis
        if (enteredAtMs == Long.MIN_VALUE) {
            // First frame: dwell pre-satisfied, so someone who starts the session already
            // walking isn't locked in STROLL (mirrors the other engines).
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
        current = RideState.STROLL
        enteredAtMs = Long.MIN_VALUE
        candidate = null
        lastDecision = null
    }

    // ------------------------------------------------------------------ rules

    private data class Fired(val state: RideState, val evidence: Set<Evidence>)

    private fun fired(f: FeatureFrame): Fired? {
        brisk(f)?.let { return Fired(RideState.BRISK, it) }
        stroll(f)?.let { return Fired(RideState.STROLL, it) }
        return null
    }

    /** Stepping out: moving properly, and the pulse agrees when there is one to ask. */
    private fun brisk(f: FeatureFrame): Set<Evidence>? {
        val fast = f.speed?.let { it > config.walkingBriskSpeed } ?: false
        val striding = f.motionRms?.let { it > config.walkingBriskMotionRms } ?: false
        if (!fast && !striding) return null
        val movement = buildSet {
            if (fast) add(Evidence.BRISK_PACE)
            if (striding) add(Evidence.STRIDING)
        }
        val bpm = f.hrBpm ?: return movement // no monitor: movement evidence stands alone
        if (bpm < config.walkingBriskHrBpm) return null
        return movement + Evidence.HR_ABOVE_BAR
    }

    /** Easing off: slow and calm, or a pulse that has clearly dropped back. */
    private fun stroll(f: FeatureFrame): Set<Evidence>? {
        val slow = f.speed?.let { it <= config.walkingStrollSpeedMax } ?: true
        val calm = f.motionRms?.let { it < config.walkingCalmMotionRms } ?: true
        if (slow && calm) {
            return buildSet {
                add(Evidence.STILL)
                if (f.speed != null) add(Evidence.SLOW)
            }
        }
        // Pulse back down while not actually striding: the walk has eased even if GPS
        // still reads a middling speed.
        val bpm = f.hrBpm ?: return null
        val sprinting = f.speed?.let { it > config.walkingBriskSpeed } ?: false
        if (bpm >= config.walkingEasyHrBpm || sprinting) return null
        return setOf(Evidence.HR_EASED)
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

    /** Band within the mode, HR-narrowed exactly like the other engines. */
    private fun energyFor(s: RideState, f: FeatureFrame): IntRange = when (s) {
        RideState.BRISK -> {
            val base = config.walkingBriskEnergy
            val pct = f.hrPctMax
            if (pct != null && pct >= config.ridingHighHrPct) base.last..base.last else base
        }
        else -> {
            val base = config.walkingStrollEnergy
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
        RideState.BRISK -> config.walkingBriskConfirmMs
        else -> config.down(config.walkingStrollConfirmMs)
    }

    private fun minDwellMs(s: RideState): Long = when (s) {
        RideState.BRISK -> config.walkingBriskMinDwellMs
        else -> config.walkingStrollMinDwellMs
    }

}
