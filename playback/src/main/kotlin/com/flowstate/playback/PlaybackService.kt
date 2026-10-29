package com.flowstate.playback

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
 * [LocalSource] alternates between two players to crossfade, so the session follows
 * whichever one is currently audible — otherwise the notification and lockscreen would
 * show the outgoing track for the length of every fade. The players are owned by
 * LocalSource, so onDestroy releases only the session.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var localSource: LocalSource

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class) // DefaultMediaNotificationProvider's hooks are unstable API
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(ModeNotificationProvider(this))
        mediaSession = MediaSession.Builder(this, localSource.sessionPlayer.value).build()
            // Media3 learns about a session when a MediaController connects to it — and
            // nothing in this app ever connects one. Unregistered, the session never got
            // a notification and the service never went to the foreground, so with the
            // screen off Android stopped the accelerometer, throttled GPS and was free to
            // kill the process: the pocket, which is the whole product, did not work.
            // (Earlier tests ran on a charger with the screen held on, which hid it.)
            .also { addSession(it) }
        serviceScope.launch {
            // drop(1): the session was just built with the current value.
            localSource.sessionPlayer.drop(1).collect { mediaSession?.setPlayer(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SENSOR_FOREGROUND) promoteForSensors()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun promoteForSensors() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SENSOR_CHANNEL, "Session", NotificationManager.IMPORTANCE_LOW),
        )
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, SENSOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_actiplayer)
            .setContentTitle("ActiPlayer session")
            .setContentText("Reading sensors — music plays through Spotify")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        startForeground(SENSOR_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * This service only runs while a session does, and a session is the sensors as much as
     * the music — so it stays in the foreground even while paused, where Media3 would
     * otherwise drop it after a timeout and let the accelerometer stop.
     *
     * Media3 starts the foreground with the media-playback type only; the location type is
     * added straight after, while the app is still in front, because Android grants
     * while-in-use location to a foreground service only if it asked while visible.
     */
    @OptIn(UnstableApi::class)
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void?> {
        val update = super.onUpdateNotificationAsync(session, /* startInForegroundRequired= */ true)
        update.addListener(::addLocationType, ContextCompat.getMainExecutor(this))
        return update
    }

    @OptIn(UnstableApi::class)
    private fun addLocationType() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val posted = getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID }
            ?: return
        runCatching {
            ServiceCompat.startForeground(
                this,
                posted.id,
                posted.notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /**
     * The standard media notification (plan §1: standard controls only), with two changes:
     * our own mark in the status bar instead of Media3's generic note, and the mode the
     * track was picked for after the artist — "Architects · RIDING" — which is the one
     * thing this notification can say that any other player's cannot.
     */
    @OptIn(UnstableApi::class)
    private class ModeNotificationProvider(service: MediaSessionService) :
        DefaultMediaNotificationProvider(service) {

        init {
            setSmallIcon(R.drawable.ic_stat_actiplayer)
        }

        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
            val parts = listOfNotNull(metadata.artist, metadata.subtitle)
                .filter { it.isNotBlank() }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
    }

    companion object {
        const val ACTION_SENSOR_FOREGROUND = "com.flowstate.playback.action.SENSOR_FOREGROUND"
        private const val SENSOR_CHANNEL = "session"
        private const val SENSOR_NOTIFICATION_ID = 2
    }
}
