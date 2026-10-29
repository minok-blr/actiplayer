package com.flowstate.session

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.flowstate.data.MusicCatalog
import com.flowstate.data.SessionPool
import com.flowstate.data.SettingsStore
import com.flowstate.data.SourceKind
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.data.di.ApplicationScope
import com.flowstate.engine.Activity
import com.flowstate.engine.ActivityEngine
import com.flowstate.engine.CyclingEngine
import com.flowstate.engine.EngineConfig
import com.flowstate.engine.GenericEngine
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.StateDecision
import com.flowstate.engine.RunningEngine
import com.flowstate.engine.StateEngine
import com.flowstate.engine.TimerEngine
import com.flowstate.engine.TimerPlan
import com.flowstate.engine.TimerState
import com.flowstate.engine.WalkingEngine
import com.flowstate.playback.LocalSource
import com.flowstate.playback.LocalTrack
import com.flowstate.playback.MusicSource
import com.flowstate.playback.NowPlaying
import com.flowstate.playback.PlaybackService
import com.flowstate.playback.SpotifyQueueController
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.SensorPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Manual pin: the permanent trust valve from plan §2. AUTO = engine drives. */
enum class OverrideMode { CHILL, AUTO, HYPE }

data class SessionUiState(
    val active: Boolean = false,
    val override: OverrideMode = OverrideMode.AUTO,
    val decision: StateDecision? = null,
    val lastFrame: FeatureFrame? = null,
    val effectiveBand: IntRange = 2..3,
    val hasBarometer: Boolean = true,
    val activity: Activity = Activity.SNOWBOARDING,
    val source: SourceKind = SourceKind.LOCAL,
    /** Non-null once a session has built its pool; drives Home's readiness copy. */
    val pool: SessionPool? = null,
    /** Non-null only for the clock-driven profiles (Strength, HIIT). */
    val timer: TimerState? = null,
    val timerPaused: Boolean = false,
    /** Monotonic clock, on the same timebase as [FeatureFrame.tMillis]. */
    val startedAtMs: Long? = null,
)

/**
 * Owns session lifecycle and wires the core loop:
 *
 *   SensorPipeline (+ BLE HR) -> FeatureFrame @1Hz -> ActivityEngine -> band -> Queue -> player
 *
 * Everything runs on the injected application scope, which is the main dispatcher:
 * sensors, engine, and player are single-threaded by design, which removes a whole class
 * of concurrency bugs (DECISIONS.md).
 */
