package com.flowstate.engine

/**
 * All engine states across activity profiles. One flat enum keeps [StateDecision]
 * simple — each engine only ever emits its own subset, and the UI names them per
 * activity (a timer's WORK reads as SET under Strength).
 */
enum class RideState {
    RIDING, LIFT, PAUSED, // snowboard
    CRUISE, EFFORT, // cycling (CRUISE is shared with running)
    STROLL, BRISK, // walking
    PUSH, WALK, // running
    ACTIVE, EASY, // generic
    WORK, REST, // timer-driven; REST is shared with generic
}

/**
 * The engine's output. [reason] carries exactly which conditions fired, in a structured
 * form the UI can turn into a sentence and a set of lit signal chips — an adaptive player
 * that can't explain itself is an adaptive player nobody trusts.
 */
data class StateDecision(
    val state: RideState,
    val targetEnergy: IntRange,
    val reason: Reason,
    val atMillis: Long,
)
