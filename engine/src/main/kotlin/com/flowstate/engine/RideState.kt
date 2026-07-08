package com.flowstate.engine

/**
 * All engine states across activity profiles. RIDING / LIFT / PAUSED belong to the
 * snowboard profile (build brief §7); CRUISE / EFFORT to the cycling profile. One flat
 * enum keeps [StateDecision] simple — each engine only ever emits its own subset.
 */
enum class RideState { RIDING, LIFT, PAUSED, CRUISE, EFFORT }

/**
 * The engine's output. [reason] is a human-readable explanation of exactly which
 * conditions fired — surfaced in the debug UI and, later, the session log. An adaptive
 * player that can't explain itself is an adaptive player nobody trusts.
 */
data class StateDecision(
    val state: RideState,
    val targetEnergy: IntRange,
    val reason: String,
    val atMillis: Long,
)
