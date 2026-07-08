package com.flowstate.app.audio

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.flowstate.app.FlowStateApp

/**
 * Media3 session service: wraps the app-scoped ExoPlayer in a MediaSession, which gives
 * us the media notification, lockscreen controls, and headset/media-button routing.
 *
 * Media3 promotes this service to the foreground while playback is active (declared with
 * mediaPlayback|location types in the manifest), which is also what keeps the sensor
 * pipeline and GPS alive with the screen off — the phone rides in a zipped pocket.
 *
 * The player itself is owned by AppContainer, so onDestroy releases only the session.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = (application as FlowStateApp).container.player
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
