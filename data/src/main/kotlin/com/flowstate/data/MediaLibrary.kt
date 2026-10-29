package com.flowstate.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The keys a track can be pooled by. Kept separate from [Track] so the resolution rules
 * can be unit-tested without an Android `Uri`.
 */
interface PoolKeys {
    val id: Long
    val bucketId: Long
    val albumId: Long

    /** Podcasts never enter a session pool, however they got there. */
    val isPodcast: Boolean
}

data class Track(
    override val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    override val albumId: Long,
    val album: String,
    override val bucketId: Long,
    /** Display path, e.g. `Music/Metal` — the leaf bucket name alone is too ambiguous. */
    val folder: String,
    override val isPodcast: Boolean,
) : PoolKeys

/** A folder or album, summarised for the "add to pool" browser (screen C2). */
data class SourceSummary(
    val kind: com.flowstate.data.db.PoolSourceKind,
    val key: String,
    val label: String,
    val trackCount: Int,
    val durationMs: Long,
    /** MediaStore marks podcasts; a folder full of them should not quietly become music. */
    val podcastCount: Int,
)

/** A device playlist plus the tracks in it. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val audioIds: List<Long>,
)

/**
 * Scans the device's music library via MediaStore (plan §3.2).
 * Offline-first: nothing here ever touches the network.
 */
@Singleton
class MediaLibrary @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var trackCache: List<Track>? = null
    private var playlistCache: List<PlaylistSummary>? = null

    /**
     * Cached view for the browsing screens, which would otherwise re-scan MediaStore on
     * every navigation. Sessions always call [loadTracks] so a file added five minutes
     * ago is in the pool.
     */
    suspend fun tracks(refresh: Boolean = false): List<Track> =
        if (!refresh) trackCache ?: loadTracks() else loadTracks()

    suspend fun playlists(refresh: Boolean = false): List<PlaylistSummary> =
        if (!refresh) playlistCache ?: loadPlaylists() else loadPlaylists()

    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            // BUCKET_ID / RELATIVE_PATH are API 29+, which is our minSdk.
            MediaStore.Audio.Media.BUCKET_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.IS_PODCAST,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val podcastCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val folder = cursor.getString(pathCol)?.trim('/') ?: ""
                val names = TrackNames.clean(cursor.getString(titleCol), cursor.getString(artistCol))
                tracks += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    title = names.title,
                    // Empty, never "<unknown>": the screens leave the line out instead.
                    artist = names.artist,
                    durationMs = cursor.getLong(durCol),
                    album = cursor.getString(albumCol)
                        ?.takeUnless { it == TrackNames.UNKNOWN } ?: "Unknown album",
                    albumId = cursor.getLong(albumIdCol),
                    bucketId = cursor.getLong(bucketCol),
                    folder = folder,
                    // MediaStore's IS_PODCAST flag is almost never set in practice, so the
                    // folder path is the signal that actually works — and a folder called
                    // "Podcasts" becoming ride music is the exact mistake C2 warns about.
                    isPodcast = cursor.getInt(podcastCol) != 0 ||
                        folder.contains("podcast", ignoreCase = true),
                )
            }
        }
        trackCache = tracks
        tracks
    }

    /**
     * Device playlists.
     *
     * `MediaStore.Audio.Playlists` was deprecated in API 31 and third-party apps can no
     * longer create or modify playlists they do not own — but reading still works, and on
     * many devices there simply are none. Callers must handle an empty list as normal,
     * not as an error.
     */
    @Suppress("DEPRECATION")
    suspend fun loadPlaylists(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        val playlists = mutableListOf<PlaylistSummary>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null,
                null,
                "${MediaStore.Audio.Playlists.NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    playlists += PlaylistSummary(
                        id = id,
                        name = cursor.getString(nameCol) ?: "Playlist",
                        audioIds = playlistMembers(id),
                    )
                }
            }
        }
        playlistCache = playlists
        playlists
    }

    @Suppress("DEPRECATION")
    private fun playlistMembers(playlistId: Long): List<Long> {
        val ids = mutableListOf<Long>()
        context.contentResolver.query(
            MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId),
            arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
            null,
            null,
            MediaStore.Audio.Playlists.Members.PLAY_ORDER,
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            while (cursor.moveToNext()) ids += cursor.getLong(col)
        }
        return ids
    }

    companion object {
        /**
         * Folders and albums are grouped from the already-loaded track list rather than
         * re-queried: one cursor pass instead of three, and it makes the summarisation a
         * pure function the tests can reach.
         */
        fun folders(tracks: List<Track>): List<SourceSummary> =
            summarise(tracks, com.flowstate.data.db.PoolSourceKind.FOLDER) {
                it.bucketId.toString() to it.folder.ifEmpty { "Unknown folder" }
            }

        fun albums(tracks: List<Track>): List<SourceSummary> =
            summarise(tracks, com.flowstate.data.db.PoolSourceKind.ALBUM) {
                it.albumId.toString() to it.album
            }

        private fun summarise(
            tracks: List<Track>,
            kind: com.flowstate.data.db.PoolSourceKind,
            keyOf: (Track) -> Pair<String, String>,
        ): List<SourceSummary> =
            tracks.groupBy { keyOf(it).first }
                .map { (key, group) ->
                    SourceSummary(
                        kind = kind,
                        key = key,
                        label = keyOf(group.first()).second,
                        trackCount = group.size,
                        durationMs = group.sumOf { it.durationMs },
                        podcastCount = group.count { it.isPodcast },
                    )
                }
                .sortedByDescending { it.trackCount }
    }
}
