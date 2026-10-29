package com.flowstate.playback

import kotlinx.coroutines.flow.StateFlow

/** What's playing right now, whoever is playing it. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val band: Int?,
    val source: String,
    /** MediaStore id for cover art; null for sources that have no local file. */
    val mediaStoreId: Long? = null,
)

/** Whether a source can actually take a session right now. */
enum class Availability { READY, UNAVAILABLE }

/**
 * Something that can play music at a requested energy band (plan §3.2).
 *
 * [LocalSource] plays the user's files through two ExoPlayers with a real crossfade;
 * [SpotifyQueueController] hands batches to the Spotify app. The engine drives whichever
 * one is wired up and cannot tell them apart — which is the point, and what makes the
 * phase-7 `SourceRouter` fallback possible without touching the engine.
 */
interface MusicSource {

    val nowPlaying: StateFlow<NowPlaying?>

    val availability: StateFlow<Availability>

    /** True while the listener has paused; the session keeps deciding, silently. */
    val paused: StateFlow<Boolean>

    /**
     * React to the engine's energy band.
     *
     * @param fast the band went UP — cut hard, the drop-in has to land. Down-switches
     *   get the full crossfade instead, because getting on the lift should feel like an
     *   exhale.
     * @param force start playing even if the current track already fits (session start).
     */
    fun play(band: IntRange, fast: Boolean, force: Boolean)

    fun skip()

    /** Hold playback where it is — the timer profiles' PAUSE stops the music too. */
    fun pause()

    fun resume()

    fun stop()

    /**
     * How far through the current track, 0f..1f, or null when the source cannot say.
     * Sampled by the UI rather than pushed, so an idle session costs nothing.
     */
    fun progress(): Float?
}
