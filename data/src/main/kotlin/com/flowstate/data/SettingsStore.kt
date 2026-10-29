package com.flowstate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
enum class SourceKind { LOCAL, SPOTIFY }

/**
 * How patient the engine is about leaving a mode (plan §3.3). Scales every *down*-switch
 * confirmation window; up-switches never scale, because a drop-in has to land instantly
 * whatever the athlete chose.
 */
enum class Sensitivity(val label: String, val scale: Double, val blurb: String) {
    QUICK("Quick", 0.6, "Follows you closely. More switching."),
    BALANCED("Balanced", 1.0, "The default. Rarely wrong, rarely twitchy."),
    SURE("Sure", 1.5, "Waits to be certain. Fewer, later switches."),
}

/** Plan §1: metric by default. Only speed is shown in a unit anywhere in the app. */
enum class Units(val speedUnit: String, private val perMs: Double) {
    METRIC("km/h", 3.6),
    IMPERIAL("mph", 2.236_936),
    ;

    /** m/s -> km/h or mph. */
    fun speed(metresPerSecond: Double): Double = metresPerSecond * perMs

    /** Converts a value recorded in km/h (the recorder's storage unit). */
    fun speedFromKmh(kmh: Double): Double = speed(kmh / 3.6)
}

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

    /**
     * Has the A1–A5 wizard been completed?
     *
     * Migrates the old first-run flag (plan §6.5): someone who already dismissed the
     * pre-wizard quick guide has been through onboarding in every sense that matters and
     * must not be dropped back into a setup flow by an update.
     */
    val onboardingComplete: StateFlow<Boolean> =
        setting { it[KEY_ONBOARDING_DONE] ?: it[KEY_GUIDE_DISMISSED] ?: false }

    val activity: StateFlow<Activity> = setting { p ->
        p[KEY_ACTIVITY]?.let { runCatching { Activity.valueOf(it) }.getOrNull() }
            ?: Activity.SNOWBOARDING
    }

    /** Last SoundCloud link loaded in the Spotify/remote screen's embedded player. */
    val soundcloudUrl: StateFlow<String> = setting { it[KEY_SOUNDCLOUD_URL] ?: "" }

    val musicSource: StateFlow<SourceKind> = setting { p ->
        p[KEY_MUSIC_SOURCE]?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() }
            ?: SourceKind.LOCAL
    }

    /**
     * Crossfade length for down-switches and track ends, 1–3 s (plan §1). Up-switches
     * ignore it and always cut fast — the drop-in has to land.
     */
    val crossfadeMs: StateFlow<Int> = setting {
        (it[KEY_CROSSFADE_MS] ?: DEFAULT_CROSSFADE_MS).coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)
    }

    val sensitivity: StateFlow<Sensitivity> = setting { p ->
        p[KEY_SENSITIVITY]?.let { runCatching { Sensitivity.valueOf(it) }.getOrNull() }
            ?: Sensitivity.BALANCED
    }

    /**
     * The work/rest clock, as `work|rest|rounds` in ms — one per clock-driven activity.
     *
     * They used to share one slot, so setting up a Tabata for HIIT quietly replaced the
     * 30/90 Strength plan and the next gym session opened on intervals. The Strength plan
     * keeps the original key, so nothing already saved is lost.
     */
    private val strengthPlan: StateFlow<Triple<Long, Long, Int>> =
        setting { parsePlan(it[KEY_TIMER_PLAN]) ?: DEFAULT_STRENGTH_PLAN }

    private val hiitPlan: StateFlow<Triple<Long, Long, Int>> =
        setting { parsePlan(it[KEY_TIMER_PLAN_HIIT]) ?: DEFAULT_HIIT_PLAN }

    fun timerPlan(activity: Activity): StateFlow<Triple<Long, Long, Int>> =
        if (activity == Activity.HIIT) hiitPlan else strengthPlan

    /** Pause when headphones are unplugged or a Bluetooth headset drops (D3). */
    val pauseOnDisconnect: StateFlow<Boolean> = setting { it[KEY_PAUSE_ON_DISCONNECT] ?: true }

    val units: StateFlow<Units> = setting { p ->
        p[KEY_UNITS]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.METRIC
    }

    /** Debug toggle (plan §3.7): show the raw telemetry behind the plain sentence. */
    val showSignals: StateFlow<Boolean> = setting { it[KEY_SHOW_SIGNALS] ?: false }

    /** One-shot flag: the pre-Room ratings in SharedPreferences have been imported. */
    val legacyRatingsImported: StateFlow<Boolean> =
        setting { it[KEY_LEGACY_RATINGS_IMPORTED] ?: false }

    fun setMaxHr(value: Int) = write { it[KEY_MAX_HR] = value.coerceIn(MIN_MAX_HR, MAX_MAX_HR) }

    fun setCrossfadeMs(value: Int) =
        write { it[KEY_CROSSFADE_MS] = value.coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS) }

    fun setSensitivity(value: Sensitivity) = write { it[KEY_SENSITIVITY] = value.name }

    fun setTimerPlan(activity: Activity, workMs: Long, restMs: Long, rounds: Int) = write {
        val key = if (activity == Activity.HIIT) KEY_TIMER_PLAN_HIIT else KEY_TIMER_PLAN
        it[key] = "$workMs|$restMs|$rounds"
    }

    fun setPauseOnDisconnect(pause: Boolean) = write { it[KEY_PAUSE_ON_DISCONNECT] = pause }

    fun setUnits(units: Units) = write { it[KEY_UNITS] = units.name }

    fun setOnboardingComplete(done: Boolean) = write { it[KEY_ONBOARDING_DONE] = done }

    fun setShowSignals(show: Boolean) = write { it[KEY_SHOW_SIGNALS] = show }

    fun setGuideDismissed(dismissed: Boolean) = write { it[KEY_GUIDE_DISMISSED] = dismissed }

    fun setActivity(activity: Activity) = write { it[KEY_ACTIVITY] = activity.name }

    fun setMusicSource(source: SourceKind) = write { it[KEY_MUSIC_SOURCE] = source.name }

    fun setSoundcloudUrl(url: String) = write { it[KEY_SOUNDCLOUD_URL] = url.trim() }

    suspend fun markLegacyRatingsImported() {
        dataStore.edit { it[KEY_LEGACY_RATINGS_IMPORTED] = true }
    }

    private fun write(block: (MutablePreferences) -> Unit) {
        scope.launch { dataStore.edit(block) }
    }

    private fun parsePlan(raw: String?): Triple<Long, Long, Int>? {
        val parts = raw?.split('|')?.takeIf { it.size == 3 } ?: return null
        val work = parts[0].toLongOrNull() ?: return null
        val rest = parts[1].toLongOrNull() ?: return null
        val rounds = parts[2].toIntOrNull() ?: return null
        return Triple(work, rest, rounds)
    }

    private companion object {
        val KEY_MAX_HR = intPreferencesKey("max_hr")
        val KEY_GUIDE_DISMISSED = booleanPreferencesKey("guide_dismissed")
        val KEY_ACTIVITY = stringPreferencesKey("activity")
        val KEY_SOUNDCLOUD_URL = stringPreferencesKey("soundcloud_url")
        val KEY_MUSIC_SOURCE = stringPreferencesKey("music_source")
        val KEY_LEGACY_RATINGS_IMPORTED = booleanPreferencesKey("legacy_ratings_imported")
        val KEY_CROSSFADE_MS = intPreferencesKey("crossfade_ms")
        val KEY_SHOW_SIGNALS = booleanPreferencesKey("show_signals")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        val KEY_SENSITIVITY = stringPreferencesKey("sensitivity")
        val KEY_TIMER_PLAN = stringPreferencesKey("timer_plan")
        val KEY_TIMER_PLAN_HIIT = stringPreferencesKey("timer_plan_hiit")
        val KEY_PAUSE_ON_DISCONNECT = booleanPreferencesKey("pause_on_disconnect")
        val KEY_UNITS = stringPreferencesKey("units")

        /** Strength 30/90 x 8 and Tabata 20/10 x 8 — the presets screen D4 opens on. */
        val DEFAULT_STRENGTH_PLAN = Triple(30_000L, 90_000L, 8)
        val DEFAULT_HIIT_PLAN = Triple(20_000L, 10_000L, 8)

        const val DEFAULT_MAX_HR = 190

        /**
         * The floor is deliberately low so HR modulation can be tested indoors: drop max
         * HR near your resting rate and hrPctMax crosses the 85% threshold on the couch.
         */
        const val MIN_MAX_HR = 60
        const val MAX_MAX_HR = 230

        const val DEFAULT_CROSSFADE_MS = 2_000
        const val MIN_CROSSFADE_MS = 1_000
        const val MAX_CROSSFADE_MS = 3_000
    }
}
