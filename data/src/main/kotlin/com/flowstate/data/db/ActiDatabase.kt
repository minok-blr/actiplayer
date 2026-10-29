package com.flowstate.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.flowstate.data.SourceKind
import com.flowstate.engine.Activity
import com.flowstate.engine.RideState

class Converters {
    @TypeConverter fun trackSource(value: TrackSource): String = value.name
    @TypeConverter fun toTrackSource(value: String): TrackSource = TrackSource.valueOf(value)
    @TypeConverter fun energySource(value: EnergySource): String = value.name
    @TypeConverter fun toEnergySource(value: String): EnergySource = EnergySource.valueOf(value)
    @TypeConverter fun energyLevel(value: EnergyLevel): String = value.name
    @TypeConverter fun toEnergyLevel(value: String): EnergyLevel = EnergyLevel.valueOf(value)
    @TypeConverter fun poolSourceKind(value: PoolSourceKind): String = value.name
    @TypeConverter fun toPoolSourceKind(value: String): PoolSourceKind = PoolSourceKind.valueOf(value)
    @TypeConverter fun activity(value: Activity): String = value.name
    @TypeConverter fun toActivity(value: String): Activity = Activity.valueOf(value)
    @TypeConverter fun sourceKind(value: SourceKind): String = value.name
    @TypeConverter fun toSourceKind(value: String): SourceKind = SourceKind.valueOf(value)
    @TypeConverter fun rideState(value: RideState?): String? = value?.name
    @TypeConverter fun toRideState(value: String?): RideState? = value?.let { RideState.valueOf(it) }
    @TypeConverter fun eventKind(value: SessionEventKind): String = value.name
    @TypeConverter fun toEventKind(value: String): SessionEventKind = SessionEventKind.valueOf(value)
}

/**
 * v1 tracks + energies; v2 adds pool sources; v3 adds recorded sessions and their
 * events; v4 adds the track id to those events, for cover art in the recap. Every step so far is a pure table addition, so Room generates and validates
 * the migrations itself against the schemas exported under `data/schemas`.
 */
@Database(
    entities = [
        TrackEntity::class,
        TrackEnergyEntity::class,
        PoolSourceEntity::class,
        SessionEntity::class,
        SessionEventEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
abstract class ActiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun poolDao(): PoolDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val NAME = "actiplayer.db"
    }
}
