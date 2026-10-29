package com.flowstate.engine

/**
 * What the athlete is doing. Each activity gets its own state machine profile; the
 * player around it (library, queue, HR plumbing) is activity-agnostic.
 *
 * Every activity has a working profile: motion-and-signal engines for the outdoor ones,
 * and a clock for STRENGTH and HIIT, where indoors there is no GPS or altitude to read.
 */
enum class Activity { SNOWBOARDING, CYCLING, WALKING, RUNNING, STRENGTH, HIIT, GENERIC }

/** One activity's state machine: 1 Hz frames in, banded decisions out. */
interface ActivityEngine {
    fun onFrame(frame: FeatureFrame): StateDecision
    fun reset()
}
