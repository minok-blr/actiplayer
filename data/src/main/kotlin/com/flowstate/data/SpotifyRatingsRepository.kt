package com.flowstate.data

import com.flowstate.data.db.EnergySource
import com.flowstate.data.db.TrackDao
import com.flowstate.data.db.TrackEnergyEntity
import com.flowstate.data.db.TrackEntity
import com.flowstate.data.db.TrackSource
import com.flowstate.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A Spotify track the user has rated. Only rated tracks can enter a Spotify session pool. */
data class RatedSpotifyTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val rating: Int,
)

/**
 * Energy ratings for Spotify tracks, keyed by track URI.
 *
 * Unlike the local library — where every track is enumerable and unrated ones default to
 * 3 — Spotify has no "all my music" to fall back on, so the session pool is exactly the
 * rated tracks. Title and artist are stored alongside so sessions never have to re-fetch
 * a playlist to know what they queued.
 */
@Singleton
class SpotifyRatingsRepository @Inject constructor(
    private val dao: TrackDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    val tracks: StateFlow<Map<String, RatedSpotifyTrack>> =
        combine(dao.observeTracks(TrackSource.SPOTIFY), dao.observeEnergies()) { tracks, energies ->
            val bands = energies.associate { it.trackId to it.band }
            tracks.mapNotNull { track ->
                val uri = track.spotifyUri ?: return@mapNotNull null
                val band = bands[track.id] ?: return@mapNotNull null // unrated or excluded
                uri to RatedSpotifyTrack(uri, track.title, track.artist, band)
            }.toMap()
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun setRating(uri: String, title: String, artist: String, rating: Int) {
        val clamped = rating.coerceIn(1, 5)
        val id = TrackEntity.spotifyId(uri)
        scope.launch {
            dao.upsertTracks(
                listOf(
                    TrackEntity(
                        id = id,
                        source = TrackSource.SPOTIFY,
                        mediaStoreId = null,
                        spotifyUri = uri,
                        title = title,
                        artist = artist,
                        album = null,
                        folder = null,
                        durationMs = 0L,
                        bpmGuess = null,
                    ),
                ),
            )
            dao.upsertEnergies(
                listOf(
                    TrackEnergyEntity(
                        trackId = id,
                        band = clamped,
                        source = EnergySource.USER,
                        updatedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun ratingFor(uri: String): Int? = tracks.value[uri]?.rating

    /** Consistent read for session start; see [RatingsRepository.snapshot]. */
    suspend fun ratedTracks(): List<RatedSpotifyTrack> {
        val bands = dao.energies().associate { it.trackId to it.band }
        return dao.tracks(TrackSource.SPOTIFY).mapNotNull { track ->
            val uri = track.spotifyUri ?: return@mapNotNull null
            val band = bands[track.id] ?: return@mapNotNull null
            RatedSpotifyTrack(uri, track.title, track.artist, band)
        }
    }
}
