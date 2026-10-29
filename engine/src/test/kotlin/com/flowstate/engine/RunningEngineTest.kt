package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Running profile. Default config: push > 3.3 m/s or cadence >= 172, walk <= 2.0 m/s or
 * cadence < 140, push confirm 3 s, cruise 15 s, walk 20 s.
 */
class RunningEngineTest {

    private fun walking(t: Long) =
        FeatureFrame(t, motionRms = 1.0, vertRate = null, speed = 1.4, cadence = 115.0)

    private fun jogging(t: Long) =
        FeatureFrame(t, motionRms = 2.0, vertRate = null, speed = 2.8, cadence = 158.0)

    private fun sprinting(t: Long, hrPctMax: Double? = null) =
        FeatureFrame(
            t, motionRms = 3.0, vertRate = null, speed = 4.0, cadence = 180.0,
            hrPctMax = hrPctMax,
        )

    /** Treadmill: a real gait, no fix at all. */
    private fun treadmill(t: Long, cadence: Double) =
        FeatureFrame(t, motionRms = 2.2, vertRate = null, speed = null, cadence = cadence)

    private fun RunningEngine.feed(times: LongProgression, frame: (Long) -> FeatureFrame): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    @Test
    fun `a session starts walking with chill energy`() {
        val d = RunningEngine().onFrame(walking(0))

        assertEquals(RideState.WALK, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `breaking into a jog reaches cruise and mid energy`() {
        val e = RunningEngine()
        e.onFrame(walking(0))

        val d = e.feed(1_000L..20_000L step 1_000) { jogging(it) }

        assertEquals(RideState.CRUISE, d.state)
        assertEquals(3..3, d.targetEnergy)
    }

    @Test
    fun `a sprint confirms push in seconds`() {
        val e = RunningEngine()
        e.onFrame(walking(0))

        val d = e.feed(1_000L..5_000L step 1_000) { sprinting(it) }

        assertEquals(RideState.PUSH, d.state)
        assertEquals(4..5, d.targetEnergy)
    }

    @Test
    fun `cadence carries the up-switch with no GPS fix at all`() {
        // A treadmill reports no speed; the gait is the only evidence there is.
        val e = RunningEngine()
        e.onFrame(walking(0))

        val d = e.feed(1_000L..5_000L step 1_000) { treadmill(it, cadence = 178.0) }

        assertEquals(RideState.PUSH, d.state)
    }

    @Test
    fun `a walking gait is a walk even when the fix claims otherwise`() {
        // GPS drift on a slow lap can read as running speed; the legs say otherwise.
        val e = RunningEngine()
        e.feed(0L..20_000L step 1_000) { jogging(it) }

        val d = e.feed(21_000L..60_000L step 1_000) {
            FeatureFrame(it, motionRms = 1.0, vertRate = null, speed = 2.6, cadence = 118.0)
        }

        assertEquals(RideState.WALK, d.state)
    }

    @Test
    fun `a hard pulse promotes a merely-running pace to push`() {
        val e = RunningEngine()
        e.onFrame(walking(0))

        val d = e.feed(1_000L..6_000L step 1_000) {
            FeatureFrame(
                it, motionRms = 2.0, vertRate = null, speed = 2.9, cadence = 160.0,
                hrPctMax = 0.85,
            )
        }

        assertEquals(RideState.PUSH, d.state)
    }

    @Test
    fun `a few slow strides do not end a hard effort`() {
        val e = RunningEngine()
        e.feed(0L..20_000L step 1_000) { sprinting(it) }

        // Ten seconds is well under the 20 s walk confirmation.
        val d = e.feed(21_000L..31_000L step 1_000) { walking(it) }

        assertEquals(RideState.PUSH, d.state)
    }

    @Test
    fun `redlining narrows the push band to the top`() {
        val e = RunningEngine()
        e.feed(0L..20_000L step 1_000) { sprinting(it) }

        val d = e.onFrame(sprinting(21_000, hrPctMax = 0.9))

        assertEquals(5..5, d.targetEnergy)
    }

    @Test
    fun `breaking into a jog from a walk lifts the music in seconds`() {
        // WALK -> CRUISE is an up-switch: it gets the fast window, not the 15 s one.
        val e = RunningEngine()
        e.feed(0L..30_000L step 1_000) { walking(it) }

        val d = e.feed(31_000L..35_000L step 1_000) { jogging(it) }

        assertEquals(RideState.CRUISE, d.state)
    }

    @Test
    fun `easing from a sprint to a jog still takes the down window`() {
        val e = RunningEngine()
        e.feed(0L..20_000L step 1_000) { sprinting(it) }

        val early = e.feed(21_000L..30_000L step 1_000) { jogging(it) }
        assertEquals(RideState.PUSH, early.state)

        val later = e.feed(31_000L..40_000L step 1_000) { jogging(it) }
        assertEquals(RideState.CRUISE, later.state)
    }
}
