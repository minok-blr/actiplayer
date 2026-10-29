package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Generic profile: motion alone decides, active > 2.0, rest < 0.8. */
class GenericEngineTest {

    private fun moving(t: Long, motion: Double, hrPctMax: Double? = null, hrTrend: Double? = null) =
        FeatureFrame(t, motionRms = motion, vertRate = null, speed = null,
            hrPctMax = hrPctMax, hrTrend = hrTrend)

    private fun GenericEngine.feed(times: LongProgression, frame: (Long) -> FeatureFrame): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    @Test
    fun `working hard reaches active and hype energy`() {
        val e = GenericEngine()
        e.onFrame(moving(0, 1.2))

        val d = e.feed(1_000L..5_000L step 1_000) { moving(it, 3.0) }

        assertEquals(RideState.ACTIVE, d.state)
        assertEquals(4..5, d.targetEnergy)
    }

    @Test
    fun `going still reaches rest and chill energy`() {
        val e = GenericEngine()
        e.feed(0L..20_000L step 1_000) { moving(it, 3.0) }

        val d = e.feed(21_000L..60_000L step 1_000) { moving(it, 0.2) }

        assertEquals(RideState.REST, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `middling motion sits at easy with mid energy`() {
        val e = GenericEngine()

        val d = e.feed(0L..30_000L step 1_000) { moving(it, 1.4) }

        assertEquals(RideState.EASY, d.state)
        assertEquals(3..3, d.targetEnergy)
    }

    @Test
    fun `a brief stop does not end an active block`() {
        val e = GenericEngine()
        e.feed(0L..20_000L step 1_000) { moving(it, 3.0) }

        // Ten seconds still, well under the 25 s rest confirmation.
        val d = e.feed(21_000L..31_000L step 1_000) { moving(it, 0.2) }

        assertEquals(RideState.ACTIVE, d.state)
    }

    @Test
    fun `heart rate never picks the state on its own`() {
        val e = GenericEngine()

        val d = e.feed(0L..60_000L step 1_000) { moving(it, 0.2, hrPctMax = 0.95) }

        assertEquals(RideState.REST, d.state)
    }

    @Test
    fun `getting up from rest lifts the music in seconds`() {
        val e = GenericEngine()
        e.feed(0L..40_000L step 1_000) { moving(it, 0.2) }

        val d = e.feed(41_000L..45_000L step 1_000) { moving(it, 1.4) }

        assertEquals(RideState.EASY, d.state)
    }

    @Test
    fun `settling from active to easy still takes the down window`() {
        val e = GenericEngine()
        e.feed(0L..20_000L step 1_000) { moving(it, 3.0) }

        assertEquals(RideState.ACTIVE, e.feed(21_000L..30_000L step 1_000) { moving(it, 1.4) }.state)
        assertEquals(RideState.EASY, e.feed(31_000L..40_000L step 1_000) { moving(it, 1.4) }.state)
    }
}
