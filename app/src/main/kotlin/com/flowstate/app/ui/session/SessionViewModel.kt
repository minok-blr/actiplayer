package com.flowstate.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.data.SettingsStore
import com.flowstate.data.SourceKind
import com.flowstate.session.OverrideMode
import com.flowstate.session.SessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay

/**
 * The one ViewModel the session screens talk to (plan §3.6).
 *
 * It exists to keep [SessionUiState] the whole contract: B1 and B2/B3 never touch the
 * coordinator, the engine or a player, so the same screens work whichever source is
 * wired up and phase 7's Spotify router changes nothing here.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val coordinator: SessionCoordinator,
    private val settings: SettingsStore,
) : ViewModel() {

    /**
     * Track progress and elapsed time are the only things that move between 1 Hz frames,
     * and both are cheap to sample. Polling beats pushing here: an idle session ticks
     * nothing, and there is no player state to mirror.
     */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    private val playback = combine(
        coordinator.state,
        coordinator.nowPlaying,
        coordinator.paused,
    ) { session, playing, paused -> Triple(session, playing, paused) }

    val state: StateFlow<SessionUiState> =
        combine(
            playback,
            settings.showSignals,
            settings.units,
            ticker,
        ) { (session, playing, paused), showSignals, units, _ ->
            val decision = session.decision
            val startedAt = session.startedAtMs
            val frameAt = session.lastFrame?.tMillis
            val activity = session.activity

            SessionUiState(
                phase = when {
                    !session.active -> Phase.IDLE
                    decision == null -> Phase.STARTING
                    else -> Phase.ACTIVE
                },
                activity = activity,
                mode = decision?.let {
                    ModeUi(
                        name = it.state.modeName(activity),
                        color = it.state.modeColor(activity),
                        sentence = ReasonFormatter.sentence(it.reason, activity),
                        contributing = it.reason.contributing,
                    )
                },
                band = session.effectiveBand,
                override = session.override,
                nowPlaying = playing?.let {
                    NowPlayingUi(
                        it.title,
                        it.artist,
                        it.band,
                        coordinator.progress(),
                        it.mediaStoreId,
                    )
                },
                elapsed = if (startedAt == null || frameAt == null) {
                    kotlin.time.Duration.ZERO
                } else {
                    (frameAt - startedAt).coerceAtLeast(0).milliseconds
                },
                signals = SignalsUi.from(session.lastFrame, units),
                sourceLine = sourceLine(session.source, session.pool?.fallback == true),
                sourceIsSpotify = session.source == SourceKind.SPOTIFY,
                showSignals = showSignals,
                debugReason = decision?.reason?.toDebugString(),
                hasBarometer = session.hasBarometer,
                paused = paused,
                timer = session.timer?.let { timer ->
                    val plan = settings.timerPlan(activity).value
                    TimerUi(
                        working = timer.working,
                        round = timer.round,
                        totalRounds = timer.totalRounds,
                        remainingMs = timer.remainingMs,
                        progress = timer.progress,
                        paused = session.timerPaused,
                        finished = timer.finished,
                        nextLabel = nextLabel(timer, activity, plan.first, plan.second),
                        planLine = planLine(plan.first, plan.second),
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUiState())

    fun start() = coordinator.startSession()

    fun end() = coordinator.stopSession()

    fun skip() = coordinator.skip()

    fun setOverride(mode: OverrideMode) = coordinator.setOverride(mode)

    /** Pause and resume, for every session; on B4 the clock holds with the music. */
    fun togglePause() = coordinator.togglePause()

    fun endIntervalEarly() = coordinator.endIntervalEarly()

    fun undoEndEarly() = coordinator.undoEndEarly()

    /**
     * What the athlete is about to be handed and for how long — "REST 1:30" — so B4 can
     * promise it before it arrives, as drawn. EMOM has no rest block: after the work comes
     * the next minute.
     */
    private fun nextLabel(
        timer: com.flowstate.engine.TimerState,
        activity: com.flowstate.engine.Activity,
        workMs: Long,
        restMs: Long,
    ): String {
        val work = com.flowstate.engine.RideState.WORK.modeLabel(activity).lowercase()
        val lastRound = timer.round >= timer.totalRounds
        return when {
            timer.finished -> DONE
            timer.working && restMs > 0 -> "${restMs.clock()} rest"
            lastRound -> DONE
            else -> "${workMs.clock()} $work"
        }
    }

    /** "30/90", or "EMOM" when there is no rest block. */
    private fun planLine(workMs: Long, restMs: Long): String =
        if (restMs == 0L) "EMOM" else "${workMs / 1000}/${restMs / 1000}"

    private fun Long.clock(): String {
        val total = this / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /**
     * Shown above the now-playing card only when there is something to say: which service
     * is playing, or that the session is running on a fallback pool rather than the one
     * the user meant to build.
     */
    private fun sourceLine(source: SourceKind, fallback: Boolean): String? = when {
        fallback -> "No pools set up, so everything plays at mid"
        source == SourceKind.SPOTIFY -> "Playing through Spotify"
        else -> null
    }

    companion object {
        private const val TICK_MS = 500L

        /** [TimerUi.nextLabel] when nothing follows: the plan ends after this interval. */
        const val DONE = "Done"
    }
}
