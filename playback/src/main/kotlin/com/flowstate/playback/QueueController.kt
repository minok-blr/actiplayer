package com.flowstate.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.flowstate.data.Track
import com.flowstate.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Picks what plays next from the user's energy ratings (build brief §8, simplified):
 *
 *  - candidates = tracks rated within the target band, widened by ±1 when the pool is thin
 *  - no repeats within the last [RECENT_LIMIT] tracks
 *  - a track already playing that fits the new band keeps playing (no needless swap)
 *  - single-player volume-ramp "crossfade": fast cut when energy goes UP (the drop-in must
 *    hit), gentle fade when it goes DOWN (getting on the lift should feel like an exhale)
 *
 * The full brief's dual-player preloaded-standby crossfade replaces the ramp later.
 */
@Singleton
class QueueController @Inject constructor(
    private val player: ExoPlayer,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PlaybackBackend {
    data class RatedTrack(val track: Track, val rating: Int)

    private var pool: List<RatedTrack> = emptyList()
    private val recentIds = ArrayDeque<Long>()
    private var band: IntRange = 2..3
    private var fadeJob: Job? = null
    private val random = Random(System.currentTimeMillis())

    private val _nowPlaying = MutableStateFlow<RatedTrack?>(null)
    val nowPlaying: StateFlow<RatedTrack?> = _nowPlaying

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) advance(fadeOutMs = 0)
        }
    }

    init {
        player.addListener(listener)
    }

    fun setPool(tracks: List<RatedTrack>) {
        pool = tracks
    }

    /** Called on session start (force = true) and whenever the effective band changes. */
    override fun onBand(newBand: IntRange, energyWentUp: Boolean, force: Boolean) {
        val changed = newBand != band
        band = newBand
        if (!force && !changed) return
        val current = _nowPlaying.value
        if (!force && current != null && current.rating in band) return
        advance(fadeOutMs = if (energyWentUp) 250 else 1_500)
    }

    override fun skip() = advance(fadeOutMs = 150)

    override fun stop() {
        fadeJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _nowPlaying.value = null
        recentIds.clear()
    }

    private fun advance(fadeOutMs: Long) {
        val next = pick() ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            if (fadeOutMs > 0 && player.isPlaying) rampVolume(player.volume, 0f, fadeOutMs)
            player.setMediaItem(next.track.toMediaItem())
            player.prepare()
            player.play()
            _nowPlaying.value = next
            rememberPlayed(next.track.id)
            rampVolume(0f, 1f, 400)
        }
    }

    private fun pick(): RatedTrack? {
        if (pool.isEmpty()) return null
        fun candidates(b: IntRange) = pool.filter { it.rating in b && it.track.id !in recentIds }
        var pot = candidates(band)
        if (pot.size < MIN_POOL) {
            pot = candidates((band.first - 1).coerceAtLeast(1)..(band.last + 1).coerceAtMost(5))
        }
        if (pot.isEmpty()) pot = pool.filter { it.track.id !in recentIds }
        if (pot.isEmpty()) pot = pool
        return pot[random.nextInt(pot.size)]
    }

    private fun rememberPlayed(id: Long) {
        recentIds.addLast(id)
        while (recentIds.size > RECENT_LIMIT) recentIds.removeFirst()
    }

    private suspend fun rampVolume(from: Float, to: Float, durationMs: Long) {
        val steps = 20
        for (i in 1..steps) {
            player.volume = from + (to - from) * i / steps
            delay(durationMs / steps)
        }
        player.volume = to
    }

    private fun Track.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build(),
            )
            .build()

    private companion object {
        const val MIN_POOL = 8
        const val RECENT_LIMIT = 10
    }
}
