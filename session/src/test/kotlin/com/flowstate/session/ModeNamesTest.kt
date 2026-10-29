package com.flowstate.session

import com.flowstate.engine.Activity
import com.flowstate.engine.RideState
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeNamesTest {

    @Test
    fun `a running cruise is mid, a cycling cruise is chill`() {
        assertEquals(EnergyTier.MID, RideState.CRUISE.tier(Activity.RUNNING))
        assertEquals(EnergyTier.CHILL, RideState.CRUISE.tier(Activity.CYCLING))
    }

    @Test
    fun `strength calls its work block a set`() {
        assertEquals("SET", RideState.WORK.displayName(Activity.STRENGTH))
        assertEquals("WORK", RideState.WORK.displayName(Activity.HIIT))
    }

    @Test
    fun `every activity lists its modes hype first, each in the tier it claims`() {
        Activity.entries.forEach { activity ->
            val tiers = activity.modes().map { it.tier(activity) }
            assertEquals("$activity", tiers.sortedBy { it.ordinal }, tiers)
        }
    }
}
