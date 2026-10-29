package com.flowstate.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DownhillSkiing
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.ui.graphics.vector.ImageVector
import com.flowstate.engine.Activity

/**
 * A mark per activity, used inside the existing coloured ring rather than as a new
 * element — the design keeps mode colour as the only saturated channel, so identity has
 * to come from the shape already on screen, not from adding another one.
 */
val Activity.glyph: ImageVector
    get() = when (this) {
        Activity.SNOWBOARDING -> Icons.Rounded.DownhillSkiing
        Activity.CYCLING -> Icons.AutoMirrored.Rounded.DirectionsBike
        Activity.WALKING -> Icons.AutoMirrored.Rounded.DirectionsWalk
        Activity.RUNNING -> Icons.AutoMirrored.Rounded.DirectionsRun
        Activity.STRENGTH -> Icons.Rounded.FitnessCenter
        Activity.HIIT -> Icons.Rounded.Bolt
        Activity.GENERIC -> Icons.Rounded.Sensors
    }

/**
 * What an activity is called, everywhere. There used to be four versions of this — the
 * wizard's, the Home card's, the activity sheet's, and raw enum names on the session and
 * recap headers — which is how Home came to read "Hiit" and the session screen "GENERIC"
 * for the card the wizard calls "Anything else".
 */
fun Activity.label(): String = when (this) {
    Activity.SNOWBOARDING -> "Ski / Snowboard"
    Activity.CYCLING -> "Cycling"
    Activity.WALKING -> "Walking"
    Activity.RUNNING -> "Running"
    Activity.STRENGTH -> "Strength"
    Activity.HIIT -> "HIIT"
    Activity.GENERIC -> "Anything else"
}

/** The label as a header ("Ski / Snowboard · 1:42:10"): the label itself, now in sentence case. */
fun Activity.header(): String = label()

/** The modes the profile switches between, as the wizard's cards list them. */
fun Activity.modesLine(): String = when (this) {
    Activity.SNOWBOARDING -> "Riding · Lift · Break"
    Activity.CYCLING -> "Effort · Cruise"
    Activity.WALKING -> "Brisk · Stroll"
    Activity.RUNNING -> "Push · Cruise · Walk"
    Activity.STRENGTH -> "Sets and rests on a clock"
    Activity.HIIT -> "Intervals on a clock"
    Activity.GENERIC -> "Active · Easy · Rest"
}

/**
 * What each mode maps to, so the Home card says what a session will actually do — as a
 * sentence, where it used to be a row of capitals and arrows.
 */
fun Activity.mapping(): String = when (this) {
    Activity.SNOWBOARDING -> "Riding is Hype, the lift is Chill, a break is Mid"
    Activity.CYCLING -> "Pushing is Hype, cruising is Chill"
    Activity.WALKING -> "Striding out is Hype, strolling is Chill"
    Activity.RUNNING -> "Pushing is Hype, cruising Mid, walking Chill"
    Activity.STRENGTH -> "Sets are Hype, rests are Chill"
    Activity.HIIT -> "Work is Hype, rest is Chill"
    Activity.GENERIC -> "Working hard is Hype, easy is Mid, still is Chill"
}

/** What the profile actually watches for, in one line. */
fun Activity.blurb(): String = when (this) {
    Activity.SNOWBOARDING ->
        "Riding gets the hype, the chairlift chills out, standing around stays mid."
    Activity.CYCLING ->
        "Cruising under ~15 km/h keeps it chill; pushing the pace, with your pulse " +
            "following, brings the hype."
    Activity.WALKING ->
        "Stepping out — over ~5.4 km/h, or a clear stride with the phone pocketed — " +
            "brings the hype once your pulse is past ~78 bpm."
    Activity.RUNNING ->
        "Your stride leads, so it works with no GPS fix. Sprinting or a hard pulse brings " +
            "the hype, dropping to a walk brings the chill."
    Activity.STRENGTH ->
        "A work/rest clock drives the music indoors — you pick it before the session. " +
            "Sets get the hype, rests chill out."
    Activity.HIIT ->
        "Interval clock: work is flat out, rest is chill. Pick Tabata, 30/30 or your own " +
            "before starting."
    Activity.GENERIC ->
        "Motion only, no assumptions. Moving hard gets the hype, going still chills out."
}

/** Strength and HIIT are clock-driven, so they pick a plan (D4) before the session. */
fun Activity.usesTimer(): Boolean = this == Activity.STRENGTH || this == Activity.HIIT

/** Indoor clock sessions have no speed worth reporting. */
fun Activity.reportsSpeed(): Boolean = !usesTimer()

