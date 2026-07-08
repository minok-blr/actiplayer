package com.flowstate.engine

/**
 * Every threshold and timing window for the snowboard profile, in one place.
 * These are tunable starting values (build brief §7), to be refined against
 * real recorded traces from the mountain. Nothing in the engine is hardcoded.
 */
data class EngineConfig(
    // --- entry thresholds ---
    /** m/s² of body movement above which the athlete is clearly working. */
    val ridingMotionRms: Double = 2.0,
    /** Stricter motion bar used when GPS and barometer are both dark. */
    val ridingMotionRmsBlind: Double = 2.6,
    /** Descending faster than this (m/s, negative = down) supports RIDING. */
    val ridingVertRate: Double = -0.3,
    /** Ground speed (m/s) above which movement supports RIDING. */
    val ridingSpeed: Double = 3.5,
    /** Below this motion RMS the body is essentially still. */
    val calmMotionRms: Double = 0.6,
    /** Sustained climb rate (m/s) — the chairlift's fingerprint. */
    val liftVertRate: Double = 0.4,
    val liftSpeedMin: Double = 1.0,
    val liftSpeedMax: Double = 7.0,
    /** |vertRate| below this counts as "not meaningfully climbing or descending". */
    val pausedVertRateMax: Double = 0.3,
    val pausedSpeedMax: Double = 1.0,

    // --- asymmetric hysteresis (brief §2.5): up-switches instant, down-switches sure ---
    val ridingConfirmMs: Long = 3_000,
    val liftConfirmMs: Long = 15_000,
    /** A quick stop to wait for a friend must never kill the song. */
    val pausedConfirmMs: Long = 30_000,
    val ridingMinDwellMs: Long = 15_000,
    val liftMinDwellMs: Long = 45_000,
    val pausedMinDwellMs: Long = 20_000,
    /** If no rule fires for this long, assume the athlete is just standing around. */
    val decayToPausedMs: Long = 90_000,

    // --- state -> energy band (1 = couch, 5 = send it) ---
    val ridingEnergy: IntRange = 4..5,
    val liftEnergy: IntRange = 1..2,
    val pausedEnergy: IntRange = 2..3,

    // --- HR modulation (brief §2.3/§7): HR tunes energy WITHIN a mode, never the mode ---
    /** At or above this fraction of max HR while RIDING, only top-energy tracks play. */
    val ridingHighHrPct: Double = 0.85,
    /** HR falling faster than this (bpm/min) while PAUSED eases the band to its calm end. */
    val pausedFallingHrTrend: Double = -3.0,

    // --- cycling profile (CRUISE / EFFORT) ---
    /** At or below this ground speed (m/s; 4.17 ≈ 15 km/h) the ride is a cruise. */
    val cyclingChillSpeedMax: Double = 4.17,
    /** Speed gained (m/s) across the effort window that signals the rider is pushing. */
    val cyclingEffortSpeedDelta: Double = 1.2,
    /** Window (ms) over which the speed gain is measured (the "past 5-7 seconds"). */
    val cyclingEffortWindowMs: Long = 6_000,
    /**
     * When HR is present, it must be climbing at least this fast (bpm/min) to confirm an
     * effort — cycling deviates from the snowboard rule here and lets HR co-decide the
     * up-switch, because on a bike rising HR accompanies effort within seconds.
     */
    val cyclingEffortHrTrend: Double = 3.0,
    val cyclingEffortConfirmMs: Long = 3_000,
    /** A coast, a red light, or a short downhill must never kill the song. */
    val cyclingCruiseConfirmMs: Long = 20_000,
    val cyclingEffortMinDwellMs: Long = 15_000,
    val cyclingCruiseMinDwellMs: Long = 10_000,
    val cyclingEffortEnergy: IntRange = 4..5,
    val cyclingChillEnergy: IntRange = 1..2,
)
