package com.flowstate.data

import com.flowstate.data.RatingsRepository.Companion.toLocalRatingMap
import com.flowstate.data.db.EnergySource
import com.flowstate.data.db.TrackEnergyEntity
import com.flowstate.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The energy table is shared by local files and Spotify URIs, so the local view has to
 * pick its own rows out of it exactly. A mistake here silently empties a user's ratings.
 */
class RatingsRepositoryTest {

    private fun row(trackId: String, band: Int?) =
        TrackEnergyEntity(trackId, band, EnergySource.USER, 0L)

    @Test
    fun `local rows map back to their media store ids`() {
        val map = listOf(row(TrackEntity.localId(42L), 5), row(TrackEntity.localId(7L), 1))
            .toLocalRatingMap()

        assertEquals(mapOf(42L to 5, 7L to 1), map)
    }

    @Test
    fun `spotify rows are not local ratings`() {
        val map = listOf(
            row(TrackEntity.spotifyId("spotify:track:abc"), 4),
            row(TrackEntity.localId(1L), 3),
        ).toLocalRatingMap()

        assertEquals(mapOf(1L to 3), map)
    }

    @Test
    fun `excluded tracks drop out rather than defaulting to a band`() {
        val map = listOf(row(TrackEntity.localId(9L), null)).toLocalRatingMap()

        assertEquals(emptyMap<Long, Int>(), map)
    }

    @Test
    fun `unrated tracks fall back to the neutral default`() {
        assertEquals(3, RatingsRepository.effectiveRating(mapOf(1L to 5), 2L))
        assertEquals(5, RatingsRepository.effectiveRating(mapOf(1L to 5), 1L))
    }
}
