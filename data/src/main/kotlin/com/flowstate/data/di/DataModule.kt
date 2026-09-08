package com.flowstate.data.di

import android.content.Context
import androidx.room.Room
import com.flowstate.data.db.ActiDatabase
import com.flowstate.data.db.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ActiDatabase =
        Room.databaseBuilder(context, ActiDatabase::class.java, ActiDatabase.NAME).build()

    @Provides
    fun trackDao(database: ActiDatabase): TrackDao = database.trackDao()

    /**
     * Main-dispatcher application scope. Sensors, engine and player commands are
     * single-threaded by design (DECISIONS.md: "Everything on the main dispatcher"); the
     * repositories suspend into Room's own executors from here rather than adding a
     * second thread of their own.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
