package com.flowstate.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Insert
    suspend fun insertEvents(events: List<SessionEventEntity>)

    /** Finished sessions only — an abandoned one has nothing to show. */
    @Query("SELECT * FROM sessions WHERE endedAtEpochMs IS NOT NULL ORDER BY startedAtEpochMs DESC")
    fun observeFinished(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE endedAtEpochMs IS NOT NULL ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Query("SELECT * FROM session_events WHERE sessionId = :id ORDER BY tMs")
    suspend fun events(id: Long): List<SessionEventEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /** A session that never produced a single event is noise, not history. */
    @Query(
        "DELETE FROM sessions WHERE endedAtEpochMs IS NULL " +
            "OR id NOT IN (SELECT DISTINCT sessionId FROM session_events)",
    )
    suspend fun pruneEmpty()
}
