package com.flowstate.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Switch sensitivity" (plan §3.3). The setting scales how patient the engine is about
 * calming down, and must never touch how fast it fires up.
 */
class SensitivityTest {

    private fun riding(t: Long) =
        FeatureFrame(t, motionRms = 3.2, vertRate = -2.0, speed = 12.0)

    private fun still(t: Long) =
        FeatureFrame(t, motionRms = 0.2, vertRate = 0.0, speed = 0.0)

    private fun StateEngine.feed(times: LongProgression, frame: (Long) -> FeatureFrame): StateDecision {
        var d: StateDecision? = null
        for (t in times) d = onFrame(frame(t))
        return d!!
    }

    @Test
    fun `quick gives up on a mode sooner than sure does`() {
        // Default paused confirmation is 30 s: Quick needs 18 s, Sure needs 45 s.
        val quick = StateEngine(EngineConfig(downSwitchScale = 0.6))
        val sure = StateEngine(EngineConfig(downSwitchScale = 1.5))
        listOf(quick, sure).forEach { it.feed(0L..20_000L step 1_000) { t -> riding(t) } }

        val quickAfter = quick.feed(21_000L..45_000L step 1_000) { still(it) }
        val sureAfter = sure.feed(21_000L..45_000L step 1_000) { still(it) }

        assertEquals(RideState.PAUSED, quickAfter.state)
        assertEquals(RideState.RIDING, sureAfter.state)
    }

    @Test
    fun `the up-switch is the same at every setting`() {
        // The drop-in has to land instantly whatever the athlete chose.
        EngineConfig().let { listOf(0.6, 1.0, 1.5) }.forEach { scale ->
            val e = StateEngine(EngineConfig(downSwitchScale = scale))
            e.onFrame(still(0))
            val d = e.feed(1_000L..4_000L step 1_000) { riding(it) }
            assertEquals("scale $scale changed the up-switch", RideState.RIDING, d.state)
        }
    }
}
