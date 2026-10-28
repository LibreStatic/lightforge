package com.librestatic.lightforge.core.model

/** An external condition holding an analysis back; user pauses are not reported here. */
enum class AnalysisWait { Battery, Heat }

/** Distinct-photo progress of one analysis pipeline; [etaMillis] is null while unknown or not running. */
data class AnalysisProgress(
    val processed: Long,
    val total: Long,
    val remaining: Long,
    val etaMillis: Long? = null,
    val running: Boolean = false,
    val waitReason: AnalysisWait? = null,
) {
    val complete: Boolean get() = total > 0 && remaining == 0L

    companion object {
        val Empty = AnalysisProgress(0, 0, 0)

        /** Shared by every pipeline: [completionTimes] are the `completedAtMillis` stamps inside the ETA window. */
        fun of(
            processed: Long,
            total: Long,
            running: Boolean,
            waitReason: AnalysisWait?,
            completionTimes: List<Long>,
            nowMillis: Long,
        ): AnalysisProgress {
            val done = processed.coerceIn(0, total.coerceAtLeast(0))
            val remaining = (total - done).coerceAtLeast(0)
            return AnalysisProgress(
                processed = done,
                total = total.coerceAtLeast(0),
                remaining = remaining,
                etaMillis = AnalysisEta.estimate(completionTimes, nowMillis, remaining, running && waitReason == null),
                running = running,
                waitReason = waitReason,
            )
        }
    }
}

/** Sliding-window throughput estimate from per-item completion timestamps. */
object AnalysisEta {
    const val WindowMillis = 10 * 60_000L
    const val MinSamples = 20
    const val MinSpanMillis = 30_000L
    /** A newest sample older than this means the rate no longer describes the present. */
    const val MaxStaleMillis = 2 * 60_000L

    fun estimate(
        completionTimes: List<Long>,
        nowMillis: Long,
        remaining: Long,
        running: Boolean,
        windowMillis: Long = WindowMillis,
        minSamples: Int = MinSamples,
        minSpanMillis: Long = MinSpanMillis,
    ): Long? {
        if (!running || remaining <= 0) return null
        val window = completionTimes.filter { it in (nowMillis - windowMillis)..nowMillis }
        if (window.size < minSamples) return null
        val first = window.min()
        val last = window.max()
        val span = last - first
        if (span < minSpanMillis || nowMillis - last > MaxStaleMillis) return null
        val perMilli = (window.size - 1).toDouble() / span
        return (remaining / perMilli).toLong()
    }

    /** Whole minutes under 10 min, 5-minute steps under an hour, half hours beyond; never below one minute. */
    fun roundCoarse(millis: Long): Long {
        val minute = 60_000L
        val step = when {
            millis < 10 * minute -> minute
            millis < 60 * minute -> 5 * minute
            else -> 30 * minute
        }
        return ((millis + step / 2) / step * step).coerceAtLeast(minute)
    }
}
