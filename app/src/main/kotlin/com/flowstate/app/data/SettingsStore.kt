package com.flowstate.app.data

import android.content.Context
import com.flowstate.engine.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny settings store. Currently just max heart rate, which turns raw bpm into the
 * hrPctMax feature the engine's modulation rules run on (brief §6). Default 190 is a
 * placeholder — the user should set their real max (or 220 − age as a rough start).
 *
 * The floor is deliberately low (60) so modulation can be tested indoors: drop max HR
 * to near your resting rate and hrPctMax crosses the 85% threshold on the couch.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _maxHr = MutableStateFlow(prefs.getInt(KEY_MAX_HR, 190))
    val maxHr: StateFlow<Int> = _maxHr

    private val _guideDismissed = MutableStateFlow(prefs.getBoolean(KEY_GUIDE_DISMISSED, false))
    val guideDismissed: StateFlow<Boolean> = _guideDismissed

    private val _activity = MutableStateFlow(
        prefs.getString(KEY_ACTIVITY, null)
            ?.let { runCatching { Activity.valueOf(it) }.getOrNull() }
            ?: Activity.SNOWBOARDING,
    )
    val activity: StateFlow<Activity> = _activity

    fun setMaxHr(value: Int) {
        val clamped = value.coerceIn(60, 230)
        prefs.edit().putInt(KEY_MAX_HR, clamped).apply()
        _maxHr.value = clamped
    }

    fun setGuideDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_GUIDE_DISMISSED, dismissed).apply()
        _guideDismissed.value = dismissed
    }

    fun setActivity(activity: Activity) {
        prefs.edit().putString(KEY_ACTIVITY, activity.name).apply()
        _activity.value = activity
    }

    private companion object {
        const val KEY_MAX_HR = "max_hr"
        const val KEY_GUIDE_DISMISSED = "guide_dismissed"
        const val KEY_ACTIVITY = "activity"
    }
}
