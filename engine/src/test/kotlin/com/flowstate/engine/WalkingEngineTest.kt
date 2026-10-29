package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Walking profile scenarios. Frames at 1 Hz. Default config: stroll <= 1.1 m/s
 * (~4 km/h), brisk > 1.5 m/s (~5.4 km/h) or motion > 1.2, HR gate at 78 bpm with an
 * easy mark of 70, brisk confirm 3 s / dwell 15 s, stroll confirm 20 s / dwell 10 s.
 */
class WalkingEngineTest {

    /** Ambling: below the stroll speed and calm with it. */
    private fun stroll(t: Long, bpm: Int? = null, hrTrend: Double? = null) =
        FeatureFrame(t, motionRms = 0.5, vertRate = 0.0, speed = 0.9, hrBpm = bpm, hrTrend = hrTrend)

    private fun standing(t: Long) =
        FeatureFrame(t, motionRms = 0.1, vertRate = 0.0, speed = 0.0)

    /** Stepping out: clearly over the speed bar, with a pulse to match. */
    private fun brisk(t: Long, bpm: Int? = 85, hrPctMax: Double? = null) =
        FeatureFrame(
            t, motionRms = 1.4, vertRate = 0.0, speed = 1.8,
            hrBpm = bpm, hrPctMax = hrPctMax,
        )

    /** Indoors: no GPS fix at all, only the cadence in the accelerometer. */
    private fun briskNoFix(t: Long, bpm: Int? = 85) =
        FeatureFrame(t, motionRms = 1.5, vertRate = null, speed = null, hrBpm = bpm)

    private fun WalkingEngine.feed(
        times: LongProgression,
        frame: (Long) -> FeatureFrame,
    ): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    @Test
    fun `session starts strolling with chill energy`() {
        val e = WalkingEngine()

        val d = e.onFrame(stroll(0))

        assertEquals(RideState.STROLL, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `stepping out with a matching pulse confirms brisk in seconds`() {
        val e = WalkingEngine()
        e.onFrame(stroll(0))

        val d = e.feed(1_000L..5_000L step 1_000) { brisk(it) }

        assertEquals(RideState.BRISK, d.state)
        assertEquals(4..5, d.targetEnergy)
    }

    @Test
    fun `walking fast with a resting pulse is not brisk`() {
        // The whole point of the HR gate: speed alone at walking pace is weak evidence,
        // and a downhill amble should not queue bangers.
        val e = WalkingEngine()
        e.onFrame(stroll(0))

        val d = e.feed(1_000L..20_000L step 1_000) { brisk(it, bpm = 62) }

        assertEquals(RideState.STROLL, d.state)
    }

    @Test
    fun `without a monitor movement evidence stands alone`() {
        val e = WalkingEngine()
        e.onFrame(stroll(0))

        val d = e.feed(1_000L..5_000L step 1_000) { brisk(it, bpm = null) }

        assertEquals(RideState.BRISK, d.state)
    }

    @Test
    fun `cadence alone carries the up-switch when there is no fix`() {
        // Indoors on a corridor or a treadmill — this is the profile's test path.
        val e = WalkingEngine()
        e.onFrame(stroll(0))

        val d = e.feed(1_000L..5_000L step 1_000) { briskNoFix(it) }

        assertEquals(RideState.BRISK, d.state)
    }

    @Test
    fun `waiting at a crossing does not end a brisk walk`() {
        val e = WalkingEngine()
        e.onFrame(stroll(0))
        e.feed(1_000L..20_000L step 1_000) { brisk(it) }

        // 15 s standing still is well under the 20 s stroll confirmation.
        val d = e.feed(21_000L..36_000L step 1_000) { standing(it) }

        assertEquals(RideState.BRISK, d.state)
    }

    @Test
    fun `sustained ambling returns to stroll`() {
        val e = WalkingEngine()
        e.onFrame(stroll(0))
        e.feed(1_000L..20_000L step 1_000) { brisk(it) }

        val d = e.feed(21_000L..60_000L step 1_000) { stroll(it) }

        assertEquals(RideState.STROLL, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `a pulse dropping back to easy ends the brisk walk`() {
        val e = WalkingEngine()
        e.onFrame(stroll(0))
        e.feed(1_000L..20_000L step 1_000) { brisk(it) }

        // Still reading a middling speed, but the pulse says the effort stopped.
        val d = e.feed(21_000L..60_000L step 1_000) {
            FeatureFrame(it, motionRms = 1.0, vertRate = 0.0, speed = 1.3, hrBpm = 64)
        }

        assertEquals(RideState.STROLL, d.state)
    }

    @Test
    fun `redlining during a brisk walk narrows the band to the top`() {
        val e = WalkingEngine()
        e.onFrame(stroll(0))
        e.feed(1_000L..20_000L step 1_000) { brisk(it) }

        val d = e.onFrame(brisk(21_000, hrPctMax = 0.9))

        assertEquals(RideState.BRISK, d.state)
        assertEquals(5..5, d.targetEnergy)
    }

    @Test
    fun `heart rate never moves the mode on its own`() {
        // HR gates the up-switch, but it cannot manufacture one without movement.
        val e = WalkingEngine()
        e.onFrame(stroll(0))

        val d = e.feed(1_000L..60_000L step 1_000) { stroll(it, bpm = 150) }

        assertEquals(RideState.STROLL, d.state)
    }
}
