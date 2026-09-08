package com.flowstate.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun trackSource(value: TrackSource): String = value.name
    @TypeConverter fun toTrackSource(value: String): TrackSource = TrackSource.valueOf(value)
    @TypeConverter fun energySource(value: EnergySource): String = value.name
    @TypeConverter fun toEnergySource(value: String): EnergySource = EnergySource.valueOf(value)
}

/**
 * Version 1 carries tracks + energies only. Pools arrive in phase 2 and sessions in
 * phase 4, each as a numbered migration against the schema exported under `data/schemas`.
 */
@Database(
    entities = [TrackEntity::class, TrackEnergyEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ActiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "actiplayer.db"
    }
}
