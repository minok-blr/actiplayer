package com.flowstate.playback

/**
 * What the session loop needs from "something that plays music": react to the engine's
 * energy band, skip on demand, stop cleanly. [QueueController] implements it with the
 * local ExoPlayer (full fades); [SpotifyQueueController] implements it by commanding the
 * Spotify app (hard cuts — Spotify exposes no volume ramp). The engine cannot tell the
 * difference, which is the point.
 */
interface PlaybackBackend {
    fun onBand(newBand: IntRange, energyWentUp: Boolean, force: Boolean)
    fun skip()
    fun stop()
}
