package com.flowstate.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-track energy ratings, 1 (couch) .. 5 (send it).
 *
 * SharedPreferences keeps the MVP free of annotation processors; Room replaces this in
 * the full build (brief §9). Unrated tracks are treated as middle energy — the brief's
 * default "treat as 3" policy — so the app works before the user has rated anything.
 */
class RatingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ratings", Context.MODE_PRIVATE)

    private val _ratings = MutableStateFlow(loadAll())
    val ratings: StateFlow<Map<Long, Int>> = _ratings

    fun setRating(trackId: Long, rating: Int) {
        val clamped = rating.coerceIn(1, 5)
        prefs.edit().putInt(trackId.toString(), clamped).apply()
        _ratings.value = _ratings.value + (trackId to clamped)
    }

    fun effectiveRating(trackId: Long): Int = _ratings.value[trackId] ?: UNRATED_DEFAULT

    private fun loadAll(): Map<Long, Int> =
        prefs.all
            .mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                val rating = value as? Int ?: return@mapNotNull null
                id to rating
            }
            .toMap()

    companion object {
        const val UNRATED_DEFAULT = 3
    }
}
