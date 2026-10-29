package com.flowstate.engine

/**
 * A work/rest clock (plan §3.3). Work is Hype, rest is Chill.
 *
 * [restMs] of zero means the plan has no built-in rest — EMOM, where the rest *is*
 * whatever is left of the minute once the reps are done. See [endIntervalEarly].
 */
data class TimerPlan(
    val workMs: Long,
    val restMs: Long,
    val rounds: Int,
) {
    val totalMs: Long get() = (workMs + restMs) * rounds

    companion object {
        val STRENGTH = TimerPlan(30_000, 90_000, 8)
        val TABATA = TimerPlan(20_000, 10_000, 8)
        val EMOM = TimerPlan(60_000, 0, 12)
        val THIRTY_THIRTY = TimerPlan(30_000, 30_000, 10)

        val presets = listOf(
            "Strength" to STRENGTH,
            "Tabata" to TABATA,
            "EMOM" to EMOM,
            "30 / 30" to THIRTY_THIRTY,
        )
    }
}

/** Where the clock currently is, for screen B4. */
data class TimerState(
    val working: Boolean,
    val round: Int,
    val totalRounds: Int,
    val remainingMs: Long,
    val intervalMs: Long,
    val finished: Boolean,
) {
    /** 0f..1f through the current interval, for B4's bar. */
    val progress: Float
        get() = if (intervalMs <= 0) 0f else (1f - remainingMs.toFloat() / intervalMs).coerceIn(0f, 1f)
}

/**
 * The Strength / HIIT profile: a clock, not a detector.
 *
 * Indoors there is no GPS and no altitude, and lifting barely moves the phone at all, so
 * no amount of signal processing would beat simply knowing the plan. That makes this the
 * only engine with nothing to tune — it cannot be subtly wrong the way a threshold can.
 *
 * Still pure: time arrives on frames like everywhere else, so pausing and skipping are
 * bookkeeping against frame time rather than calls to a clock.
 */
class TimerEngine(
    val plan: TimerPlan,
    private val config: EngineConfig = EngineConfig(),
    /** Strength works at 4–5; HIIT is flat out at 5 (plan §3.3). */
    private val workEnergy: IntRange = config.timerWorkEnergy,
) : ActivityEngine {

    private var startedAtMs: Long = Long.MIN_VALUE
    private var lastFrameMs: Long = Long.MIN_VALUE
    private var lastDecision: StateDecision? = null

    /** Time the clock did not advance because the athlete paused it. */
    private var pausedAccumMs = 0L
    private var paused = false

    /** Time skipped by ending intervals early, and the last such jump, for undo. */
    private var skippedMs = 0L
    private var lastSkipMs = 0L

    var state: TimerState = TimerState(true, 1, plan.rounds, plan.workMs, plan.workMs, false)
        private set

    val isPaused: Boolean get() = paused

    override fun onFrame(frame: FeatureFrame): StateDecision {
        val now = frame.tMillis
        if (startedAtMs == Long.MIN_VALUE) startedAtMs = now
        // Paused time is accumulated between frames rather than read from a clock, which
        // keeps the engine's "time only arrives on frames" rule intact.
        if (paused && lastFrameMs != Long.MIN_VALUE) pausedAccumMs += now - lastFrameMs
        lastFrameMs = now

        val elapsed = (now - startedAtMs - pausedAccumMs + skippedMs).coerceAtLeast(0)
        state = stateAt(elapsed)

        val target = if (state.working) RideState.WORK else RideState.REST
        val last = lastDecision
        if (last == null || last.state != target) {
            return StateDecision(
                target,
                energyFor(target),
                Reason.Confirmed(target, 0, setOf(Evidence.ON_THE_CLOCK)),
                now,
            ).also { lastDecision = it }
        }
        return last
    }

    override fun reset() {
        startedAtMs = Long.MIN_VALUE
        lastFrameMs = Long.MIN_VALUE
        lastDecision = null
        pausedAccumMs = 0
        skippedMs = 0
        lastSkipMs = 0
        paused = false
        state = TimerState(true, 1, plan.rounds, plan.workMs, plan.workMs, false)
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * Ends the current interval now — screen B4's "tap anywhere".
     *
     * Ending *work* early hands the time back as rest rather than swallowing it: for a
     * plan with a rest block that means going straight to it, and for EMOM (rest = 0) the
     * unused seconds become the rest, which is what "every minute on the minute" actually
     * means. Reversible with [undoEndEarly] so a pocket tap does not cost a set.
     */
    fun endIntervalEarly() {
        val remaining = state.remainingMs
        if (remaining <= 0 || state.finished) return
        lastSkipMs = remaining
        skippedMs += remaining
    }

    fun undoEndEarly() {
        if (lastSkipMs == 0L) return
        skippedMs -= lastSkipMs
        lastSkipMs = 0
    }

    private fun stateAt(elapsed: Long): TimerState {
        val cycle = plan.workMs + plan.restMs
        if (cycle <= 0 || elapsed >= plan.totalMs) {
            return TimerState(false, plan.rounds, plan.rounds, 0, plan.restMs, finished = true)
        }
        val round = (elapsed / cycle).toInt()
        val within = elapsed % cycle
        return if (within < plan.workMs) {
            TimerState(true, round + 1, plan.rounds, plan.workMs - within, plan.workMs, false)
        } else {
            TimerState(false, round + 1, plan.rounds, cycle - within, plan.restMs, false)
        }
    }

    private fun energyFor(s: RideState): IntRange =
        if (s == RideState.WORK) workEnergy else config.timerRestEnergy
}
