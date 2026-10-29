package com.flowstate.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import com.flowstate.data.SettingsStore
import com.flowstate.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A track in the session pool, already resolved to an energy band. */
data class LocalTrack(
    override val id: Long,
    override val band: Int,
    val uri: Uri,
    val title: String,
    val artist: String,
) : Banded

/**
 * Local playback with a real crossfade (plan §3.2).
 *
 * Two ExoPlayers alternate: one plays, the other holds a preloaded standby track from the
 * band the athlete is most likely to jump to next, so an up-switch starts on the beat
 * instead of waiting on a decode. Down-switches and track ends get the full crossfade
 * from settings (1–3 s); up-switches use [UP_SWITCH_MS], because the drop-in has to land.
 *
 * **Audio focus is managed here, not by the players.** Two ExoPlayers each handling their
 * own focus would fight: the second one's request makes the first lose focus and pause,
 * which is exactly the moment a crossfade needs both of them audible. So both players are
 * built with `handleAudioFocus = false` and this class owns one focus request for the
 * pair, plus the becoming-noisy receiver.
 *
 * **A pause belongs to the listener.** Whether it came from the session screen, the
 * notification, a headset button or another app taking the audio, a paused source stays
 * paused until the listener resumes it: mode changes and skips cue the right track
 * silently instead of starting it. Before this, pausing from the notification lasted only
 * until the next mode switch, which then started the music again on its own.
 */
