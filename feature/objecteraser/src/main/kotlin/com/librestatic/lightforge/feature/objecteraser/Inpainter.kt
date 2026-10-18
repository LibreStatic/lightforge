package com.librestatic.lightforge.feature.objecteraser

import com.google.ai.edge.litert.Accelerator
import java.io.Closeable

/** A 512x512 inpainting model, owned and used on one thread ([InpaintingSession] provides it). */
interface Inpainter : Closeable {
    val accelerator: Accelerator

    /**
     * Fills the pixels of the 512x512 [argb] image where [keep] is false; returns the whole generated image.
     *
     * [seed] makes stochastic models repeatable: the same image, mask and seed give the same result. Models
     * without randomness ignore it. [ensureActive] may throw to abandon a long run between its steps.
     * The returned array must not be modified by the caller.
     */
    fun inpaint(argb: IntArray, keep: BooleanArray, seed: Long = 0L, ensureActive: () -> Unit = {}): IntArray
}
