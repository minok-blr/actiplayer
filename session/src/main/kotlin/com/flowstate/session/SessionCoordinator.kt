package com.flowstate.session

import android.content.Context
import android.content.Intent
import com.flowstate.data.MediaLibrary
import com.flowstate.data.MusicSource
import com.flowstate.data.RatingsRepository
import com.flowstate.data.SettingsStore
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.data.di.ApplicationScope
import com.flowstate.engine.Activity
import com.flowstate.engine.ActivityEngine
import com.flowstate.engine.CyclingEngine
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.StateDecision
import com.flowstate.engine.StateEngine
import com.flowstate.playback.PlaybackBackend
import com.flowstate.playback.PlaybackService
import com.flowstate.playback.QueueController
import com.flowstate.playback.SpotifyQueueController
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.SensorPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
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
    val source: MusicSource = MusicSource.LOCAL,
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
    private val library: MediaLibrary,
    private val ratings: RatingsRepository,
    private val heartRate: HeartRateMonitor,
    private val settings: SettingsStore,
    private val spotifyRatings: SpotifyRatingsRepository,
    val queue: QueueController,
    val spotifyQueue: SpotifyQueueController,
) {
    private val pipeline = SensorPipeline(context, scope, heartRate) { settings.maxHr.value }

    /** Chosen per session from the activity setting; each activity has its own profile. */
    private var engine: ActivityEngine = StateEngine()

    /** Chosen per session from the music-source setting; the engine drives it blind. */
    private var backend: PlaybackBackend = queue

    private val _state = MutableStateFlow(SessionUiState(hasBarometer = pipeline.hasBarometer))
    val state: StateFlow<SessionUiState> = _state

    private var loopJob: Job? = null

    fun startSession() {
        if (_state.value.active) return
        scope.launch {
            val source = settings.musicSource.value
            backend = when (source) {
                MusicSource.LOCAL -> {
                    val ratingMap = ratings.snapshot()
                    val pool = library.loadTracks().map {
                        QueueController.RatedTrack(
                            it,
                            RatingsRepository.effectiveRating(ratingMap, it.id),
                        )
                    }
                    queue.setPool(pool)
                    // Plain startService from the foreground; Media3 promotes the service
                    // to a typed FGS itself once playback starts.
                    context.startService(Intent(context, PlaybackService::class.java))
                    queue
                }
                MusicSource.SPOTIFY -> {
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
            engine = when (activity) {
                Activity.CYCLING -> CyclingEngine()
                else -> StateEngine() // snowboard profile is the default until others land
            }
            engine.reset()
            heartRate.connect() // no-op unless a monitor was set up; HR is always optional
            pipeline.start()
            _state.update {
                it.copy(
                    active = true,
                    decision = null,
                    override = OverrideMode.AUTO,
                    activity = activity,
                    source = source,
                )
            }
            applyBand(force = true, wentUp = false)
            loopJob = launch {
                pipeline.frames.filterNotNull().collect { frame -> onFrame(frame) }
            }
        }
    }

    fun stopSession() {
        if (!_state.value.active) return
        loopJob?.cancel()
        loopJob = null
        pipeline.stop()
        heartRate.disconnect() // saves watch + phone battery between sessions
        backend.stop()
        context.stopService(Intent(context, PlaybackService::class.java))
        _state.update { it.copy(active = false) }
    }

    fun skip() = backend.skip()

    fun setOverride(mode: OverrideMode) {
        val prevBand = _state.value.effectiveBand
        _state.update { it.copy(override = mode) }
        applyBand(force = false, wentUp = bandFor().first > prevBand.first)
    }

    private fun onFrame(frame: FeatureFrame) {
        val decision = engine.onFrame(frame)
        val prevBand = _state.value.effectiveBand
        _state.update { it.copy(decision = decision, lastFrame = frame) }
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
        backend.onBand(band, energyWentUp = wentUp, force = force)
    }
}
