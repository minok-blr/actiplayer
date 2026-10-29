package com.flowstate.session

import com.flowstate.data.db.SessionEventEntity
import com.flowstate.data.db.SessionEventKind
import com.flowstate.engine.RideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecapBuilderTest {

    private fun mode(t: Long, state: RideState) =
        SessionEventEntity(sessionId = 1, tMs = t, kind = SessionEventKind.MODE, state = state)

    private fun sample(t: Long, hr: Int? = null, speed: Double? = null) =
        SessionEventEntity(
            sessionId = 1, tMs = t, kind = SessionEventKind.SAMPLE,
            hrBpm = hr, speedKmh = speed,
        )

    private fun track(t: Long, title: String, artist: String = "A") =
        SessionEventEntity(
            sessionId = 1, tMs = t, kind = SessionEventKind.TRACK,
            trackTitle = title, trackArtist = artist,
        )

    private fun override(t: Long) =
        SessionEventEntity(
            sessionId = 1, tMs = t, kind = SessionEventKind.OVERRIDE, overrideMode = "HYPE",
        )

    @Test
    fun `mode events become contiguous segments ending at the session end`() {
        val events = listOf(
            mode(0, RideState.PAUSED),
            mode(10_000, RideState.RIDING),
            mode(30_000, RideState.LIFT),
        )

        val recap = RecapBuilder.build(events, durationMs = 60_000)

        assertEquals(3, recap.segments.size)
        assertEquals(0L..10_000L, recap.segments[0].startMs..recap.segments[0].endMs)
        assertEquals(20_000L, recap.segments[1].durationMs)
        // The last segment runs to the end of the session, not to its own start.
        assertEquals(30_000L, recap.segments[2].durationMs)
    }

    @Test
    fun `time per mode adds up across repeated visits`() {
        val events = listOf(
            mode(0, RideState.RIDING),
            mode(10_000, RideState.LIFT),
            mode(20_000, RideState.RIDING),
        )

        val recap = RecapBuilder.build(events, durationMs = 30_000)

        assertEquals(20_000L, recap.timePerMode[RideState.RIDING])
        assertEquals(10_000L, recap.timePerMode[RideState.LIFT])
    }

    @Test
    fun `the opening mode is not counted as a switch`() {
        // Settling into a starting state is not the engine changing its mind.
        val one = RecapBuilder.build(listOf(mode(0, RideState.PAUSED)), 10_000)
        val three = RecapBuilder.build(
            listOf(
                mode(0, RideState.PAUSED),
                mode(5_000, RideState.RIDING),
                mode(8_000, RideState.LIFT),
            ),
            10_000,
        )

        assertEquals(0, one.switches)
        assertEquals(2, three.switches)
    }

    @Test
    fun `overrides are counted because each one is the engine being corrected`() {
        val recap = RecapBuilder.build(
            listOf(mode(0, RideState.RIDING), override(1_000), override(9_000)),
            10_000,
        )

        assertEquals(2, recap.overrides)
    }

    @Test
    fun `heart rate and speed stats come from the samples`() {
        val events = listOf(
            mode(0, RideState.RIDING),
            sample(0, hr = 120, speed = 10.0),
            sample(5_000, hr = 160, speed = 42.0),
            sample(10_000, hr = 140, speed = 30.0),
        )

        val recap = RecapBuilder.build(events, 15_000)

        assertEquals(140, recap.avgHr)
        assertEquals(160, recap.maxHr)
        assertEquals(42.0, recap.topSpeedKmh!!, 0.001)
    }

    @Test
    fun `a session with no heart rate reports none rather than zero`() {
        val recap = RecapBuilder.build(listOf(mode(0, RideState.RIDING), sample(0)), 5_000)

        assertNull(recap.avgHr)
        assertNull(recap.maxHr)
        assertNull(recap.topSpeedKmh)
    }

    @Test
    fun `tracks are ranked by plays and attributed to the mode they started under`() {
        val events = listOf(
            mode(0, RideState.RIDING),
            track(1_000, "Bleed It Out"),
            track(4_000, "Bleed It Out"),
            mode(10_000, RideState.LIFT),
            track(11_000, "Holocene", "Bon Iver"),
        )

        val recap = RecapBuilder.build(events, 20_000)

        assertEquals(listOf("Bleed It Out", "Holocene"), recap.topTracks.map { it.title })
        assertEquals(2, recap.topTracks[0].plays)
        assertEquals(RideState.RIDING, recap.topTracks[0].mode)
        assertEquals(RideState.LIFT, recap.topTracks[1].mode)
    }

    @Test
    fun `an empty session produces an empty recap rather than throwing`() {
        val recap = RecapBuilder.build(emptyList(), 0)

        assertEquals(0, recap.switches)
        assertEquals(emptyList<ModeSegment>(), recap.segments)
    }
}
