package com.flowstate.playback

import com.flowstate.data.RatedSpotifyTrack
import com.flowstate.data.di.ApplicationScope
import com.flowstate.playback.spotify.SpotifyRemote
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Spotify session backend (Premium required): the engine's band changes become one
 * Web API call each — "replace what the Spotify app is playing with this batch of tracks
 * rated in the band". Spotify advances through the batch on its own, so steady riding
 * costs zero network traffic; only band changes and a slow now-playing poll talk to the
 * API. Transitions are hard cuts — the Connect API has no volume ramp, so the local
 * backend's asymmetric fades don't exist here (logged limitation, DECISIONS.md).
 *
 * The pool is only tracks the user rated in the Remote tab: Spotify has no enumerable
 * "whole library" to default to 3 the way MediaStore does.
 */
@Singleton
class SpotifyQueueController @Inject constructor(
    private val remote: SpotifyRemote,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PlaybackBackend {

    private var pool: List<RatedSpotifyTrack> = emptyList()
    private var band: IntRange = 2..3
    private val random = Random(System.currentTimeMillis())

    /** "Title — Artist" polled from Spotify (what's ACTUALLY playing, not what we sent). */
    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying

    /** Last player-control failure, shown on the Ride Board ("needs Premium" lands here). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var pollJob: Job? = null
    private var sendJob: Job? = null

    fun setPool(tracks: List<RatedSpotifyTrack>) {
        pool = tracks
    }

    /** Starts the now-playing poll; call at session start, paired with [stop]. */
    fun start() {
        _error.value = null
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                runCatching { remote.currentlyPlayingLabel() }
                    .onSuccess { _nowPlaying.value = it ?: _nowPlaying.value }
            }
        }
    }

    override fun onBand(newBand: IntRange, energyWentUp: Boolean, force: Boolean) {
        val changed = newBand != band
        band = newBand
        if (!force && !changed) return
        sendBatch()
    }

    override fun skip() = sendBatch()

    override fun stop() {
        sendJob?.cancel()
        pollJob?.cancel()
        scope.launch { remote.pausePlayback() }
        _nowPlaying.value = null
    }

    private fun sendBatch() {
        val batch = batchFor(pool, band, random)
        if (batch.isEmpty()) {
            _error.value = "No rated Spotify tracks — rate some in the Remote tab."
            return
        }
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                remote.playUris(batch.map { it.uri })
                _error.value = null
                // Optimistic; the poll corrects it as Spotify advances.
                _nowPlaying.value = "${batch.first().title} — ${batch.first().artist}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    companion object {
        const val POLL_MS = 15_000L
        const val MIN_POOL = 8
        const val MAX_BATCH = 40

        /**
         * Pure batch selection, mirroring [QueueController]'s pick: candidates rated in
         * the band, widened ±1 when thin, whole pool as the last resort — then shuffled
         * so Spotify's auto-advance stays varied.
         */
        internal fun batchFor(
            pool: List<RatedSpotifyTrack>,
            band: IntRange,
            random: Random,
        ): List<RatedSpotifyTrack> {
            if (pool.isEmpty()) return emptyList()
            fun candidates(b: IntRange) = pool.filter { it.rating in b }
            var pot = candidates(band)
            if (pot.size < MIN_POOL) {
                pot = candidates((band.first - 1).coerceAtLeast(1)..(band.last + 1).coerceAtMost(5))
            }
            if (pot.isEmpty()) pot = pool
            return pot.shuffled(random).take(MAX_BATCH)
        }
    }
}
