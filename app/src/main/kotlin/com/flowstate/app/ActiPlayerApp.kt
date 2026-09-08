package com.flowstate.app

import android.app.Application
import com.flowstate.data.LegacyRatingsImport
import com.flowstate.data.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class ActiPlayerApp : Application() {

    @Inject lateinit var legacyRatingsImport: LegacyRatingsImport

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Pre-Room ratings live in SharedPreferences on devices that ran the old build.
        // One-shot, flag-guarded, and cheap when there is nothing to import.
        appScope.launch { legacyRatingsImport.runIfNeeded() }
    }
}
