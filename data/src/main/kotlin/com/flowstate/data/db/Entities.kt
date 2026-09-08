package com.flowstate.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a track's audio comes from. Local files are enumerable; Spotify tracks are not. */
enum class TrackSource { LOCAL, SPOTIFY }

/**
 * How a track's energy band was decided, in the resolution order of the plan (§3.1):
 * an explicit user rating beats pool membership, which beats a tempo guess.
 */
enum class EnergySource { USER, POOL, GUESS }

/**
 * One track the app knows about.
 *
 * [id] is a stable synthetic key — `local:<mediaStoreId>` or `spotify:<uri>` — so ratings,
 * pools and session events can reference either source through one column. Local tracks
 * are re-scanned from MediaStore on demand and only need a row once something references
 * them; Spotify tracks need a row as soon as they are rated, because Spotify exposes no
 * enumerable library to look the title and artist back up from.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val source: TrackSource,
    val mediaStoreId: Long?,
    val spotifyUri: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val folder: String?,
    val durationMs: Long,
    val bpmGuess: Int?,
) {
    companion object {
        fun localId(mediaStoreId: Long): String = "local:$mediaStoreId"
        fun spotifyId(uri: String): String = "spotify:$uri"
    }
}

/**
 * A track's energy band, 1 (couch) .. 5 (send it).
 *
 * [band] is null when the user excluded the track from every pool — the plan's `EXCLUDED`
 * (§3.1) — which is a different thing from "unrated": an unrated track simply has no row
 * here and falls back to the neutral default.
 */
@Entity(
    tableName = "track_energy",
    indices = [Index("band")],
)
data class TrackEnergyEntity(
    @PrimaryKey val trackId: String,
    val band: Int?,
    val source: EnergySource,
    val updatedAt: Long,
)
