package com.flowstate.data

import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.EnergySource
import com.flowstate.data.db.PoolSourceEntity
import com.flowstate.data.db.PoolSourceKind

/** A track with the energy band the resolver decided on, and why. */
data class ResolvedTrack<T : PoolKeys>(
    val track: T,
    val band: Int,
    val source: EnergySource,
)

/**
 * The energy resolution order from plan §3.1:
 *
 *   user rating -> pool membership (HYPE=5 / MID=3 / CHILL=1) -> tempo guess -> 3
 *
 * The tempo guess is phase 9, so today a track that is neither rated nor pooled is
 * *unsorted* and does not enter a session at all — that is what screen C1's "the engine
 * only ever picks from these" means, and it is the whole reason pools exist: a device
 * with 164 podcast episodes and 15 real tracks should not be shuffling podcasts.
 *
 * Pure and Android-free so the rules are unit-testable; see [PoolKeys].
 */
object EnergyResolver {

    /**
     * Resolves every track that belongs in a session pool. Tracks with no rating and no
     * pool membership are left out entirely — [unsortedCount] counts those.
     */
    fun <T : PoolKeys> resolve(
        tracks: List<T>,
        energies: LocalEnergies,
        pools: List<PoolSourceEntity>,
        playlistMembers: Map<String, Set<Long>> = emptyMap(),
    ): List<ResolvedTrack<T>> {
        val levels = poolLevels(pools, playlistMembers)
        return tracks.mapNotNull { track ->
            // Podcasts are never music, whatever folder they sit in and whatever anyone
            // rated them: an episode arriving mid-run is the one thing worse than silence.
            if (track.isPodcast) return@mapNotNull null
            // Exclusion is the user saying "never this one" — it has to outrank the
            // folder the file happens to sit in, or a pooled folder would drag it back.
            if (track.id in energies.excluded) return@mapNotNull null
            val rated = energies.ratings[track.id]
            when {
                rated != null -> ResolvedTrack(track, rated, EnergySource.USER)
                else -> levels.levelFor(track)?.let {
                    ResolvedTrack(track, it.band, EnergySource.POOL)
                }
            }
        }
    }

    /**
     * Tracks that are neither rated nor pooled — the C1 "unsorted" banner's number.
     * Podcasts are not counted: they are not waiting to be sorted, they are not music.
     */
    fun <T : PoolKeys> unsorted(
        tracks: List<T>,
        energies: LocalEnergies,
        pools: List<PoolSourceEntity>,
        playlistMembers: Map<String, Set<Long>> = emptyMap(),
    ): List<T> {
        val levels = poolLevels(pools, playlistMembers)
        return tracks.filter {
            !it.isPodcast &&
                it.id !in energies.ratings &&
                it.id !in energies.excluded &&
                levels.levelFor(it) == null
        }
    }

    /** How many tracks each pool currently supplies — the counts on screen C1's cards. */
    fun <T : PoolKeys> trackCounts(
        tracks: List<T>,
        energies: LocalEnergies,
        pools: List<PoolSourceEntity>,
        playlistMembers: Map<String, Set<Long>> = emptyMap(),
    ): Map<EnergyLevel, Int> {
        val resolved = resolve(tracks, energies, pools, playlistMembers)
        return EnergyLevel.entries.associateWith { level ->
            resolved.count { levelOf(it.band) == level }
        }
    }

    /**
     * Band -> the level a user would call it (plan §3.1: Chill = 1–2, Mid = 3,
     * Hype = 4–5). The engine keeps the full 1..5 range; this is only for display and
     * for grouping in screens C1 and C4.
     */
    fun levelOf(band: Int): EnergyLevel = when {
        band <= 2 -> EnergyLevel.CHILL
        band == 3 -> EnergyLevel.MID
        else -> EnergyLevel.HYPE
    }

    private class PoolLevels(
        val byFolder: Map<String, EnergyLevel>,
        val byAlbum: Map<String, EnergyLevel>,
        val byTrackId: Map<Long, EnergyLevel>,
    ) {
        /**
         * A track can sit in a pooled folder and a pooled album at once. Playlists are
         * the most deliberate choice a user makes, then albums, then the folder the file
         * happens to live in.
         */
        fun levelFor(track: PoolKeys): EnergyLevel? =
            byTrackId[track.id]
                ?: byAlbum[track.albumId.toString()]
                ?: byFolder[track.bucketId.toString()]
    }

    private fun poolLevels(
        pools: List<PoolSourceEntity>,
        playlistMembers: Map<String, Set<Long>>,
    ): PoolLevels {
        val byTrackId = mutableMapOf<Long, EnergyLevel>()
        pools.filter { it.kind == PoolSourceKind.PLAYLIST }.forEach { source ->
            playlistMembers[source.key]?.forEach { audioId -> byTrackId[audioId] = source.level }
        }
        return PoolLevels(
            byFolder = pools.filter { it.kind == PoolSourceKind.FOLDER }
                .associate { it.key to it.level },
            byAlbum = pools.filter { it.kind == PoolSourceKind.ALBUM }
                .associate { it.key to it.level },
            byTrackId = byTrackId,
        )
    }
}
