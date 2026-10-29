package com.flowstate.data

import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.EnergySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a session gets to play, and how it was arrived at.
 *
 * [fallback] is true when the user has neither rated anything nor built a pool: rather
 * than start a session in silence, everything that is not flagged as a podcast plays at
 * mid energy. Home's readiness check surfaces this so it never happens quietly.
 */
data class SessionPool(
    val tracks: List<ResolvedTrack<Track>>,
    val unsorted: Int,
    val fallback: Boolean,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()

    fun countAt(level: EnergyLevel): Int =
        tracks.count { EnergyResolver.levelOf(it.band) == level }
}

/**
 * Joins MediaStore, ratings and pools into the one thing the session actually needs.
 * Reads are consistent snapshots, not cached flows — a session must never start on a
 * half-populated pool.
 */
@Singleton
class MusicCatalog @Inject constructor(
    private val library: MediaLibrary,
    private val ratings: RatingsRepository,
    private val pools: PoolRepository,
) {

    suspend fun sessionPool(): SessionPool {
        val tracks = library.loadTracks()
        val energies = ratings.snapshot()
        val poolSources = pools.snapshot()
        val playlistMembers = playlistMembers(poolSources.any { it.kind.isPlaylist })

        val resolved = EnergyResolver.resolve(tracks, energies, poolSources, playlistMembers)
        if (resolved.isNotEmpty()) {
            return SessionPool(
                tracks = resolved,
                unsorted = EnergyResolver.unsorted(tracks, energies, poolSources, playlistMembers).size,
                fallback = false,
            )
        }

        // Nothing rated, nothing pooled: play the library at mid energy rather than
        // nothing at all, minus anything MediaStore flags as a podcast.
        val music = tracks.filterNot { it.isPodcast || it.id in energies.excluded }
        return SessionPool(
            tracks = music.map {
                ResolvedTrack(it, RatingsRepository.UNRATED_DEFAULT, EnergySource.GUESS)
            },
            unsorted = music.size,
            fallback = music.isNotEmpty(),
        )
    }

    /** Playlist membership is a second cursor pass, so only pay for it when pooled. */
    private suspend fun playlistMembers(needed: Boolean): Map<String, Set<Long>> =
        if (!needed) {
            emptyMap()
        } else {
            library.loadPlaylists().associate { it.id.toString() to it.audioIds.toSet() }
        }
}

private val com.flowstate.data.db.PoolSourceKind.isPlaylist: Boolean
    get() = this == com.flowstate.data.db.PoolSourceKind.PLAYLIST
