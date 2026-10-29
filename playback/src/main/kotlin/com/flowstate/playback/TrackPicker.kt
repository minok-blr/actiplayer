package com.flowstate.playback

import kotlin.random.Random

/** Anything the picker can choose between: an id to avoid repeating, and an energy band. */
interface Banded {
    val id: Long
    val band: Int
}

/**
 * Which track plays next.
 *
 *  - candidates = tracks in the target band, not among the last [RECENT_LIMIT] played
 *  - widen by ±1 when fewer than [MIN_POOL] candidates survive, so a thin pool still
 *    varies instead of looping four songs
 *  - when everything that fits has played recently, repeat the one heard longest ago —
 *    the band is kept and the no-repeat rule is what gives
 *  - only when nothing within ±1 exists at all, take the nearest band the library has
 *
 * The order matters. Dropping the band before the no-repeat rule meant a small Hype pool
 * (six tracks, ten remembered) started feeding a riding block from Chill after six songs;
 * a repeat is a far smaller betrayal than the wrong energy.
 *
 * Pure and Android-free: this is the rule that decides what the athlete actually hears,
 * so it is the part that gets tested rather than desk-checked.
 */
object TrackPicker {

    const val MIN_POOL = 8
    const val RECENT_LIMIT = 10

    /**
     * @param recent ids played most recently, oldest first — the order decides which
     *   track comes round again when a band has been played through.
     */
    fun <T : Banded> pick(
        pool: List<T>,
        band: IntRange,
        recent: Collection<Long>,
        random: Random,
    ): T? {
        if (pool.isEmpty()) return null
        val fresh = pool.filter { it.id !in recent }

        var pot = fresh.filter { it.band in band }
        if (pot.size < MIN_POOL) pot = fresh.filter { it.band in band.widened() }
        if (pot.isNotEmpty()) return pot[random.nextInt(pot.size)]

        // Everything that fits has played recently: repeat rather than leave the band.
        val fits = pool.filter { it.band in band.widened() }
        if (fits.isNotEmpty()) {
            val age = recent.withIndex().associate { (i, id) -> id to i }
            return fits.minBy { age[it.id] ?: -1 }
        }

        // Nothing within reach of the band at all (an empty Mid pool, say): the nearest
        // energy the library has, which for a break between runs is Chill, not Hype.
        val nearest = pool.minOf { distance(it.band, band) }
        val closest = pool.filter { distance(it.band, band) == nearest }
        val closestFresh = closest.filter { it.id !in recent }
        val from = closestFresh.ifEmpty { closest }
        return from[random.nextInt(from.size)]
    }

    /** How far [band] sits outside [range]; 0 when inside it. */
    internal fun distance(band: Int, range: IntRange): Int = when {
        band < range.first -> range.first - band
        band > range.last -> band - range.last
        else -> 0
    }

    /**
     * The track to have warm on the standby player. Up-switches must be instant, so while
     * the athlete is calm the loud band is the one worth preloading, and vice versa.
     */
    fun standbyBand(current: IntRange): IntRange =
        if (current.last <= 3) 4..5 else 1..2

    private fun IntRange.widened(): IntRange =
        (first - 1).coerceAtLeast(1)..(last + 1).coerceAtMost(5)
}
