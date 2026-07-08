package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The synthetic-trace scenarios from build brief §13. Frames arrive at 1 Hz.
 * Default EngineConfig timings: riding confirm 3s / dwell 15s, lift confirm 15s / dwell 45s,
 * paused confirm 30s / dwell 20s, decay 90s.
 */
class StateEngineTest {

    private fun riding(t: Long) = FeatureFrame(t, motionRms = 3.5, vertRate = -2.0, speed = 8.0)
    private fun lift(t: Long) = FeatureFrame(t, motionRms = 0.3, vertRate = 1.2, speed = 4.0)
    private fun still(t: Long) = FeatureFrame(t, motionRms = 0.2, vertRate = 0.0, speed = 0.0)

    /** Mid-intensity mush: fires no rule at all (not calm, not violent). */
    private fun mushy(t: Long) = FeatureFrame(t, motionRms = 1.2, vertRate = 0.0, speed = 0.5)

    private fun StateEngine.feed(times: LongProgression, frame: (Long) -> FeatureFrame): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    @Test
    fun `drop-in confirms riding within seconds`() {
        val e = StateEngine()
        e.feed(0L..2_000L step 1_000, ::still)
        val d = e.feed(3_000L..6_000L step 1_000, ::riding)
        assertEquals(RideState.RIDING, d.state)
        assertEquals(4..5, d.targetEnergy)
    }

    @Test
    fun `a 20 second stop mid-run does not leave riding`() {
        val e = StateEngine()
        e.feed(0L..20_000L step 1_000, ::riding)
        val d = e.feed(21_000L..41_000L step 1_000, ::still) // 20 s < pausedConfirm (30 s)
        assertEquals(RideState.RIDING, d.state)
        assertEquals(RideState.RIDING, e.onFrame(riding(42_000L)).state)
    }

    @Test
    fun `sustained climb with a still body confirms lift`() {
        val e = StateEngine()
        e.feed(0L..5_000L step 1_000, ::riding)
        e.feed(6_000L..40_000L step 1_000, ::still) // PAUSED confirms around t=36s
        val d = e.feed(41_000L..57_000L step 1_000, ::lift)
        assertEquals(RideState.LIFT, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `riding takes over from lift quickly once lift dwell has passed`() {
        val e = StateEngine()
        e.feed(0L..5_000L step 1_000, ::riding)
        e.feed(6_000L..40_000L step 1_000, ::still)
        e.feed(41_000L..110_000L step 1_000, ::lift) // comfortably past lift dwell (45 s)
        val d = e.feed(111_000L..114_000L step 1_000, ::riding)
        assertEquals(RideState.RIDING, d.state)
    }

    @Test
    fun `lift confirmation in progress is cancelled by a riding burst`() {
        val e = StateEngine()
        e.feed(0L..25_000L step 1_000, ::still) // settle into PAUSED with dwell satisfied
        e.feed(26_000L..34_000L step 1_000, ::lift) // 9 s of lift-like signal: not confirmed yet
        val d = e.feed(35_000L..38_000L step 1_000, ::riding)
        assertEquals(RideState.RIDING, d.state)
    }

    @Test
    fun `no signals for long enough decays to paused`() {
        val e = StateEngine()
        e.feed(0L..20_000L step 1_000, ::riding)
        val d = e.feed(21_000L..115_000L step 1_000, ::mushy)
        assertEquals(RideState.PAUSED, d.state)
    }

    @Test
    fun `violent motion alone can confirm riding when gps and baro are dark`() {
        val e = StateEngine()
        val d = e.feed(0L..4_000L step 1_000) { t ->
            FeatureFrame(t, motionRms = 3.0, vertRate = null, speed = null)
        }
        assertEquals(RideState.RIDING, d.state)
    }

    // ------------------------------------------------------ HR modulation (Phase 5)

    @Test
    fun `high heart rate narrows the riding band to the top`() {
        val e = StateEngine()
        e.feed(0L..6_000L step 1_000, ::riding)
        val d = e.onFrame(riding(7_000L).copy(hrPctMax = 0.90))
        assertEquals(RideState.RIDING, d.state)
        assertEquals(5..5, d.targetEnergy)
    }

    @Test
    fun `band returns to base when heart rate settles`() {
        val e = StateEngine()
        e.feed(0L..6_000L step 1_000, ::riding)
        e.onFrame(riding(7_000L).copy(hrPctMax = 0.90))
        val d = e.onFrame(riding(8_000L).copy(hrPctMax = 0.75))
        assertEquals(4..5, d.targetEnergy)
    }

    @Test
    fun `falling heart rate while paused eases the band down`() {
        val e = StateEngine()
        e.feed(0L..25_000L step 1_000, ::still)
        val d = e.onFrame(still(26_000L).copy(hrTrend = -6.0))
        assertEquals(RideState.PAUSED, d.state)
        assertEquals(2..2, d.targetEnergy)
    }

    @Test
    fun `heart rate never changes the mode itself`() {
        val e = StateEngine()
        e.feed(0L..25_000L step 1_000, ::still) // settled in PAUSED
        // Redline-level HR while standing dead still (lift line after a hard run):
        // the mode MUST stay PAUSED — HR modulates, motion+elevation decide (brief §2.3).
        val d = e.feed(26_000L..40_000L step 1_000) { t -> still(t).copy(hrPctMax = 0.95) }
        assertEquals(RideState.PAUSED, d.state)
    }
}
