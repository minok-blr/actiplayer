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
) : MusicSource {

    private var pool: List<RatedSpotifyTrack> = emptyList()
    private var band: IntRange = 2..3
    private val random = Random(System.currentTimeMillis())

    /** What Spotify reports is ACTUALLY playing, not what we last sent. */
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _availability = MutableStateFlow(Availability.UNAVAILABLE)
    override val availability: StateFlow<Availability> = _availability

    private val _paused = MutableStateFlow(false)
    override val paused: StateFlow<Boolean> = _paused

    /** Last player-control failure, shown on the Ride Board ("needs Premium" lands here). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var pollJob: Job? = null
    private var sendJob: Job? = null

    fun setPool(tracks: List<RatedSpotifyTrack>) {
        pool = tracks
        _availability.value =
            if (tracks.isEmpty()) Availability.UNAVAILABLE else Availability.READY
    }

    /** Starts the now-playing poll; call at session start, paired with [stop]. */
    fun start() {
        _error.value = null
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                runCatching { remote.currentlyPlayingLabel() }
                    .onSuccess { label ->
                        if (label != null) _nowPlaying.value = label.toNowPlaying()
                    }
            }
        }
    }

    override fun play(band: IntRange, fast: Boolean, force: Boolean) {
        val changed = band != this.band
        this.band = band
        if (!force && !changed) return
        // A pause is the listener's: the new band is remembered and sent on resume.
        if (_paused.value) return
        sendBatch()
    }

    override fun skip() = sendBatch()

    override fun pause() {
        _paused.value = true
        scope.launch { runCatching { remote.pausePlayback() } }
    }

    /** Resuming means re-sending the batch: Connect has no plain "resume" we hold state for. */
    override fun resume() {
        _paused.value = false
        sendBatch()
    }

    /** Spotify Connect reports position only on the slow poll, so this stays unknown. */
    override fun progress(): Float? = null

    override fun stop() {
        _paused.value = false
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
                val first = batch.first()
                _nowPlaying.value =
                    NowPlaying(first.title, first.artist, first.rating, SOURCE_NAME)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /**
     * Spotify hands back one display string, not fields; splitting on the em dash we
     * built it with is best-effort and the artist is simply blank if it is not there.
     */
    private fun String.toNowPlaying(): NowPlaying {
        val parts = split(" — ", limit = 2)
        return NowPlaying(parts[0], parts.getOrElse(1) { "" }, null, SOURCE_NAME)
    }

    companion object {
        const val SOURCE_NAME = "Spotify"
        const val POLL_MS = 15_000L
        const val MIN_POOL = 8
        const val MAX_BATCH = 40

        /**
         * Pure batch selection, mirroring [TrackPicker]: candidates rated in the band,
         * widened ±1 when thin, and only then the nearest band the ratings cover — never
         * the whole pool, which would hand a hype block chill tracks. Shuffled so
         * Spotify's auto-advance stays varied.
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
            if (pot.isEmpty()) {
                val nearest = pool.minOf { TrackPicker.distance(it.rating, band) }
                pot = pool.filter { TrackPicker.distance(it.rating, band) == nearest }
            }
            return pot.shuffled(random).take(MAX_BATCH)
        }
    }
}
