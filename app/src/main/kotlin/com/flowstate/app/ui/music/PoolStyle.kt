package com.flowstate.app.ui.music

import androidx.compose.ui.graphics.Color
import com.flowstate.app.ui.ActiColors
import com.flowstate.data.MediaLibrary
import com.flowstate.data.PlaylistSummary
import com.flowstate.data.Track
import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.PoolSourceEntity
import com.flowstate.data.db.PoolSourceKind

/** Pool colour is the mode colour the pool feeds — the palette carries the meaning. */
val EnergyLevel.color: Color
    get() = when (this) {
        EnergyLevel.HYPE -> ActiColors.riding
        EnergyLevel.MID -> ActiColors.paused
        EnergyLevel.CHILL -> ActiColors.lift
    }

val EnergyLevel.displayName: String
    get() = when (this) {
        EnergyLevel.HYPE -> "Hype"
        EnergyLevel.MID -> "Mid"
        EnergyLevel.CHILL -> "Chill"
    }

/** The engine states each pool actually feeds, per screen C1. */
val EnergyLevel.modes: String
    get() = when (this) {
        EnergyLevel.HYPE -> "Riding, efforts, sprints and sets"
        EnergyLevel.MID -> "Breaks, easy running, ticking over"
        EnergyLevel.CHILL -> "The lift, cruising, strolls and rests"
    }

/**
 * How many library tracks a single pool source actually contributes — podcasts excluded,
 * so the chip agrees with what the engine will really pick from.
 */
fun PoolSourceEntity.trackCount(
    tracks: List<Track>,
    playlists: List<PlaylistSummary>,
): Int {
    val music = tracks.filterNot { it.isPodcast }
    return when (kind) {
        PoolSourceKind.FOLDER -> music.count { it.bucketId.toString() == key }
        PoolSourceKind.ALBUM -> music.count { it.albumId.toString() == key }
        PoolSourceKind.PLAYLIST -> {
            val members = playlists.firstOrNull { it.id.toString() == key }?.audioIds.orEmpty().toSet()
            music.count { it.id in members }
        }
        PoolSourceKind.SPOTIFY_PLAYLIST -> 0 // phase 7
    }
}

/** How many of this source's tracks actually resolve into the pool it sits in. */
fun PoolSourceEntity.contributing(
    tracks: List<Track>,
    playlists: List<PlaylistSummary>,
    resolvedLevel: Map<Long, EnergyLevel>,
): Int {
    val music = tracks.filterNot { it.isPodcast }
    val members = when (kind) {
        PoolSourceKind.FOLDER -> music.filter { it.bucketId.toString() == key }
        PoolSourceKind.ALBUM -> music.filter { it.albumId.toString() == key }
        PoolSourceKind.PLAYLIST -> {
            val ids = playlists.firstOrNull { it.id.toString() == key }?.audioIds.orEmpty().toSet()
            music.filter { it.id in ids }
        }
        PoolSourceKind.SPOTIFY_PLAYLIST -> emptyList() // phase 7
    }
    return members.count { resolvedLevel[it.id] == level }
}

fun playlistMembersByKey(playlists: List<PlaylistSummary>): Map<String, Set<Long>> =
    playlists.associate { it.id.toString() to it.audioIds.toSet() }

fun PlaylistSummary.asSource() = com.flowstate.data.SourceSummary(
    kind = PoolSourceKind.PLAYLIST,
    key = id.toString(),
    label = name,
    trackCount = audioIds.size,
    durationMs = 0L,
    podcastCount = 0,
)

fun sourcesFor(tracks: List<Track>, tab: BrowseTab, playlists: List<PlaylistSummary>) =
    when (tab) {
        BrowseTab.FOLDERS -> MediaLibrary.folders(tracks)
        BrowseTab.ALBUMS -> MediaLibrary.albums(tracks)
        BrowseTab.PLAYLISTS -> playlists.map { it.asSource() }
    }

enum class BrowseTab(val label: String) {
    FOLDERS("Folders"),
    ALBUMS("Albums"),
    PLAYLISTS("Playlists"),
}

/** `2h 41m` / `38m` — the mono duration line under each source on screen C2. */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** `4:12` — track length on screen C4. */
fun formatTrackLength(ms: Long): String {
    val totalSeconds = ms / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
