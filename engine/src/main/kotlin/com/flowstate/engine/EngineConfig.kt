package com.flowstate.engine

/**
 * Every threshold and timing window for every profile, in one place.
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
    /**
     * How long after a speed gain the pulse may take to agree. HR lags effort by 10–30 s
     * and the 60 s trend lags HR further, so demanding both in the same second meant an
     * effort off a red light — HR still falling from the stop — could never confirm.
     */
    val cyclingEffortHrGraceMs: Long = 45_000,
    val cyclingEffortConfirmMs: Long = 3_000,
    /** A coast, a red light, or a short downhill must never kill the song. */
    val cyclingCruiseConfirmMs: Long = 20_000,
    val cyclingEffortMinDwellMs: Long = 15_000,
    val cyclingCruiseMinDwellMs: Long = 10_000,
    val cyclingEffortEnergy: IntRange = 4..5,
    val cyclingChillEnergy: IntRange = 1..2,

    // --- walking profile (STROLL / BRISK) ---
    /** Above this ground speed (m/s; 1.5 ≈ 5.4 km/h) the walk is a brisk one. */
    val walkingBriskSpeed: Double = 1.5,
    /** At or below this (m/s; 1.1 ≈ 4 km/h) it is a stroll — or a wait at a crossing. */
    val walkingStrollSpeedMax: Double = 1.1,
    /**
     * Walking speeds sit close to the GPS noise floor, and a phone in a pocket reads
     * cadence clearly, so motion can carry the up-switch on its own — which also makes
     * the profile testable indoors with no fix at all.
     */
    val walkingBriskMotionRms: Double = 1.2,
    val walkingCalmMotionRms: Double = 0.7,
    /**
     * Absolute bpm, not a fraction of max: at walking intensity a percentage-of-max bar
     * would never be crossed. Like cycling, HR here helps *decide* the up-switch rather
     * than only modulating within it (logged in DECISIONS.md).
     */
    val walkingBriskHrBpm: Int = 78,
    /** Below this the walk has clearly eased off. The gap to brisk is the hysteresis. */
    val walkingEasyHrBpm: Int = 70,
    val walkingBriskConfirmMs: Long = 3_000,
    /** A road crossing or a pause to look at the map must not kill the song. */
    val walkingStrollConfirmMs: Long = 20_000,
    val walkingBriskMinDwellMs: Long = 15_000,
    val walkingStrollMinDwellMs: Long = 10_000,
    val walkingBriskEnergy: IntRange = 4..5,
    val walkingStrollEnergy: IntRange = 1..2,

    // --- running profile (WALK / CRUISE / PUSH) ---
    /** Above this ground speed (m/s; 3.3 ≈ 12 km/h) the runner is pushing. */
    val runningPushSpeed: Double = 3.3,
    /** At or below this (m/s; 2.0 ≈ 7.2 km/h) they have dropped to a walk. */
    val runningWalkSpeedMax: Double = 2.0,
    /** Steps per minute that mark a hard effort; carries the up-switch with no GPS fix. */
    val runningPushCadence: Double = 172.0,
    /** Below this cadence the gait is a walk, whatever a noisy fix claims. */
    val runningWalkCadence: Double = 140.0,
    /** At or above this fraction of max HR, a borderline pace counts as pushing. */
    val runningPushHrPct: Double = 0.82,
    val runningPushConfirmMs: Long = 3_000,
    /**
     * CRUISE sits in the middle, so it is reached both ways: up from a walk, which is an
     * up-switch and gets the fast window like any other, and down from a sprint, which
     * gets [runningCruiseConfirmMs]. Deciding by target state alone made a runner wait
     * 15 s for the music to lift after breaking into a jog.
     */
    val runningCruiseUpConfirmMs: Long = 3_000,
    val runningCruiseConfirmMs: Long = 15_000,
    val runningWalkConfirmMs: Long = 20_000,
    val runningPushMinDwellMs: Long = 15_000,
    val runningCruiseMinDwellMs: Long = 10_000,
    val runningWalkMinDwellMs: Long = 10_000,
    val runningPushEnergy: IntRange = 4..5,
    val runningCruiseEnergy: IntRange = 3..3,
    val runningWalkEnergy: IntRange = 1..2,

    // --- generic profile (REST / EASY / ACTIVE) ---
    /** Motion RMS above which the athlete is clearly working, whatever they are doing. */
    val genericActiveMotion: Double = 2.0,
    /** Below this they are essentially still. */
    val genericRestMotion: Double = 0.8,
    val genericActiveConfirmMs: Long = 3_000,
    /** REST -> EASY is an up-switch; ACTIVE -> EASY is not (see [runningCruiseUpConfirmMs]). */
    val genericEasyUpConfirmMs: Long = 3_000,
    val genericEasyConfirmMs: Long = 15_000,
    val genericRestConfirmMs: Long = 25_000,
    val genericActiveMinDwellMs: Long = 15_000,
    val genericEasyMinDwellMs: Long = 10_000,
    val genericRestMinDwellMs: Long = 10_000,
    val genericActiveEnergy: IntRange = 4..5,
    val genericEasyEnergy: IntRange = 3..3,
    val genericRestEnergy: IntRange = 1..2,

    // --- timer profile (WORK / REST) ---
    val timerWorkEnergy: IntRange = 4..5,
    /** Plan §3.3: HIIT's work block is flat out, so only the very top of the pool. */
    val hiitWorkEnergy: IntRange = 5..5,
    val timerRestEnergy: IntRange = 1..2,

    /**
     * "Switch sensitivity" (plan §3.3): scales every *down*-switch confirmation window.
     * Quick 0.6 / Balanced 1.0 / Sure 1.5.
     *
     * Up-switch windows are never scaled. The asymmetry is the whole design — a drop-in
     * has to land instantly at any setting, and only the patience for calming down is a
     * matter of taste.
     */
    val downSwitchScale: Double = 1.0,
) {
    /** Applies [downSwitchScale] to a down-switch window. */
    fun down(ms: Long): Long = (ms * downSwitchScale).toLong()
}
