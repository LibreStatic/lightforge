package com.librestatic.lightforge.core.preferences

import kotlin.math.abs

object VideoResumePolicy {
    private const val MinimumResumeMillis = 3_000L
    private const val EndMarginMillis = 5_000L
    private const val DurationToleranceMillis = 2_000L

    fun restoredPosition(
        enabled: Boolean,
        positionMillis: Long,
        savedDurationMillis: Long,
        currentDurationMillis: Long,
    ): Long? {
        if (!enabled) return null
        val duration = currentDurationMillis.takeIf { it > 0L } ?: savedDurationMillis
        if (duration > 0L && abs(savedDurationMillis - duration) > DurationToleranceMillis) return null
        return positionMillis.takeIf { isResumable(it, duration) }
    }

    fun shouldPersist(enabled: Boolean, positionMillis: Long, durationMillis: Long): Boolean =
        enabled && isResumable(positionMillis, durationMillis)

    private fun isResumable(positionMillis: Long, durationMillis: Long): Boolean =
        positionMillis >= MinimumResumeMillis &&
            (durationMillis <= 0L || positionMillis < durationMillis - EndMarginMillis)
}
