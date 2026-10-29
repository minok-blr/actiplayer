package com.flowstate.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPickerTest {

    private data class Fake(override val id: Long, override val band: Int) : Banded

    private val random = Random(1)

    private fun pool(vararg bands: Pair<Long, Int>) = bands.map { Fake(it.first, it.second) }

    @Test
    fun `picks only from the target band when the pool is deep enough`() {
        val deep = (1L..20L).map { Fake(it, if (it <= 10) 5 else 1) }

        repeat(50) {
            val pick = TrackPicker.pick(deep, 4..5, emptyList(), random)
            assertEquals(5, pick!!.band)
        }
    }

    @Test
    fun `a thin band widens by one rather than looping the same few tracks`() {
        // Only three tracks at band 5, but plenty at 4 — widening should reach them.
        val thin = (1L..3L).map { Fake(it, 5) } + (10L..20L).map { Fake(it, 4) }

        val seen = (1..50).mapNotNull { TrackPicker.pick(thin, 5..5, emptyList(), random)?.band }

        assertTrue("widening never reached band 4", seen.any { it == 4 })
    }

    @Test
    fun `recently played tracks are skipped`() {
        val small = pool(1L to 5, 2L to 5, 3L to 5)

        val pick = TrackPicker.pick(small, 4..5, listOf(1L, 2L), random)

        assertEquals(3L, pick!!.id)
    }

    @Test
    fun `the no-repeat rule is dropped before giving up entirely`() {
        val small = pool(1L to 5, 2L to 5)

        // Everything is "recent", but silence is not an acceptable answer.
        val pick = TrackPicker.pick(small, 4..5, listOf(1L, 2L), random)

        assertNotNull(pick)
    }

    @Test
    fun `an empty pool picks nothing rather than crashing`() {
        assertNull(TrackPicker.pick(emptyList<Fake>(), 1..5, emptyList(), random))
    }

    @Test
    fun `standby preloads the loud band while calm and the calm band while loud`() {
        // Up-switches have to be instant, so the band worth having warm is the far one.
        assertEquals(4..5, TrackPicker.standbyBand(1..2))
        assertEquals(4..5, TrackPicker.standbyBand(2..3))
        assertEquals(1..2, TrackPicker.standbyBand(4..5))
    }

    @Test
    fun `a small hype pool repeats hype rather than drifting into chill`() {
        // The user's real library: six Hype tracks, three Chill, no Mid.
        val lib = (1L..6L).map { Fake(it, 5) } + (7L..9L).map { Fake(it, 1) }
        val recent = ArrayDeque<Long>()

        repeat(40) {
            val pick = TrackPicker.pick(lib, 4..5, recent, random)!!
            assertEquals("a riding block was handed band ${pick.band}", 5, pick.band)
            recent.addLast(pick.id)
            while (recent.size > TrackPicker.RECENT_LIMIT) recent.removeFirst()
        }
    }

    @Test
    fun `when a band has been played through the oldest comes round first`() {
        val lib = pool(1L to 5, 2L to 5, 3L to 5)

        // 2 was heard longest ago; 1 was the last thing played.
        val pick = TrackPicker.pick(lib, 4..5, listOf(2L, 3L, 1L), random)

        assertEquals(2L, pick!!.id)
    }

    @Test
    fun `an empty mid pool borrows from the nearest band`() {
        val lib = (1L..6L).map { Fake(it, 5) } + (7L..9L).map { Fake(it, 1) }

        // BREAK wants 2..3; Chill (1) is one away, Hype (5) two away.
        repeat(20) {
            assertEquals(1, TrackPicker.pick(lib, 2..3, emptyList(), random)!!.band)
        }
    }
}
