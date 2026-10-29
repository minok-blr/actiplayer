package com.flowstate.app.ui.session

import com.flowstate.engine.Activity
import com.flowstate.engine.Evidence
import com.flowstate.engine.HrEffect
import com.flowstate.engine.Reason
import com.flowstate.engine.RideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence is the trust mechanism, so it gets tested like one: no thresholds, no
 * variable names, and never a bare state name where a plain statement belongs.
 */
class ReasonFormatterTest {

    @Test
    fun `a drop-in reads as descending, not as a threshold`() {
        val reason = Reason.Confirmed(
            RideState.RIDING,
            heldMs = 3_000,
            evidence = setOf(Evidence.HEAVY_MOTION, Evidence.DESCENDING),
        )

        assertEquals(
            "Descending fast with heavy motion for 3 s.",
            ReasonFormatter.sentence(reason),
        )
    }

    @Test
    fun `the chairlift reads as a steady climb`() {
        val reason = Reason.Confirmed(
            RideState.LIFT,
            heldMs = 15_000,
            evidence = setOf(Evidence.STEADY_CLIMB, Evidence.BARELY_MOVING),
        )

        assertEquals("Steady climb, barely moving, for 15 s.", ReasonFormatter.sentence(reason))
    }

    @Test
    fun `redlining explains what it did to the music`() {
        val reason = Reason.HeartRate(HrEffect.REDLINING, pctMax = 0.86, trend = null, band = 5..5)

        assertEquals(
            "Pulse at 86% of max, so only your top tracks.",
            ReasonFormatter.sentence(reason),
        )
    }

    @Test
    fun `a decay says what it is easing to`() {
        assertEquals(
            "Nothing moving for 90 s, easing to mid.",
            ReasonFormatter.sentence(Reason.Decayed(90_000)),
        )
    }

    @Test
    fun `cycling distinguishes speed alone from speed with a rising pulse`() {
        val withHr = Reason.Confirmed(
            RideState.EFFORT,
            6_000,
            setOf(Evidence.SPEED_GAIN, Evidence.HR_RISING),
        )
        val without = Reason.Confirmed(RideState.EFFORT, 6_000, setOf(Evidence.SPEED_GAIN))

        assertEquals("Speed and pulse climbing together for 6 s.", ReasonFormatter.sentence(withHr))
        assertEquals("Picking up speed for 6 s.", ReasonFormatter.sentence(without))
    }

    @Test
    fun `walking distinguishes a measured pace from a detected stride`() {
        val paced = Reason.Confirmed(RideState.BRISK, 3_000, setOf(Evidence.BRISK_PACE))
        val strideOnly = Reason.Confirmed(RideState.BRISK, 3_000, setOf(Evidence.STRIDING))

        assertEquals("Walking briskly for 3 s.", ReasonFormatter.sentence(paced))
        assertEquals("A steady stride for 3 s.", ReasonFormatter.sentence(strideOnly))
    }

    @Test
    fun `long holds switch to minutes rather than counting seconds forever`() {
        val reason = Reason.Confirmed(RideState.CRUISE, 300_000, setOf(Evidence.EASY_PACE))

        assertEquals("Rolling easy for 5 min.", ReasonFormatter.sentence(reason))
    }

    @Test
    fun `before any decision it says what it is doing`() {
        assertEquals("Listening to your sensors…", ReasonFormatter.sentence(null))
        assertEquals("Listening to your sensors…", ReasonFormatter.sentence(Reason.SessionStart))
    }

    @Test
    fun `every state produces a sentence, never a bare enum name`() {
        // A missing branch would surface on the biggest text on the screen.
        RideState.entries.forEach { state ->
            val sentence = ReasonFormatter.sentence(Reason.Confirmed(state, 3_000, emptySet()))
            assertTrue("$state produced '$sentence'", sentence.endsWith("."))
            assertTrue("$state leaked its enum name", !sentence.contains(state.name))
        }
    }

    @Test
    fun `a running cruise is described as running, not rolling`() {
        val reason = Reason.Confirmed(RideState.CRUISE, 3_000, setOf(Evidence.STRIDING))

        assertEquals(
            "Running at an easy pace for 3 s.",
            ReasonFormatter.sentence(reason, Activity.RUNNING),
        )
        assertEquals("Rolling easy for 3 s.", ReasonFormatter.sentence(reason, Activity.CYCLING))
    }

    @Test
    fun `generic going still is not described as a timer rest block`() {
        val still = Reason.Confirmed(RideState.REST, 25_000, setOf(Evidence.STILL))
        val clock = Reason.Confirmed(RideState.REST, 0, setOf(Evidence.ON_THE_CLOCK))

        assertEquals("Still for 25 s.", ReasonFormatter.sentence(still, Activity.GENERIC))
        assertEquals("Rest block, easing off.", ReasonFormatter.sentence(clock, Activity.HIIT))
    }
}
