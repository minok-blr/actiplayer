package com.flowstate.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Upsert
    suspend fun upsertEnergies(energies: List<TrackEnergyEntity>)

    @Query("SELECT * FROM track_energy")
    fun observeEnergies(): Flow<List<TrackEnergyEntity>>

    @Query("SELECT * FROM track_energy")
    suspend fun energies(): List<TrackEnergyEntity>

    @Query("SELECT * FROM tracks WHERE source = :source")
    fun observeTracks(source: TrackSource): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source")
    suspend fun tracks(source: TrackSource): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM track_energy")
    suspend fun energyCount(): Int
}
