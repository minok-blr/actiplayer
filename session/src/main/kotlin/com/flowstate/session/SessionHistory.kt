package com.flowstate.session

import com.flowstate.data.db.SessionDao
import com.flowstate.data.db.SessionEntity
import com.flowstate.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Read side of recorded sessions: the History list (D1) and one recap (B5). */
@Singleton
class SessionHistory @Inject constructor(
    private val dao: SessionDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    val sessions: StateFlow<List<SessionEntity>> =
        dao.observeFinished().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latest: Flow<SessionEntity?> = dao.observeLatest()

    /** Session plus its computed recap, or null if it has been deleted meanwhile. */
    suspend fun recap(id: Long): Pair<SessionEntity, SessionRecap>? {
        val session = dao.session(id) ?: return null
        return session to RecapBuilder.build(dao.events(id), session.durationMs)
    }

    fun delete(id: Long) {
        scope.launch { dao.delete(id) }
    }
}
