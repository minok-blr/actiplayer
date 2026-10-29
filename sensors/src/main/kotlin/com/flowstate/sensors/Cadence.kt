package com.flowstate.sensors

/**
 * Steps per minute from peaks in the acceleration magnitude.
 *
 * A gait is the clearest thing in this signal — far cleaner than the speed a fix gives at
 * running pace — and it keeps working on a treadmill, under trees or in a tunnel where
 * there is no fix at all. Peaks must clear [PEAK_MS2] and sit [MIN_STEP_GAP_MS] apart,
 * which caps the reading at a plausible 240 spm and stops a single jolt from registering
 * as a burst of steps.
 *
 * Pure, so the arithmetic is tested rather than trusted. It was not, and for all of
 * phase 6 the pipeline kept only 5 s of samples while this asked for at least 5 s of a
 * 10 s window — so it returned null on almost every frame and the running profile never
 * saw a cadence on a real phone. [RETENTION_MS] is what the buffer must now keep.
 */
internal object Cadence {

    const val WINDOW_MS = 10_000L

    /** How long the pipeline must keep acceleration samples for [spm] to have a window. */
    const val RETENTION_MS = WINDOW_MS + 1_000L

    /** m/s² a peak must clear to count as a footfall rather than jitter. */
    const val PEAK_MS2 = 1.5

    /** 250 ms floor between steps caps the reading at 240 spm. */
    const val MIN_STEP_GAP_MS = 250L

    /**
     * @param samples (elapsedRealtime ms, |linear acceleration|) oldest first.
     * @return steps per minute over the last [WINDOW_MS], or null with too little data.
     */
    fun spm(samples: List<Pair<Long, Double>>, now: Long): Double? {
        val window = samples.filter { now - it.first <= WINDOW_MS }
        if (window.size < 12) return null
        val spanMs = window.last().first - window.first().first
        if (spanMs < WINDOW_MS / 2) return null

        var steps = 0
        var lastStepAt = Long.MIN_VALUE
        for (i in 1 until window.size - 1) {
            val (t, v) = window[i]
            val isPeak = v > window[i - 1].second && v >= window[i + 1].second
            if (!isPeak || v < PEAK_MS2) continue
            if (lastStepAt != Long.MIN_VALUE && t - lastStepAt < MIN_STEP_GAP_MS) continue
            steps++
            lastStepAt = t
        }
        return steps * 60_000.0 / spanMs
    }
}
