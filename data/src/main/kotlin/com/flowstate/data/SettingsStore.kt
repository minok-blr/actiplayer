package com.flowstate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowstate.data.di.ApplicationScope
import com.flowstate.engine.Activity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Who plays the music during a session. LOCAL = our ExoPlayer; SPOTIFY = the Spotify app. */
enum class MusicSource { LOCAL, SPOTIFY }

/**
 * The legacy `settings` SharedPreferences file is migrated into DataStore on first read;
 * key names are preserved so the migration is a straight copy.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, "settings")) },
)

/**
 * App settings on DataStore (Preferences), per plan §3.1.
 *
 * The API is deliberately synchronous-looking — `StateFlow` reads, fire-and-forget writes
 * — because the session loop reads settings from non-suspending call sites (`maxHr.value`
 * inside the 1 Hz sensor tick) and the screens read them during composition. The one cost
 * is a single blocking first read at construction, so nothing ever observes a default it
 * would then have to correct on screen (a dismissed guide must not flash back).
 */
@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val dataStore = context.settingsDataStore

    private val prefs: StateFlow<Preferences> = run {
        val initial = runBlocking { dataStore.data.first() }
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, initial)
    }

    private fun <T> setting(read: (Preferences) -> T): StateFlow<T> =
        prefs.map(read).stateIn(scope, SharingStarted.Eagerly, read(prefs.value))

    val maxHr: StateFlow<Int> = setting { it[KEY_MAX_HR] ?: DEFAULT_MAX_HR }

    val guideDismissed: StateFlow<Boolean> = setting { it[KEY_GUIDE_DISMISSED] ?: false }

    val activity: StateFlow<Activity> = setting { p ->
        p[KEY_ACTIVITY]?.let { runCatching { Activity.valueOf(it) }.getOrNull() }
            ?: Activity.SNOWBOARDING
    }

    /** Last SoundCloud link loaded in the Spotify/remote screen's embedded player. */
    val soundcloudUrl: StateFlow<String> = setting { it[KEY_SOUNDCLOUD_URL] ?: "" }

    val musicSource: StateFlow<MusicSource> = setting { p ->
        p[KEY_MUSIC_SOURCE]?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() }
            ?: MusicSource.LOCAL
    }

    /** One-shot flag: the pre-Room ratings in SharedPreferences have been imported. */
    val legacyRatingsImported: StateFlow<Boolean> =
        setting { it[KEY_LEGACY_RATINGS_IMPORTED] ?: false }

    fun setMaxHr(value: Int) = write { it[KEY_MAX_HR] = value.coerceIn(MIN_MAX_HR, MAX_MAX_HR) }

    fun setGuideDismissed(dismissed: Boolean) = write { it[KEY_GUIDE_DISMISSED] = dismissed }

    fun setActivity(activity: Activity) = write { it[KEY_ACTIVITY] = activity.name }

    fun setMusicSource(source: MusicSource) = write { it[KEY_MUSIC_SOURCE] = source.name }

    fun setSoundcloudUrl(url: String) = write { it[KEY_SOUNDCLOUD_URL] = url.trim() }

    suspend fun markLegacyRatingsImported() {
        dataStore.edit { it[KEY_LEGACY_RATINGS_IMPORTED] = true }
    }

    private fun write(block: (MutablePreferences) -> Unit) {
        scope.launch { dataStore.edit(block) }
    }

    private companion object {
        val KEY_MAX_HR = intPreferencesKey("max_hr")
        val KEY_GUIDE_DISMISSED = booleanPreferencesKey("guide_dismissed")
        val KEY_ACTIVITY = stringPreferencesKey("activity")
        val KEY_SOUNDCLOUD_URL = stringPreferencesKey("soundcloud_url")
        val KEY_MUSIC_SOURCE = stringPreferencesKey("music_source")
        val KEY_LEGACY_RATINGS_IMPORTED = booleanPreferencesKey("legacy_ratings_imported")

        const val DEFAULT_MAX_HR = 190

        /**
         * The floor is deliberately low so HR modulation can be tested indoors: drop max
         * HR near your resting rate and hrPctMax crosses the 85% threshold on the couch.
         */
        const val MIN_MAX_HR = 60
        const val MAX_MAX_HR = 230
    }
}
