package com.flowstate.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media3 session service: wraps the app-scoped ExoPlayer in a MediaSession, which gives
 * us the media notification, lockscreen controls, and headset/media-button routing.
 *
 * Media3 promotes this service to the foreground while playback is active (declared with
 * mediaPlayback|location types in the manifest), which is also what keeps the sensor
 * pipeline and GPS alive with the screen off — the phone rides in a zipped pocket.
 *
 * Spotify-source sessions play no local audio, so Media3 never promotes us; for those the
 * coordinator starts the service with [ACTION_SENSOR_FOREGROUND] and we take the
 * location-type foreground ourselves — same pocket guarantee, no media notification.
 *
 * The player itself is an application-scoped Hilt singleton, so onDestroy releases only
 * the session.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var player: ExoPlayer

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SENSOR_FOREGROUND) promoteForSensors()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun promoteForSensors() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SENSOR_CHANNEL, "Riding session", NotificationManager.IMPORTANCE_LOW),
        )
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, SENSOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ActiPlayer session")
            .setContentText("Reading sensors — music plays through Spotify")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        startForeground(SENSOR_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SENSOR_FOREGROUND = "com.flowstate.playback.action.SENSOR_FOREGROUND"
        private const val SENSOR_CHANNEL = "session"
        private const val SENSOR_NOTIFICATION_ID = 2
    }
}
