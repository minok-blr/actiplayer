package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock profile. Nothing here depends on a threshold — which is the point of it —
 * so the tests are about the arithmetic of pausing, skipping and finishing.
 */
class TimerEngineTest {

    private fun frame(t: Long) = FeatureFrame(t, motionRms = 0.5, vertRate = null, speed = null)

    private fun TimerEngine.feed(times: LongProgression): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    /** 10 s work / 5 s rest, 3 rounds — short enough to walk through second by second. */
    private fun engine() = TimerEngine(TimerPlan(10_000, 5_000, 3))

    @Test
    fun `a session opens on work with hype energy`() {
        val e = engine()

        val d = e.onFrame(frame(0))

        assertEquals(RideState.WORK, d.state)
        assertEquals(4..5, d.targetEnergy)
        assertEquals(1, e.state.round)
    }

    @Test
    fun `the clock flips to rest when the work interval runs out`() {
        val e = engine()

        val d = e.feed(0L..11_000L step 1_000)

        assertEquals(RideState.REST, d.state)
        assertEquals(1..2, d.targetEnergy)
    }

    @Test
    fun `rounds advance and the last one finishes the plan`() {
        val e = engine()
        e.feed(0L..16_000L step 1_000)

        assertEquals(2, e.state.round)

        e.feed(17_000L..46_000L step 1_000)

        assertTrue("plan should be finished", e.state.finished)
        assertEquals(3, e.state.totalRounds)
    }

    @Test
    fun `pausing stops the clock and resuming continues where it stopped`() {
        val e = engine()
        e.feed(0L..4_000L step 1_000)
        val beforePause = e.state.remainingMs

        e.pause()
        e.feed(5_000L..25_000L step 1_000) // twenty seconds of paused frames
        assertEquals(beforePause, e.state.remainingMs)
        assertTrue(e.isPaused)

        e.resume()
        e.feed(26_000L..28_000L step 1_000)

        assertFalse(e.isPaused)
        // Resume takes effect at the next frame boundary, so the three steps from the last
        // paused frame count — and nothing else was lost to the twenty-second pause.
        assertEquals(beforePause - 3_000, e.state.remainingMs)
    }

    @Test
    fun `ending a set early jumps straight to the rest`() {
        val e = engine()
        e.feed(0L..3_000L step 1_000)
        assertTrue(e.state.working)

        e.endIntervalEarly()
        val d = e.onFrame(frame(4_000))

        assertEquals(RideState.REST, d.state)
    }

    @Test
    fun `undo puts the set back, so a pocket tap costs nothing`() {
        val e = engine()
        e.feed(0L..3_000L step 1_000)
        val remainingBefore = e.state.remainingMs

        e.endIntervalEarly()
        e.onFrame(frame(4_000))
        e.undoEndEarly()
        e.onFrame(frame(4_000))

        assertTrue(e.state.working)
        assertEquals(remainingBefore - 1_000, e.state.remainingMs)
    }

    @Test
    fun `undo only reverses the most recent skip`() {
        val e = engine()
        e.feed(0L..1_000L step 1_000)
        e.endIntervalEarly()
        e.onFrame(frame(2_000))
        e.undoEndEarly()
        e.onFrame(frame(2_000))
        // A second undo must not rewind further into the session.
        e.undoEndEarly()
        val d = e.onFrame(frame(2_000))

        assertEquals(RideState.WORK, d.state)
        assertEquals(1, e.state.round)
    }

    @Test
    fun `EMOM has no rest block, so ending early is what creates the rest`() {
        // Every minute on the minute: finish the reps, tap, and the remainder is recovery.
        val e = TimerEngine(TimerPlan.EMOM)
        e.feed(0L..20_000L step 5_000)
        assertTrue(e.state.working)

        e.endIntervalEarly()
        val d = e.onFrame(frame(21_000))

        // With rest = 0 the clock rolls straight into the next round's work.
        assertEquals(RideState.WORK, d.state)
        assertEquals(2, e.state.round)
    }

    @Test
    fun `the plan's total is what the setup screen promises`() {
        assertEquals((30_000L + 90_000L) * 8, TimerPlan.STRENGTH.totalMs)
        assertEquals((20_000L + 10_000L) * 8, TimerPlan.TABATA.totalMs)
    }

    @Test
    fun `a HIIT work block asks for only the very top of the pool`() {
        val config = EngineConfig()
        val e = TimerEngine(TimerPlan.TABATA, config, workEnergy = config.hiitWorkEnergy)

        val d = e.onFrame(frame(0))

        assertEquals(5..5, d.targetEnergy)
    }

    @Test
    fun `the clock lights no signal chip`() {
        val d = engine().onFrame(frame(0))

        assertTrue(d.reason.contributing.isEmpty())
    }
}
