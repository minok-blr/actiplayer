package com.flowstate.session

import com.flowstate.data.db.SessionEventEntity
import com.flowstate.data.db.SessionEventKind
import com.flowstate.engine.RideState

/** One stretch of the session spent in a single mode — the bands on the recap timeline. */
data class ModeSegment(
    val state: RideState,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/** An HR / speed reading at a point in the session, for the lines over the timeline. */
data class Sample(val tMs: Long, val hrBpm: Int?, val speedKmh: Double?)

data class TrackPlay(
    val title: String,
    val artist: String,
    val mediaStoreId: Long?,
    val plays: Int,
    /** The mode this track spent most of its time under. */
    val mode: RideState?,
)

/**
 * Everything screen B5 draws, computed from a session's events.
 *
 * [overrides] is not in the plan's stat list and is deliberately added: every override is
 * the athlete correcting the engine, so the count is the most honest measure of whether
 * the thresholds are any good.
 */
data class SessionRecap(
    val segments: List<ModeSegment> = emptyList(),
    val samples: List<Sample> = emptyList(),
    val timePerMode: Map<RideState, Long> = emptyMap(),
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val topSpeedKmh: Double? = null,
    val switches: Int = 0,
    val overrides: Int = 0,
    val topTracks: List<TrackPlay> = emptyList(),
)

/**
 * Pure recap computation — no Android, no database. The recap is the only lens on whether
 * the engine behaved, so the arithmetic behind it is tested rather than eyeballed.
 */
object RecapBuilder {

    fun build(events: List<SessionEventEntity>, durationMs: Long): SessionRecap {
        if (events.isEmpty()) return SessionRecap()
        val ordered = events.sortedBy { it.tMs }
        val segments = segments(ordered, durationMs)

        val samples = ordered.filter { it.kind == SessionEventKind.SAMPLE }
            .map { Sample(it.tMs, it.hrBpm, it.speedKmh) }
        val hr = samples.mapNotNull { it.hrBpm }
        val speeds = samples.mapNotNull { it.speedKmh }

        return SessionRecap(
            segments = segments,
            samples = samples,
            timePerMode = segments.groupBy { it.state }
                .mapValues { (_, group) -> group.sumOf { it.durationMs } },
            avgHr = hr.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxHr = hr.maxOrNull(),
            topSpeedKmh = speeds.maxOrNull(),
            // The first MODE event is the session settling into its opening state, not a
            // switch away from anything, so it does not count as one.
            switches = (ordered.count { it.kind == SessionEventKind.MODE } - 1).coerceAtLeast(0),
            overrides = ordered.count { it.kind == SessionEventKind.OVERRIDE },
            topTracks = topTracks(ordered, segments),
        )
    }

    private fun segments(events: List<SessionEventEntity>, durationMs: Long): List<ModeSegment> {
        val modes = events.filter { it.kind == SessionEventKind.MODE && it.state != null }
        if (modes.isEmpty()) return emptyList()
        val end = maxOf(durationMs, modes.last().tMs)
        return modes.mapIndexed { index, event ->
            ModeSegment(
                state = event.state!!,
                startMs = event.tMs,
                endMs = modes.getOrNull(index + 1)?.tMs ?: end,
            )
        }.filter { it.durationMs > 0 }
    }

    /**
     * Which tracks actually carried the session, and under which mode. Attributed by the
     * mode in force when a track started — that is the pool it was picked from, which is
     * the question worth answering when curating pools later.
     */
    private fun topTracks(
        events: List<SessionEventEntity>,
        segments: List<ModeSegment>,
    ): List<TrackPlay> {
        val plays = events.filter { it.kind == SessionEventKind.TRACK && it.trackTitle != null }
        if (plays.isEmpty()) return emptyList()
        return plays
            .groupBy { it.trackTitle!! }
            .map { (title, group) ->
                val modes = group.mapNotNull { play ->
                    segments.firstOrNull { play.tMs >= it.startMs && play.tMs < it.endMs }?.state
                }
                TrackPlay(
                    title = title,
                    artist = group.first().trackArtist.orEmpty(),
                    mediaStoreId = group.firstNotNullOfOrNull { it.trackMediaId },
                    plays = group.size,
                    mode = modes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                )
            }
            .sortedWith(compareByDescending<TrackPlay> { it.plays }.thenBy { it.title })
    }
}
