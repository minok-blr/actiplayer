package com.flowstate.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

/**
 * Scans the device's music library via MediaStore (build brief §8).
 * Offline-first: nothing here ever touches the network.
 */
class MediaLibrary(private val context: Context) {

    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                tracks += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = cursor.getString(artistCol) ?: "Unknown artist",
                    durationMs = cursor.getLong(durCol),
                )
            }
        }
        tracks
    }
}
