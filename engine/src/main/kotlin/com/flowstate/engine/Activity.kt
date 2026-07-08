package com.flowstate.engine

/**
 * What the athlete is doing. Each activity gets its own state machine profile; the
 * player around it (library, queue, HR plumbing) is activity-agnostic.
 *
 * SNOWBOARDING and CYCLING are implemented; the rest are menu placeholders until they
 * get their own profiles.
 */
enum class Activity { SNOWBOARDING, CYCLING, RUNNING, HIKING }

/** One activity's state machine: 1 Hz frames in, banded decisions out. */
interface ActivityEngine {
    fun onFrame(frame: FeatureFrame): StateDecision
    fun reset()
}
