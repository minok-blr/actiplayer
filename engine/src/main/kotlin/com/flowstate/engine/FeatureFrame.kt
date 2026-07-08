package com.flowstate.engine

/**
 * One snapshot of the athlete's physical state, emitted at ~1 Hz (build brief §6).
 *
 * All signal fields are nullable: any sensor can be absent (no barometer, no GPS fix,
 * phone in a pocket under a jacket in the trees) and the engine must degrade gracefully
 * rather than stall.
 */
data class FeatureFrame(
    /** Timestamp in ms on a monotonic clock (e.g. elapsedRealtime). Never wall-clock. */
    val tMillis: Long,
    /** RMS of linear-acceleration magnitude over ~3 s, in m/s². Orientation-independent. */
    val motionRms: Double?,
    /** Vertical rate in m/s, positive = climbing. Barometer preferred, GPS-altitude fallback. */
    val vertRate: Double?,
    /** Ground speed in m/s from GPS. Null when there is no fresh fix. */
    val speed: Double?,
    /** Latest heart rate in bpm from a BLE monitor. Null when absent or stale. */
    val hrBpm: Int? = null,
    /** hrBpm as a fraction of the user's configured max HR. */
    val hrPctMax: Double? = null,
    /** Heart-rate slope over ~60 s, in bpm per minute. Positive = climbing. */
    val hrTrend: Double? = null,
)
