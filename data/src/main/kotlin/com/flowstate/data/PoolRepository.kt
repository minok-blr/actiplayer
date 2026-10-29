package com.flowstate.data

import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.PoolDao
import com.flowstate.data.db.PoolSourceEntity
import com.flowstate.data.db.PoolSourceKind
import com.flowstate.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The three energy pools (plan §3.1) — which folders, albums and playlists feed Hype,
 * Mid and Chill.
 *
 * A source belongs to exactly one pool, so adding it somewhere it already lives is a
 * move, not a duplicate. That is what screen C2's "IN MID" badge and screen C4's move
 * buttons both promise.
 */
@Singleton
class PoolRepository @Inject constructor(
    private val dao: PoolDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    val sources: StateFlow<List<PoolSourceEntity>> =
        dao.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(level: EnergyLevel, sources: List<SourceSummary>) {
        if (sources.isEmpty()) return
        val now = System.currentTimeMillis()
        scope.launch {
            dao.upsert(
                sources.map {
                    PoolSourceEntity(it.kind, it.key, level, it.label, now)
                },
            )
        }
    }

    fun move(source: PoolSourceEntity, level: EnergyLevel) {
        scope.launch { dao.upsert(listOf(source.copy(level = level))) }
    }

    fun remove(kind: PoolSourceKind, key: String) {
        scope.launch { dao.remove(kind, key) }
    }

    /** Consistent read for session start; see [RatingsRepository.snapshot]. */
    suspend fun snapshot(): List<PoolSourceEntity> = dao.all()
}
