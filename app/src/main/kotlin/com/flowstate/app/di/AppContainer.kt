package com.flowstate.app.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.flowstate.app.ble.HeartRateMonitor
import com.flowstate.app.data.MediaLibrary
import com.flowstate.app.data.RatingsStore
import com.flowstate.app.data.SettingsStore
import com.flowstate.app.session.SessionCoordinator

/**
 * Hand-rolled dependency graph. The build brief calls for Hilt; the MVP deliberately
 * avoids annotation processing to maximise first-build reliability (see DECISIONS.md).
 *
 * The ExoPlayer is app-scoped and shared by the UI, the QueueController and the
 * PlaybackService's MediaSession. Single-process, main-thread access only.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // headphones yanked out -> pause
            .build()
    }

    val mediaLibrary = MediaLibrary(appContext)
    val ratingsStore = RatingsStore(appContext)
    val settingsStore = SettingsStore(appContext)
    val heartRateMonitor = HeartRateMonitor(appContext)

    val sessionCoordinator: SessionCoordinator by lazy {
        SessionCoordinator(
            appContext, player, mediaLibrary, ratingsStore, heartRateMonitor, settingsStore,
        )
    }
}
