package com.flowstate.sensors

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CadenceTest {

    /**
     * A 50 Hz magnitude trace (SENSOR_DELAY_GAME) with one sharp footfall per step on a
     * low noise floor, held for [seconds], trimmed exactly as the pipeline trims it.
     */
    private fun gait(spm: Double, seconds: Int, peak: Double = 8.0): List<Pair<Long, Double>> {
        val stepMs = 60_000.0 / spm
        val all = (0 until seconds * 50).map { i ->
            val t = i * 20L
            val phase = (t % stepMs) / stepMs
            // 60 ms half-sine impact, then quiet until the next foot lands.
            val impactFraction = 60.0 / stepMs
            val v = if (phase < impactFraction) 0.3 + peak * sin(PI * phase / impactFraction) else 0.3
            t to v
        }
        val now = all.last().first
        return all.filter { now - it.first <= Cadence.RETENTION_MS }
    }

    private fun List<Pair<Long, Double>>.now() = last().first

    @Test
    fun `a running gait reads as its cadence`() {
        val trace = gait(spm = 168.0, seconds = 15)

        val spm = Cadence.spm(trace, trace.now())

        assertNotNull("cadence was null with ${trace.size} samples", spm)
        assertEquals(168.0, spm!!, 12.0)
    }

    @Test
    fun `a walking gait reads as a walk`() {
        val trace = gait(spm = 110.0, seconds = 15, peak = 4.0)

        assertEquals(110.0, Cadence.spm(trace, trace.now())!!, 12.0)
    }

    @Test
    fun `the buffer the pipeline keeps is long enough to produce a reading`() {
        // The phase-6 defect: 5 s retained, at least 5 s of span demanded — null forever.
        val trace = gait(spm = 168.0, seconds = 30)
        val span = trace.last().first - trace.first().first

        assert(span >= Cadence.WINDOW_MS / 2) { "retained span $span ms is too short" }
        assertNotNull(Cadence.spm(trace, trace.now()))
    }

    @Test
    fun `a phone lying still reads zero steps, not a guess`() {
        val still = (0 until 600).map { it * 20L to 0.05 }

        assertEquals(0.0, Cadence.spm(still, still.last().first)!!, 0.0)
    }

    @Test
    fun `too little data gives no reading`() {
        val brief = (0 until 50).map { it * 20L to 0.05 } // one second

        assertNull(Cadence.spm(brief, brief.last().first))
    }
}