@Singleton
class SessionCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val catalog: MusicCatalog,
    private val heartRate: HeartRateMonitor,
    private val settings: SettingsStore,
    private val spotifyRatings: SpotifyRatingsRepository,
    val local: LocalSource,
    val spotifyQueue: SpotifyQueueController,
    private val recorder: SessionRecorder,
) {
    private val pipeline = SensorPipeline(context, scope, heartRate) { settings.maxHr.value }

    /** Chosen per session from the activity setting; each activity has its own profile. */
    private var engine: ActivityEngine = StateEngine()

    /** Chosen per session from the music-source setting; the engine drives it blind. */
    private var backend: MusicSource = local

    private val _state = MutableStateFlow(SessionUiState(hasBarometer = pipeline.hasBarometer))
    val state: StateFlow<SessionUiState> = _state

    /**
     * What is playing, whichever source this session picked. The screens should never
     * have to know which backend is wired up — that is the whole point of [MusicSource].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlaying: StateFlow<NowPlaying?> = _state
        .map { it.source }
        .distinctUntilChanged()
        .flatMapLatest { source ->
            when (source) {
                SourceKind.LOCAL -> local.nowPlaying
                SourceKind.SPOTIFY -> spotifyQueue.nowPlaying
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Whether the listener has paused, whichever source is playing — from the session
     * screen, the notification, a headset, or another app taking the audio.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val paused: StateFlow<Boolean> = _state
        .map { it.source }
        .distinctUntilChanged()
        .flatMapLatest { source ->
            when (source) {
                SourceKind.LOCAL -> local.paused
                SourceKind.SPOTIFY -> spotifyQueue.paused
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var loopJob: Job? = null
    private var trackJob: Job? = null
    private var pauseJob: Job? = null

    /**
     * Set from the tap until the session is live. Building the pool is asynchronous, so
     * `active` alone let a double-tap on Start launch two sessions — two recordings, and
     * a second frame collector that outlived the session and fed every later one twice.
     */
    private var starting = false

    fun startSession() {
        if (_state.value.active || starting) return
        starting = true
        scope.launch {
            try {
                begin()
            } finally {
                // Whatever happened, a failed start must not lock Start out for good.
                starting = false
            }
        }
    }

    private suspend fun begin() {
        val source = settings.musicSource.value
        var pool: SessionPool? = null
        backend = when (source) {
            SourceKind.LOCAL -> {
                pool = catalog.sessionPool()
                local.setPool(
                    pool.tracks.map {
                        LocalTrack(
                            id = it.track.id,
                            band = it.band,
                            uri = it.track.uri,
                            title = it.track.title,
                            artist = it.track.artist,
                        )
                    },
                )
                // Plain startService from the foreground; Media3 promotes the service
                // to a typed FGS itself once playback starts.
                context.startService(Intent(context, PlaybackService::class.java))
                local
            }
            SourceKind.SPOTIFY -> {
                spotifyQueue.setPool(spotifyRatings.ratedTracks())
                spotifyQueue.start()
                // No local playback to promote the service, so ask it to take the
                // location-type foreground itself — sensors must survive the pocket.
                context.startService(
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_SENSOR_FOREGROUND),
                )
                spotifyQueue
            }
        }
        val activity = settings.activity.value
        // Sensitivity scales only the down-switch windows; see EngineConfig.down().
        val config = EngineConfig(downSwitchScale = settings.sensitivity.value.scale)
        engine = when (activity) {
            Activity.CYCLING -> CyclingEngine(config)
            Activity.WALKING -> WalkingEngine(config)
            Activity.RUNNING -> RunningEngine(config)
            Activity.GENERIC -> GenericEngine(config)
            Activity.STRENGTH, Activity.HIIT -> {
                val (work, rest, rounds) = settings.timerPlan(activity).value
                TimerEngine(
                    TimerPlan(work, rest, rounds),
                    config,
                    workEnergy = if (activity == Activity.HIIT) {
                        config.hiitWorkEnergy
                    } else {
                        config.timerWorkEnergy
                    },
                )
            }
            Activity.SNOWBOARDING -> StateEngine(config)
        }
        engine.reset()
        // No-op unless a monitor was set up, and deliberately unforced: an already
        // connected strap must survive session start. HR is always optional.
        heartRate.connect()
        pipeline.start()
        _state.update {
            it.copy(
                active = true,
                decision = null,
                override = OverrideMode.AUTO,
                activity = activity,
                source = source,
                pool = pool,
                startedAtMs = SystemClock.elapsedRealtime(),
            )
        }
        recorder.start(activity, source, SystemClock.elapsedRealtime())
        local.modeLabel = null
        applyBand(force = true, wentUp = false)
        loopJob = scope.launch {
            pipeline.frames.filterNotNull().collect { frame -> onFrame(frame) }
        }
        trackJob = scope.launch {
            nowPlaying.filterNotNull().distinctUntilChanged().collect {
                recorder.onTrack(it, SystemClock.elapsedRealtime())
            }
        }
        // One pause, whoever pressed it: on a clock-driven session the clock holds
        // with the music, so a pause from the notification cannot leave the set
        // running on without its soundtrack.
        pauseJob = scope.launch {
            backend.paused.collect { isPaused ->
                val timer = engine as? TimerEngine ?: return@collect
                if (isPaused) timer.pause() else timer.resume()
                _state.update { it.copy(timerPaused = isPaused) }
            }
        }
    }

    fun stopSession() {
        if (!_state.value.active) return
        recorder.stop(SystemClock.elapsedRealtime())
        loopJob?.cancel()
        loopJob = null
        trackJob?.cancel()
        trackJob = null
        pauseJob?.cancel()
        pauseJob = null
        pipeline.stop()
        heartRate.disconnect() // saves watch + phone battery between sessions
        backend.stop()
        context.stopService(Intent(context, PlaybackService::class.java))
        _state.update { it.copy(active = false) }
    }

    fun skip() = backend.skip()

    /** How far through the current track, if the active source can say. */
    fun progress(): Float? = backend.progress()

    /**
     * Pause and resume for every session. On the clock-driven profiles the clock follows
     * (see the pause job in [startSession]) — B4's PAUSE is this same call.
     */
    fun togglePause() {
        if (!_state.value.active) return
        if (paused.value) backend.resume() else backend.pause()
    }

    // ---------------------------------------------------------------- timer controls

    /** "Tap anywhere to end the set early", and its undo. */
    fun endIntervalEarly() = (engine as? TimerEngine)?.endIntervalEarly() ?: Unit

    fun undoEndEarly() = (engine as? TimerEngine)?.undoEndEarly() ?: Unit

    fun setOverride(mode: OverrideMode) {
        val prevBand = _state.value.effectiveBand
        recorder.onOverride(mode, SystemClock.elapsedRealtime())
        _state.update { it.copy(override = mode) }
        applyBand(force = false, wentUp = bandFor().first > prevBand.first)
    }

    private fun onFrame(frame: FeatureFrame) {
        val decision = engine.onFrame(frame)
        val prevBand = _state.value.effectiveBand
        local.modeLabel = decision.state.label(_state.value.activity)
        val timer = engine as? TimerEngine
        _state.update {
            it.copy(
                decision = decision,
                lastFrame = frame,
                timer = timer?.state,
                timerPaused = timer?.isPaused == true,
            )
        }
        recorder.onFrame(decision.state, bandFor(), frame)
        val newBand = bandFor()
        if (newBand != prevBand) {
            applyBand(force = false, wentUp = newBand.first > prevBand.first)
        }
    }

    private fun bandFor(): IntRange {
        val s = _state.value
        return when (s.override) {
            OverrideMode.CHILL -> 1..2
            OverrideMode.HYPE -> 4..5
            OverrideMode.AUTO -> s.decision?.targetEnergy ?: (2..3)
        }
    }

    private fun applyBand(force: Boolean, wentUp: Boolean) {
        val band = bandFor()
        _state.update { it.copy(effectiveBand = band) }
        backend.play(band, fast = wentUp, force = force)
    }
}
