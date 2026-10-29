package com.flowstate.data

import android.content.Context
import android.util.Log
import com.flowstate.data.db.EnergySource
import com.flowstate.data.db.TrackDao
import com.flowstate.data.db.TrackEnergyEntity
import com.flowstate.data.db.TrackEntity
import com.flowstate.data.db.TrackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * One-shot import of the pre-Room ratings.
 *
 * Before phase 1 the app kept local ratings in a `ratings` SharedPreferences file
 * (`trackId -> Int`) and Spotify ratings in `spotify_ratings` (`uri -> {t,a,r}` JSON).
 * A device that has been rating tracks for months must not lose them to the restructure,
 * so both files are read once and written into Room, guarded by a DataStore flag.
 *
 * The old files are deliberately left on disk: they cost a few kilobytes and they are the
 * only way back if the import ever turns out to be wrong.
 */
@Singleton
class LegacyRatingsImport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: TrackDao,
    private val settings: SettingsStore,
) {

    suspend fun runIfNeeded() {
        if (settings.legacyRatingsImported.value) return
        val found = runCatching { importLocal() + importSpotify() }
            .onFailure { Log.w(TAG, "legacy ratings import failed", it) }
            .getOrDefault(0)
        // Only close the door once something was actually imported. Marking it done on an
        // empty device would mean a later restore from backup — or a reinstall that puts
        // the old preference files back — is silently ignored forever.
        if (found > 0) settings.markLegacyRatingsImported()
    }

    /** @return how many ratings were imported. */
    private suspend fun importLocal(): Int {
        val prefs = context.getSharedPreferences("ratings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val energies = prefs.all.mapNotNull { (key, value) ->
            val mediaStoreId = key.toLongOrNull() ?: return@mapNotNull null
            val band = (value as? Int)?.coerceIn(1, 5) ?: return@mapNotNull null
            TrackEnergyEntity(TrackEntity.localId(mediaStoreId), band, EnergySource.USER, now)
        }
        if (energies.isEmpty()) return 0
        dao.upsertEnergies(energies)
        Log.i(TAG, "imported ${energies.size} local ratings")
        return energies.size
    }

    /** @return how many ratings were imported. */
    private suspend fun importSpotify(): Int {
        val prefs = context.getSharedPreferences("spotify_ratings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val tracks = mutableListOf<TrackEntity>()
        val energies = mutableListOf<TrackEnergyEntity>()
        prefs.all.forEach { (uri, value) ->
            val json = runCatching { JSONObject(value as String) }.getOrNull() ?: return@forEach
            val id = TrackEntity.spotifyId(uri)
            tracks += TrackEntity(
                id = id,
                source = TrackSource.SPOTIFY,
                mediaStoreId = null,
                spotifyUri = uri,
                title = json.optString("t"),
                artist = json.optString("a"),
                album = null,
                folder = null,
                durationMs = 0L,
                bpmGuess = null,
            )
            energies += TrackEnergyEntity(id, json.optInt("r", 3).coerceIn(1, 5), EnergySource.USER, now)
        }
        if (tracks.isEmpty()) return 0
        dao.upsertTracks(tracks)
        dao.upsertEnergies(energies)
        Log.i(TAG, "imported ${tracks.size} Spotify ratings")
        return tracks.size
    }

    private companion object {
        const val TAG = "LegacyRatingsImport"
    }
}
