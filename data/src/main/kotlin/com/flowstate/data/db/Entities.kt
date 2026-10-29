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

/**
 * The three user-facing energy levels (plan §3.1). The engine still thinks in bands 1..5;
 * these are the buckets a user actually sorts music into, and [band] is what a track
 * inherits from the pool it sits in when it has no explicit rating of its own.
 */
enum class EnergyLevel(val band: Int) {
    CHILL(1),
    MID(3),
    HYPE(5),
}

/** What kind of thing was added to a pool. Spotify playlists arrive in phase 7. */
enum class PoolSourceKind { FOLDER, ALBUM, PLAYLIST, SPOTIFY_PLAYLIST }

/**
 * One source feeding one energy pool — a folder, an album, a device playlist, or (later)
 * a Spotify playlist.
 *
 * The primary key is (kind, key), so a given folder lives in exactly one pool: adding it
 * to Mid when it is already in Hype moves it, which is what C2's "IN MID" badge and C4's
 * move buttons both imply. [label] is denormalised so a pool can be drawn without
 * re-scanning MediaStore.
 */
@Entity(tableName = "pool_sources", primaryKeys = ["kind", "key"])
data class PoolSourceEntity(
    val kind: PoolSourceKind,
    val key: String,
    val level: EnergyLevel,
    val label: String,
    val addedAt: Long,
)