@Singleton
class LocalSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val settings: SettingsStore,
) : MusicSource {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val players: List<ExoPlayer> = List(2) { buildPlayer() }
    private var activeIndex = 0

    private val active: ExoPlayer get() = players[activeIndex]
    private val standby: ExoPlayer get() = players[1 - activeIndex]

    /**
     * What the MediaSession wraps — one facade per player, so the notification, the
     * lockscreen, headset buttons and car controls reach this class rather than an
     * ExoPlayer behind its back. See [SessionFacade].
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private val facades: List<Player> = players.map { SessionFacade(it) }
    private val _sessionPlayer = MutableStateFlow(facades[0])
    val sessionPlayer: StateFlow<Player> = _sessionPlayer

    private val _paused = MutableStateFlow(false)
    override val paused: StateFlow<Boolean> = _paused

    /**
     * The mode as the athlete would name it ("RIDING"), set by the session each decision
     * and carried into every new track's metadata — plan §1 wants it in the notification.
     */
    var modeLabel: String? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _availability = MutableStateFlow(Availability.UNAVAILABLE)
    override val availability: StateFlow<Availability> = _availability

    private var pool: List<LocalTrack> = emptyList()
    private val recent = ArrayDeque<Long>()
    private var band: IntRange = 2..3
    private var current: LocalTrack? = null
    private var standbyTrack: LocalTrack? = null
    private var fadeJob: Job? = null
    private var endWatchJob: Job? = null
    private var hasFocus = false
    /** Paused by a phone call or a navigation prompt, not by the listener: resume on gain. */
    private var pausedForTransientLoss = false
    private var noisyRegistered = false
    private val random = Random(System.currentTimeMillis())

    private val endListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // Only the audible player ending should advance the queue; the one fading out
            // reaches STATE_ENDED too if a crossfade outlives its tail.
            if (state == Player.STATE_ENDED && active.playbackState == Player.STATE_ENDED) {
                advance(fast = false, force = true)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Headphones unplugged or a Bluetooth headset dropped: by default the music
            // must not carry on out of the phone's speaker. A setting lets it.
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                settings.pauseOnDisconnect.value
            ) {
                pause()
            }
        }
    }

    init {
        players.forEach { it.addListener(endListener) }
    }

    fun setPool(tracks: List<LocalTrack>) {
        pool = tracks
        _availability.value = if (tracks.isEmpty()) Availability.UNAVAILABLE else Availability.READY
    }

    override fun play(band: IntRange, fast: Boolean, force: Boolean) {
        val changed = band != this.band
        this.band = band
        if (!force && !changed) return
        // A track that already fits the new band keeps playing — no needless swap.
        if (!force && current?.band?.let { it in band } == true) {
            preloadStandby()
            return
        }
        advance(fast = fast, force = force)
    }

    override fun skip() = advance(fast = true, force = true)

    override fun pause() {
        finishFadeNow()
        pauseAll()
        pausedForTransientLoss = false
        _paused.value = true
    }

    override fun resume() {
        _paused.value = false
        if (current == null) return
        if (!requestFocus()) return
        active.volume = 1f
        active.play()
    }

    override fun progress(): Float? {
        val duration = active.duration
        if (duration <= 0L) return null
        return (active.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    }

    override fun stop() {
        fadeJob?.cancel()
        endWatchJob?.cancel()
        endWatchJob = null
        _paused.value = false
        pausedForTransientLoss = false
        players.forEach {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
        abandonFocus()
        current = null
        standbyTrack = null
        recent.clear()
        _nowPlaying.value = null
    }

    private fun advance(fast: Boolean, force: Boolean) {
        val next = preferredStandby() ?: TrackPicker.pick(pool, band, recent, random) ?: return
        val fadeMs = if (fast) UP_SWITCH_MS else settings.crossfadeMs.value.toLong()
        crossfadeTo(next, if (force && current == null) 0L else fadeMs)
    }

    /**
     * If the standby player is already warm on a track that fits the band we are switching
     * to, use it — that is the whole reason it was preloaded. This serves down-switches as
     * much as up-switches: the standby holds the opposite band, which is where a
     * down-switch is going.
     */
    private fun preferredStandby(): LocalTrack? =
        standbyTrack?.takeIf { it.band in band && it.id !in recent }

    private fun crossfadeTo(next: LocalTrack, fadeMs: Long) {
        fadeJob?.cancel()
        if (_paused.value) {
            cue(next)
            return
        }
        if (!requestFocus()) return

        val outgoing = active
        val incoming = standby
        if (incoming.currentMediaItem?.mediaId != next.id.toString()) {
            incoming.setMediaItem(next.toMediaItem())
            incoming.prepare()
        }
        incoming.volume = 0f
        incoming.play()
        watchForTrackEnd()

        // The session follows the incoming track immediately: it is what the listener is
        // about to hear, so the notification must not lag a crossfade behind.
        activeIndex = 1 - activeIndex
        _sessionPlayer.value = facades[activeIndex]
        current = next
        standbyTrack = null
        remember(next.id)
        _nowPlaying.value =
            NowPlaying(next.title, next.artist, next.band, SOURCE_NAME, next.id)

        fadeJob = scope.launch {
            if (fadeMs > 0) {
                val steps = (fadeMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
                for (step in 1..steps) {
                    val t = step.toFloat() / steps
                    incoming.volume = t
                    outgoing.volume = 1f - t
                    delay(FADE_STEP_MS)
                }
            }
            incoming.volume = 1f
            outgoing.stop()
            outgoing.clearMediaItems()
            outgoing.volume = 1f
            preloadStandby()
        }
    }

    /**
     * While paused, a mode change or a skip still decides what plays next — it just does
     * not start it. The listener resumes into the track that fits the moment, not the one
     * that was on when they paused.
     */
    private fun cue(next: LocalTrack) {
        val outgoing = active
        val incoming = standby
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f
        if (incoming.currentMediaItem?.mediaId != next.id.toString()) {
            incoming.setMediaItem(next.toMediaItem())
            incoming.prepare()
        }
        incoming.pause()
        incoming.volume = 1f
        activeIndex = 1 - activeIndex
        _sessionPlayer.value = facades[activeIndex]
        current = next
        standbyTrack = null
        remember(next.id)
        _nowPlaying.value = NowPlaying(next.title, next.artist, next.band, SOURCE_NAME, next.id)
    }

    /** Pausing mid-fade completes the swap at once rather than freezing two half-volumes. */
    private fun finishFadeNow() {
        if (fadeJob?.isActive != true) return
        fadeJob?.cancel()
        standby.stop()
        standby.clearMediaItems()
        standby.volume = 1f
        active.volume = 1f
    }

    /**
     * Starts the next track [crossfade][SettingsStore.crossfadeMs] before this one ends, so
     * track changes overlap the way plan §1 describes. Waiting for the player to report
     * STATE_ENDED — as this first did — gave a gap of silence and then a fade *in*, which
     * is not a crossfade at all. [endListener] stays as the fallback for tracks too short
     * to overlap.
     */
    private fun watchForTrackEnd() {
        if (endWatchJob?.isActive == true) return
        endWatchJob = scope.launch {
            while (isActive) {
                delay(END_POLL_MS)
                if (current == null || _paused.value || fadeJob?.isActive == true) continue
                val player = active
                val duration = player.duration
                if (duration <= 0L || !player.isPlaying) continue
                val remaining = duration - player.currentPosition
                if (remaining <= settings.crossfadeMs.value + END_LEAD_MS) {
                    advance(fast = false, force = true)
                }
            }
        }
    }

    /**
     * Warm the idle player on a track from the band the athlete is most likely to jump
     * to, paused at 0:00, so the next up-switch is a volume ramp rather than a decode.
     */
    private fun preloadStandby() {
        val target = TrackPicker.standbyBand(band)
        val candidate = TrackPicker.pick(pool, target, recent + listOfNotNull(current?.id), random)
            ?: return
        if (candidate.id == standbyTrack?.id) return
        standbyTrack = candidate
        standby.setMediaItem(candidate.toMediaItem())
        standby.prepare()
        standby.volume = 0f
        standby.pause()
    }

    private fun remember(id: Long) {
        recent.addLast(id)
        while (recent.size > TrackPicker.RECENT_LIMIT) recent.removeFirst()
    }

    // ------------------------------------------------------------------ audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Another app started playing for real. Treat it as the listener pausing:
            // grabbing the audio back at the next mode change would hijack whatever they
            // chose to listen to instead.
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
                abandonFocus()
            }
            // A call or a navigation prompt: step aside and come back afterwards — but
            // only if the listener had not paused already.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!_paused.value) {
                    finishFadeNow()
                    pauseAll()
                    pausedForTransientLoss = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                players.forEach { it.volume = it.volume * DUCK }
            AudioManager.AUDIOFOCUS_GAIN -> {
                active.volume = 1f
                if (pausedForTransientLoss && !_paused.value && current != null) active.play()
                pausedForTransientLoss = false
            }
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val granted = audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            hasFocus = true
            registerNoisy()
        }
        return granted
    }

    private fun abandonFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun pauseAll() = players.forEach { it.pause() }

    // ------------------------------------------------------------------ plumbing

    // setLooper is @UnstableApi. Pinning both players to the main looper is the point:
    // the crossfade drives two players from one coroutine and must not race them.
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .build()
            .apply { volume = 1f }

    private fun LocalTrack.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifEmpty { null })
                    // The notification shows this after the artist (see PlaybackService):
                    // the mode the track was picked for, as plan §1 asks.
                    .setSubtitle(modeLabel)
                    .build(),
            )
            .build()

    /**
     * What controllers outside the app see. Play, pause and next are routed through this
     * class so its own idea of "paused" stays the truth; everything else passes straight
     * to the player.
     *
     * Next is advertised even though each player only ever holds one item — ExoPlayer
     * would otherwise report no next command, so the notification showed no skip button
     * and a headset double-press did nothing. Here it means what skip means on screen.
     */
    // ForwardingPlayer is @UnstableApi in Media3 1.11; it is also the documented way to
    // customise what a MediaSession's controllers can do, so the opt-in is deliberate.
    @androidx.annotation.OptIn(UnstableApi::class)
    private inner class SessionFacade(player: ExoPlayer) : ForwardingPlayer(player) {
        override fun play() = resume()

        override fun pause() = this@LocalSource.pause()

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) resume() else this@LocalSource.pause()
        }

        override fun seekToNext() = skip()

        override fun seekToNextMediaItem() = skip()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*EXTRA_COMMANDS).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command in EXTRA_COMMANDS || super.isCommandAvailable(command)
    }

    private companion object {
        const val SOURCE_NAME = "Local"

        val EXTRA_COMMANDS = intArrayOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        /** How often to check for the start of the end-of-track overlap. */
        const val END_POLL_MS = 250L

        /** Start a little early so a decode of the next track fits inside the fade. */
        const val END_LEAD_MS = 300L

        /** Up-switch cut. Plan §3.2: fast enough that the drop-in still lands. */
        const val UP_SWITCH_MS = 300L
        const val FADE_STEP_MS = 50L
        const val DUCK = 0.3f
    }
}
