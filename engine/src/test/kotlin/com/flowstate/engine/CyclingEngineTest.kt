package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The case the grace window exists for, with the pulse modelled the way the phone
     * actually sees it: HR recovers during a stop at a light, lags the acceleration away
     * from it, and the trend is the sensor pipeline's own 60 s head-third/tail-third slope.
     * Demanding speed gain and a rising trend in the *same* seconds, as the engine first
     * did, never confirms this effort at all — the gain is over before the trend turns.
     */
    @Test
    fun `an effort off a red light confirms once the lagging pulse catches up`() {
        val e = CyclingEngine()
        val hr = HrModel()
        var d: StateDecision? = null
        for (s in 0..200) {
            val t = s * 1_000L
            val speed = when {
                s < 60 -> 3.0 // easy rolling
                s < 100 -> 0.0 // stopped at the light
                s < 108 -> 0.875 * (s - 100) // pulling away
                else -> 7.0 // back up to ~25 km/h and holding it
            }
            val target = when {
                s < 60 -> 120.0
                s < 100 -> 100.0
                else -> 155.0
            }
            hr.step(target, tauS = if (s < 100) 20.0 else 25.0)
            d = e.onFrame(
                FeatureFrame(
                    t, motionRms = 1.5, vertRate = 0.0, speed = speed,
                    hrBpm = hr.bpm.toInt(), hrTrend = hr.trend(t),
                ),
            )
            if (d.state == RideState.EFFORT) break
        }
        assertEquals(RideState.EFFORT, d!!.state)
        // ...and within a sensible time of pulling away (t = 100 s), not by accident later.
        assertTrue("confirmed at ${d.atMillis}", d.atMillis in 100_000L..150_000L)
    }

    @Test
    fun `a gain the pulse never agrees with stays a cruise`() {
        val e = CyclingEngine()
        e.feed(0L..9_000L step 1_000, ::cruise)
        e.feed(10_000L..18_000L step 1_000) { t -> sprintRamp(t, 10_000L, hrTrend = 0.0) }
        // A long downhill after the ramp: fast, and the pulse flat the whole way.
        val d = e.feed(19_000L..80_000L step 1_000) {
            FeatureFrame(it, motionRms = 1.0, vertRate = 0.0, speed = 9.0, hrTrend = 0.0)
        }
        assertEquals(RideState.CRUISE, d.state)
    }

    /** First-order HR response plus the pipeline's trend estimate over 1 Hz samples. */
    private class HrModel(start: Double = 120.0) {
        var bpm = start
            private set
        private val samples = ArrayDeque<Pair<Long, Double>>()
        private var t = 0L

        fun step(target: Double, tauS: Double) {
            bpm += (target - bpm) / tauS
            samples.addLast(t to bpm)
            t += 1_000
        }

        /** Mirrors SensorPipeline.hrTrend: newest third vs oldest third of the last 60 s. */
        fun trend(now: Long): Double? {
            val window = samples.filter { now - it.first <= 60_000 }
            if (window.size < 6) return null
            val k = (window.size / 3).coerceAtLeast(1)
            val head = window.take(k)
            val tail = window.takeLast(k)
            val t0 = head.map { it.first }.average()
            val t1 = tail.map { it.first }.average()
            if (t1 - t0 < 20_000) return null
            return (tail.map { it.second }.average() - head.map { it.second }.average()) /
                ((t1 - t0) / 60_000.0)
        }
    }
}
