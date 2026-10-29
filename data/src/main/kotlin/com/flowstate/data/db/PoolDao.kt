package com.flowstate.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PoolDao {

    @Upsert
    suspend fun upsert(sources: List<PoolSourceEntity>)

    @Query("DELETE FROM pool_sources WHERE kind = :kind AND `key` = :key")
    suspend fun remove(kind: PoolSourceKind, key: String)

    @Query("SELECT * FROM pool_sources ORDER BY addedAt")
    fun observeAll(): Flow<List<PoolSourceEntity>>

    @Query("SELECT * FROM pool_sources ORDER BY addedAt")
    suspend fun all(): List<PoolSourceEntity>
}
