package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cycling profile scenarios. Frames at 1 Hz. Default config: chill speed <= 4.17 m/s
 * (~15 km/h), effort = +1.2 m/s across a 6 s window (+ rising HR when present),
 * effort confirm 3 s / dwell 15 s, cruise confirm 20 s / dwell 10 s.
 */
class CyclingEngineTest {

    /** Easy rolling well below the chill threshold. */
    private fun cruise(t: Long, hrTrend: Double? = null) =
        FeatureFrame(t, motionRms = 0.8, vertRate = 0.0, speed = 3.0, hrTrend = hrTrend)

    private fun stopped(t: Long) =
        FeatureFrame(t, motionRms = 0.2, vertRate = 0.0, speed = 0.0)

    /** Accelerating hard from cruise pace: +0.5 m/s per second. */
    private fun sprintRamp(t: Long, startMs: Long, hrTrend: Double? = 6.0, hrPctMax: Double? = null) =
        FeatureFrame(
            t, motionRms = 1.5, vertRate = 0.0,
            speed = 3.0 + 0.5 * ((t - startMs) / 1_000),
            hrTrend = hrTrend, hrPctMax = hrPctMax,
        )

    /** Fast but steady: no speed gain, nothing to confirm an effort with. */
    private fun fastSteady(t: Long) =
        FeatureFrame(t, motionRms = 1.5, vertRate = 0.0, speed = 7.0)

    private fun CyclingEngine.feed(times: LongProgression, frame: (Long) -> FeatureFrame): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    @Test
    fun `session starts cruising with chill energy`() {
        val e = CyclingEngine()
        val d = e.onFrame(cruise(0L))
        assertEquals(RideState.CRUISE, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `slow rolling and red lights stay in cruise`() {
        val e = CyclingEngine()
        e.feed(0L..30_000L step 1_000, ::cruise)
        val d = e.feed(31_000L..60_000L step 1_000, ::stopped)
        assertEquals(RideState.CRUISE, d.state)
    }

    @Test
    fun `speed gain with rising heart rate confirms effort in seconds`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        val d = e.feed(10_000L..18_000L step 1_000) { t -> sprintRamp(t, 10_000L) }
        assertEquals(RideState.EFFORT, d.state)
        assertEquals(4..5, d.targetEnergy)
    }

    @Test
    fun `speed gain without a monitor still confirms effort`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        val d = e.feed(10_000L..18_000L step 1_000) { t -> sprintRamp(t, 10_000L, hrTrend = null) }
        assertEquals(RideState.EFFORT, d.state)
    }

    @Test
    fun `speed gain with flat heart rate does not count as effort`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        // Downhill: speed rises but the rider isn't working (HR flat).
        val d = e.feed(10_000L..18_000L step 1_000) { t -> sprintRamp(t, 10_000L, hrTrend = 0.0) }
        assertEquals(RideState.CRUISE, d.state)
    }

    @Test
    fun `fast but steady speed alone never fires effort`() {
        val e = CyclingEngine()
        e.feed(0L..40_000L step 1_000, ::fastSteady)
        assertEquals(RideState.CRUISE, e.onFrame(fastSteady(41_000L)).state)
    }

    @Test
    fun `a short coast does not end effort`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        e.feed(10_000L..20_000L step 1_000) { t -> sprintRamp(t, 10_000L) } // EFFORT by ~t=16s
        val d = e.feed(21_000L..31_000L step 1_000, ::cruise) // 10 s lull < cruise confirm 20 s
        assertEquals(RideState.EFFORT, d.state)
        assertEquals(RideState.EFFORT, e.onFrame(fastSteady(32_000L)).state)
    }

    @Test
    fun `sustained slow riding returns to cruise`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        e.feed(10_000L..20_000L step 1_000) { t -> sprintRamp(t, 10_000L) }
        val d = e.feed(21_000L..45_000L step 1_000, ::cruise) // 24 s > confirm 20 s
        assertEquals(RideState.CRUISE, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `redlining during effort narrows the band to the top`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        e.feed(10_000L..18_000L step 1_000) { t -> sprintRamp(t, 10_000L) }
        val d = e.onFrame(sprintRamp(19_000L, 10_000L, hrPctMax = 0.92))
        assertEquals(RideState.EFFORT, d.state)
        assertEquals(5..5, d.targetEnergy)
    }
}
