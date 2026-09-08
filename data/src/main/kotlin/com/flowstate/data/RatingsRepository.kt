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

    fun setRating(mediaStoreId: Long, rating: Int) {
        val clamped = rating.coerceIn(1, 5)
        scope.launch {
            dao.upsertEnergies(
                listOf(
                    TrackEnergyEntity(
                        trackId = TrackEntity.localId(mediaStoreId),
                        band = clamped,
                        source = EnergySource.USER,
                        updatedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    /**
     * A consistent read for session start, straight from the database rather than the
     * observable cache — a session must never begin with a half-populated pool.
     */
    suspend fun snapshot(): Map<Long, Int> = dao.energies().toLocalRatingMap()

    companion object {
        const val UNRATED_DEFAULT = 3
        private const val LOCAL_PREFIX = "local:"

        fun effectiveRating(ratings: Map<Long, Int>, mediaStoreId: Long): Int =
            ratings[mediaStoreId] ?: UNRATED_DEFAULT

        /**
         * Energy rows -> mediaStoreId/band map. Spotify rows and excluded tracks drop
         * out; everything else must survive, because this is the only path a user's
         * ratings take from the database back to the library screen and the session pool.
         */
        internal fun List<TrackEnergyEntity>.toLocalRatingMap(): Map<Long, Int> =
            mapNotNull { row ->
                val band = row.band ?: return@mapNotNull null // excluded
                if (!row.trackId.startsWith(LOCAL_PREFIX)) return@mapNotNull null
                val id = row.trackId.removePrefix(LOCAL_PREFIX).toLongOrNull()
                    ?: return@mapNotNull null
                id to band
            }.toMap()
    }
}
