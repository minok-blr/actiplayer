package com.flowstate.session

import com.flowstate.engine.Activity
import com.flowstate.engine.RideState

/** The three energy tiers a user thinks in (plan §3.1): what a mode is *for*. */
enum class EnergyTier { HYPE, MID, CHILL }

/**
 * Which tier a mode belongs to, per activity.
 *
 * The engine's states are shared between profiles, and not always at the same energy:
 * CRUISE is the chill state on a bike but the middle one on a run. Colouring by state
 * alone painted a running cruise in Chill blue — the same colour as walking — so the
 * one channel the session screen relies on said the wrong thing. Everything that turns a
 * mode into a colour or a word goes through here.
 */
fun RideState.tier(activity: Activity): EnergyTier = when (this) {
    RideState.RIDING, RideState.EFFORT, RideState.BRISK, RideState.PUSH,
    RideState.ACTIVE, RideState.WORK,
    -> EnergyTier.HYPE

    RideState.PAUSED, RideState.EASY -> EnergyTier.MID
    RideState.CRUISE -> if (activity == Activity.RUNNING) EnergyTier.MID else EnergyTier.CHILL
    RideState.LIFT, RideState.STROLL, RideState.WALK, RideState.REST -> EnergyTier.CHILL
}

/**
 * What a mode is called on screen, in the notification and in the recap. Plan §3.3
 * renames PAUSED to BREAK for skiers, and Strength calls its work block a SET.
 */
fun RideState.displayName(activity: Activity): String = when (this) {
    RideState.RIDING -> "RIDING"
    RideState.LIFT -> "LIFT"
    RideState.PAUSED -> "BREAK"
    RideState.EFFORT -> "EFFORT"
    RideState.CRUISE -> "CRUISE"
    RideState.BRISK -> "BRISK"
    RideState.STROLL -> "STROLL"
    RideState.PUSH -> "PUSH"
    RideState.WALK -> "WALK"
    RideState.ACTIVE -> "ACTIVE"
    RideState.EASY -> "EASY"
    RideState.WORK -> if (activity == Activity.STRENGTH) "SET" else "WORK"
    RideState.REST -> "REST"
}

/**
 * The mode in sentence case — "Riding", "Break", "Set" — for everywhere smaller than the
 * session screen's headline: the notification, the recap, the mini player.
 */
fun RideState.label(activity: Activity): String =
    displayName(activity).lowercase().replaceFirstChar { it.uppercase() }

/** The states an activity can be in, hype first — the order recap tiles are laid out in. */
fun Activity.modes(): List<RideState> = when (this) {
    Activity.SNOWBOARDING -> listOf(RideState.RIDING, RideState.PAUSED, RideState.LIFT)
    Activity.CYCLING -> listOf(RideState.EFFORT, RideState.CRUISE)
    Activity.WALKING -> listOf(RideState.BRISK, RideState.STROLL)
    Activity.RUNNING -> listOf(RideState.PUSH, RideState.CRUISE, RideState.WALK)
    Activity.STRENGTH, Activity.HIIT -> listOf(RideState.WORK, RideState.REST)
    Activity.GENERIC -> listOf(RideState.ACTIVE, RideState.EASY, RideState.REST)
}
