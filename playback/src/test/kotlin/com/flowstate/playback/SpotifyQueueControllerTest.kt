package com.flowstate.playback

import com.flowstate.data.RatedSpotifyTrack
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure batch-selection half of the Spotify backend (the network half needs a device
 * and a Premium account — see the human test script in README/DECISIONS).
 */
class SpotifyQueueControllerTest {

    private fun track(n: Int, rating: Int) =
        RatedSpotifyTrack("spotify:track:$n", "Track $n", "Artist", rating)

    /** A healthy pool: plenty of tracks in every band. */
    private fun bigPool() = buildList {
        var n = 0
        for (rating in 1..5) repeat(10) { add(track(n++, rating)) }
    }

    private val random = Random(42)

    @Test
    fun `batch only contains tracks rated in the band when the pool is deep`() {
        val batch = SpotifyQueueController.batchFor(bigPool(), 4..5, random)
        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.rating in 4..5 })
    }

    @Test
    fun `thin band widens by one so the music never stalls`() {
        // Only 2 tracks rated 5 (below MIN_POOL), but plenty rated 4.
        val pool = List(2) { track(it, 5) } + List(10) { track(100 + it, 4) }
        val batch = SpotifyQueueController.batchFor(pool, 5..5, random)
        assertTrue(batch.size > 2)
        assertTrue(batch.all { it.rating in 4..5 })
    }

    @Test
    fun `widening clamps to the 1-5 scale`() {
        val pool = List(3) { track(it, 1) }
        val batch = SpotifyQueueController.batchFor(pool, 1..2, random)
        assertEquals(3, batch.size) // widened band is 1..3; never asks for rating 0
    }

    @Test
    fun `nothing near the band falls back to the whole pool`() {
        val pool = List(6) { track(it, 1) }
        val batch = SpotifyQueueController.batchFor(pool, 4..5, random)
        assertEquals(6, batch.size)
    }

    @Test
    fun `empty pool yields an empty batch`() {
        assertTrue(SpotifyQueueController.batchFor(emptyList(), 2..3, random).isEmpty())
    }

    @Test
    fun `batch is capped so the play request stays small`() {
        val pool = List(200) { track(it, 3) }
        val batch = SpotifyQueueController.batchFor(pool, 2..3, random)
        assertEquals(SpotifyQueueController.MAX_BATCH, batch.size)
    }
}
