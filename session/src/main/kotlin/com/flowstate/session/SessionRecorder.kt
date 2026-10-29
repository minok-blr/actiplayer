package com.flowstate.session

import com.flowstate.data.SourceKind
import com.flowstate.data.db.SessionDao
import com.flowstate.data.db.SessionEntity
import com.flowstate.data.db.SessionEventEntity
import com.flowstate.data.db.SessionEventKind
import com.flowstate.data.di.ApplicationScope
import com.flowstate.engine.Activity
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.RideState
import com.flowstate.playback.NowPlaying
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Writes what happened during a session so it can be looked at afterwards (plan §3.4).
 *
 * This is the tuning loop: every threshold in `EngineConfig` is a starting value, and
 * without a recorded trace the only way to judge one is to watch the screen and form an
 * impression. Overrides are recorded for the same reason — each one is the engine being
 * corrected by hand, which makes them the honest measure of whether it is any good.
 *
 * Everything runs on the application scope (the main dispatcher), so the ordering here
 * needs no synchronisation; events written before the session row exists are held and
 * flushed once it does.
 */
@Singleton
class SessionRecorder @Inject constructor(
    private val dao: SessionDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private var sessionId: Long? = null
    private var startedElapsedMs = 0L
    private var recording = false

    /** Events that arrived before the session row's id came back from Room. */
    private val pending = mutableListOf<SessionEventEntity>()

    private var lastState: RideState? = null
    private var lastOverride: OverrideMode? = null
    private var lastTrackTitle: String? = null
    private var lastSampleMs = Long.MIN_VALUE

    fun start(activity: Activity, source: SourceKind, startedElapsedMs: Long) {
        if (recording) return
        recording = true
        this.startedElapsedMs = startedElapsedMs
        sessionId = null
        pending.clear()
        lastState = null
        lastOverride = null
        lastTrackTitle = null
        lastSampleMs = Long.MIN_VALUE

        scope.launch {
            // Housekeeping first: a crash or a force-stop leaves a session with no end.
            runCatching { dao.pruneEmpty() }
            val id = dao.insertSession(
                SessionEntity(
                    activity = activity,
                    source = source,
                    startedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            sessionId = id
            flush()
        }
    }

    /** A mode change, and a periodic HR/speed sample for the timeline. */
    fun onFrame(state: RideState, band: IntRange, frame: FeatureFrame) {
        if (!recording) return
        val t = frame.tMillis - startedElapsedMs
        if (state != lastState) {
            lastState = state
            record(
                SessionEventKind.MODE,
                t,
                state = state,
                bandLow = band.first,
                bandHigh = band.last,
            )
        }
        // 1 Hz frames downsampled to 5 s for storage (plan §3.4): enough resolution for
        // the recap's lines, a fifth of the rows.
        if (t - lastSampleMs >= SAMPLE_INTERVAL_MS) {
            lastSampleMs = t
            record(
                SessionEventKind.SAMPLE,
                t,
                hrBpm = frame.hrBpm,
                speedKmh = frame.speed?.times(3.6),
            )
        }
    }

    fun onOverride(mode: OverrideMode, nowElapsedMs: Long) {
        if (!recording || mode == lastOverride) return
        lastOverride = mode
        record(SessionEventKind.OVERRIDE, nowElapsedMs - startedElapsedMs, overrideMode = mode.name)
    }

    fun onTrack(nowPlaying: NowPlaying, nowElapsedMs: Long) {
        if (!recording || nowPlaying.title == lastTrackTitle) return
        lastTrackTitle = nowPlaying.title
        record(
            SessionEventKind.TRACK,
            nowElapsedMs - startedElapsedMs,
            trackTitle = nowPlaying.title,
            trackArtist = nowPlaying.artist,
            trackBand = nowPlaying.band,
            trackMediaId = nowPlaying.mediaStoreId,
        )
    }

    fun stop(nowElapsedMs: Long) {
        if (!recording) return
        recording = false
        val id = sessionId
        val duration = (nowElapsedMs - startedElapsedMs).coerceAtLeast(0)
        scope.launch {
            flush()
            if (id == null) return@launch
            dao.session(id)?.let {
                dao.updateSession(
                    it.copy(endedAtEpochMs = System.currentTimeMillis(), durationMs = duration),
                )
            }
            // A session with no events at all is noise in the history list.
            runCatching { dao.pruneEmpty() }
        }
    }

    private fun record(
        kind: SessionEventKind,
        tMs: Long,
        state: RideState? = null,
        bandLow: Int? = null,
        bandHigh: Int? = null,
        trackTitle: String? = null,
        trackArtist: String? = null,
        trackBand: Int? = null,
        trackMediaId: Long? = null,
        overrideMode: String? = null,
        hrBpm: Int? = null,
        speedKmh: Double? = null,
    ) {
        pending += SessionEventEntity(
            sessionId = 0, // filled in at flush, once the session row exists
            tMs = tMs.coerceAtLeast(0),
            kind = kind,
            state = state,
            bandLow = bandLow,
            bandHigh = bandHigh,
            trackTitle = trackTitle,
            trackArtist = trackArtist,
            trackBand = trackBand,
            trackMediaId = trackMediaId,
            overrideMode = overrideMode,
            hrBpm = hrBpm,
            speedKmh = speedKmh,
        )
        if (sessionId != null) scope.launch { flush() }
    }

    private suspend fun flush() {
        val id = sessionId ?: return
        if (pending.isEmpty()) return
        val batch = pending.map { it.copy(sessionId = id) }
        pending.clear()
        runCatching { dao.insertEvents(batch) }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 5_000L
    }
}
