package com.flowstate.data

import com.flowstate.data.db.EnergySource
import com.flowstate.data.db.TrackDao
import com.flowstate.data.db.TrackEnergyEntity
import com.flowstate.data.db.TrackEntity
import com.flowstate.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the user has explicitly said about local tracks: a band, or "never play this".
 * Both beat pool membership, which is why they travel together.
 */
data class LocalEnergies(
    val ratings: Map<Long, Int> = emptyMap(),
    val excluded: Set<Long> = emptySet(),
)

/**
 * Energy ratings for local (MediaStore) tracks, 1 (couch) .. 5 (send it), on Room.
 *
 * Writes are fire-and-forget on the application scope so the rating controls stay
 * ordinary click handlers; the observable map updates when Room says the row landed.
 * Unrated tracks are treated as middle energy ([UNRATED_DEFAULT]), so the app works
 * before the user has rated anything.
 */
@Singleton
class RatingsRepository @Inject constructor(
    private val dao: TrackDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /** mediaStoreId -> band, for every locally rated track. Excluded tracks are omitted. */
    val ratings: StateFlow<Map<Long, Int>> = dao.observeEnergies()
        .map { rows -> rows.toLocalRatingMap() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** mediaStoreIds the user excluded from every pool. */
    val excluded: StateFlow<Set<Long>> = dao.observeEnergies()
        .map { rows -> rows.toExcludedIds() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setRating(mediaStoreId: Long, rating: Int) = setBands(listOf(mediaStoreId), rating)

    /** Band 1..5, or null to exclude the track from every pool (screen C4). */
    fun setBands(mediaStoreIds: List<Long>, band: Int?) {
        if (mediaStoreIds.isEmpty()) return
        val clamped = band?.coerceIn(1, 5)
        val now = System.currentTimeMillis()
        scope.launch {
            dao.upsertEnergies(
                mediaStoreIds.map {
                    TrackEnergyEntity(TrackEntity.localId(it), clamped, EnergySource.USER, now)
                },
            )
        }
    }

    /** Back to unsorted: forget what the user said and let pools decide again. */
    fun clear(mediaStoreIds: List<Long>) {
        if (mediaStoreIds.isEmpty()) return
        scope.launch { dao.deleteEnergies(mediaStoreIds.map { TrackEntity.localId(it) }) }
    }

    /**
     * A consistent read for session start, straight from the database rather than the
     * observable cache — a session must never begin with a half-populated pool.
     */
    suspend fun snapshot(): LocalEnergies = dao.energies().let {
        LocalEnergies(it.toLocalRatingMap(), it.toExcludedIds())
    }

    companion object {
        const val UNRATED_DEFAULT = 3
        private const val LOCAL_PREFIX = "local:"

        /**
         * Energy rows -> mediaStoreId/band map. Spotify rows and excluded tracks drop
         * out; everything else must survive, because this is the only path a user's
         * ratings take from the database back to the library screen and the session pool.
         */
        internal fun List<TrackEnergyEntity>.toLocalRatingMap(): Map<Long, Int> =
            mapNotNull { row ->
                val band = row.band ?: return@mapNotNull null // excluded
                val id = row.localId() ?: return@mapNotNull null
                id to band
            }.toMap()

        /** Rows with no band are the user saying "never play this" (plan §3.1 EXCLUDED). */
        internal fun List<TrackEnergyEntity>.toExcludedIds(): Set<Long> =
            mapNotNullTo(mutableSetOf()) { row ->
                if (row.band != null) null else row.localId()
            }

        private fun TrackEnergyEntity.localId(): Long? =
            if (!trackId.startsWith(LOCAL_PREFIX)) {
                null
            } else {
                trackId.removePrefix(LOCAL_PREFIX).toLongOrNull()
            }
    }
}
