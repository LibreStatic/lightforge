package com.librestatic.lightforge.feature.collage

import kotlin.math.roundToLong

/**
 * Per-frame timing of a created GIF, persisted as one Int so drafts and publication journals
 * written before frame rates existed stay readable: 1..5 are the original "seconds per frame"
 * choices, and `1000 + N` means N frames per second.
 */
object GifFrameTiming {
    val SecondChoices = 1..5
    val FpsChoices = listOf(5, 15, 24, 25, 30, 60, 120)
    const val Default = 2
    private const val FpsBase = 1000

    /**
     * GIF stores delays in 1/100 s and common players slow anything under 2/100 s down to about
     * 10 FPS, so 50 FPS is the fastest rate a GIF plays back reliably.
     */
    const val MaxReliableFps = 50

    fun fps(framesPerSecond: Int): Int = FpsBase + framesPerSecond
    fun isValid(code: Int): Boolean = code in SecondChoices || fpsOf(code) in FpsChoices
    fun fpsOf(code: Int): Int? = (code - FpsBase).takeIf { code > FpsBase }

    /** The rate the file will actually play at, or null for seconds-per-frame timings. */
    fun effectiveFps(code: Int): Int? = fpsOf(code)?.coerceAtMost(MaxReliableFps)

    /**
     * Frame delays in milliseconds (multiples of 10, as GIF stores them). Frame rates that do not
     * divide 100 spread the rounding over consecutive frames, so 15, 24 and 30 FPS keep their exact
     * average rate instead of drifting to the nearest 1/100 s.
     */
    fun delaysMillis(code: Int, frames: Int): List<Int> {
        require(isValid(code) && frames >= 0)
        val fps = effectiveFps(code) ?: return List(frames) { code * 1000 }
        fun centis(frame: Int) = (frame * 100.0 / fps).roundToLong()
        return List(frames) { ((centis(it + 1) - centis(it)) * 10).toInt() }
    }

    fun totalMillis(code: Int, frames: Int): Int = delaysMillis(code, frames).sum()
}
