package com.flowstate.app.session

import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import com.flowstate.app.audio.PlaybackService
import com.flowstate.app.audio.QueueController
import com.flowstate.app.ble.HeartRateMonitor
import com.flowstate.app.data.MediaLibrary
import com.flowstate.app.data.RatingsStore
import com.flowstate.app.data.SettingsStore
import com.flowstate.app.sensors.SensorPipeline
import com.flowstate.engine.Activity
import com.flowstate.engine.ActivityEngine
import com.flowstate.engine.CyclingEngine
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.StateDecision
import com.flowstate.engine.StateEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Manual pin: the permanent trust valve from build brief §7. AUTO = engine drives. */
enum class OverrideMode { CHILL, AUTO, HYPE }

data class SessionUiState(
    val active: Boolean = false,
    val override: OverrideMode = OverrideMode.AUTO,
    val decision: StateDecision? = null,
    val lastFrame: FeatureFrame? = null,
    val effectiveBand: IntRange = 2..3,
    val hasBarometer: Boolean = true,
    val activity: Activity = Activity.SNOWBOARDING,
)

/**
 * Owns session lifecycle and wires the loop from build brief §5:
 *
 *   SensorPipeline (+ BLE HR) -> FeatureFrame @1Hz -> StateEngine -> band -> Queue -> player
 *
 * Everything runs on the main dispatcher: sensors, engine, and player are single-threaded
 * by design in this MVP, which removes a whole class of concurrency bugs.
 */
class SessionCoordinator(
    private val context: Context,
    player: ExoPlayer,
    private val library: MediaLibrary,
    private val ratings: RatingsStore,
    private val heartRate: HeartRateMonitor,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pipeline = SensorPipeline(context, scope, heartRate) { settings.maxHr.value }

    /** Chosen per session from the activity setting; each activity has its own profile. */
    private var engine: ActivityEngine = StateEngine()
    val queue = QueueController(player, scope)

    private val _state = MutableStateFlow(SessionUiState(hasBarometer = pipeline.hasBarometer))
    val state: StateFlow<SessionUiState> = _state

    private var loopJob: Job? = null

    fun startSession() {
        if (_state.value.active) return
        scope.launch {
            val pool = library.loadTracks().map {
                QueueController.RatedTrack(it, ratings.effectiveRating(it.id))
            }
            queue.setPool(pool)
            val activity = settings.activity.value
            engine = when (activity) {
                Activity.CYCLING -> CyclingEngine()
                else -> StateEngine() // snowboard profile is the default until others land
            }
            engine.reset()
            // Plain startService from the foreground; Media3 promotes the service to a
            // typed FGS itself once playback starts.
            context.startService(Intent(context, PlaybackService::class.java))
            heartRate.connect() // no-op unless a monitor was set up; HR is always optional
            pipeline.start()
            _state.update {
                it.copy(
                    active = true,
                    decision = null,
                    override = OverrideMode.AUTO,
                    activity = activity,
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
        queue.stop()
        context.stopService(Intent(context, PlaybackService::class.java))
        _state.update { it.copy(active = false) }
    }

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
        queue.onBand(band, energyWentUp = wentUp, force = force)
    }
}
