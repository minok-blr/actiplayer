package com.flowstate.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Embedded cover art for local tracks.
 *
 * Uses `ContentResolver.loadThumbnail` on the track URI (API 29, our minSdk) rather than
 * the `MediaStore.Audio.Albums.ALBUM_ART` column, which has been dead for years and reads
 * NULL for every album on a modern device. Roughly half a real library carries an
 * embedded APIC frame; the rest fall back to a generated tile, so a track never shows an
 * empty square.
 *
 * No image library: one MediaStore source and one size means a bitmap cache and a
 * coroutine are the whole job, and every added dependency has to clear our AGP 8.13 cap.
 */
@Singleton
class AlbumArtLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /** Tracks already known to carry no art, so a miss is not retried on every scroll. */
    private val known = mutableSetOf<Long>()

    suspend fun load(mediaStoreId: Long): Bitmap? {
        cache.get(mediaStoreId)?.let { return it }
        if (mediaStoreId in known) return null
        return withContext(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                mediaStoreId,
            )
            // Throws rather than returning null when a track has no embedded artwork.
            val bitmap = runCatching {
                context.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
            }.getOrNull()
            if (bitmap == null) {
                synchronized(known) { known += mediaStoreId }
            } else {
                cache.put(mediaStoreId, bitmap)
            }
            bitmap
        }
    }

    private companion object {
        const val THUMB_PX = 320
        const val CACHE_BYTES = 12 * 1024 * 1024
    }
}
