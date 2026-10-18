package com.librestatic.lightforge.feature.objecteraser

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Erases with the best downloaded inpainting model: the generative one, then MI-GAN, then the explicit fallback.
 *
 * Each compiled model is opened on first use and kept until [close], on one dedicated thread so the GPU context
 * never changes threads. A model that fails to open or run is dropped and the next level is used, so an erase
 * never fails only because the accelerator did; [ObjectEraser.EraseResult.method] says which one ran.
 */
class InpaintingSession(context: Context) : Closeable {
    private class Level(val store: InpaintModelStore, val method: ObjectEraser.EraseMethod) {
        @Volatile var inpainter: Inpainter? = null
    }

    private val levels = listOf(
        Level(InpaintModelStore(context, InpaintModelCatalog.Generative), ObjectEraser.EraseMethod.GENERATIVE_INPAINTING),
        Level(InpaintModelStore(context, InpaintModelCatalog.Migan), ObjectEraser.EraseMethod.ML_INPAINTING),
    )
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "inpainting") }
    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun erase(bitmap: Bitmap, regions: List<ObjectEraser.EraseRegion>): ObjectEraser.EraseResult =
        withContext(dispatcher) {
            val eraser = ObjectEraser()
            for (level in levels) {
                val model = open(level) ?: continue
                try {
                    val context = coroutineContext
                    return@withContext eraser.inpaintRegions(bitmap, regions, model, { context.ensureActive() }, level.method)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(Tag, "Inpainting on ${model.accelerator} failed; trying the next", failure)
                    model.close()
                    level.inpainter = null
                }
            }
            eraser.eraseRegions(bitmap, regions)
        }

    /**
     * Opens the best installed model ahead of the first [erase], e.g. while the user paints: verifying and
     * compiling the generative package takes seconds. An [erase] issued meanwhile queues behind it.
     */
    /** True when an installed model is not open yet, so the first [erase] would wait for [prepare]. */
    fun needsPreparing(): Boolean = levels.none { it.inpainter != null } && levels.any { it.store.installed() }

    suspend fun prepare() {
        withContext(dispatcher) { levels.firstNotNullOfOrNull { open(it) } }
    }

    /** The level's compiled model, opened on first use; null when it is not installed or fails to open. */
    private fun open(level: Level): Inpainter? = level.inpainter ?: if (level.store.installed()) {
        runCatching { level.store.openInpainter() }
            .onFailure { Log.w(Tag, "Inpainting model ${level.store.spec.id} unavailable; trying the next", it) }
            .getOrNull()
            ?.also { level.inpainter = it }
    } else null

    override fun close() {
        executor.execute {
            levels.forEach { level ->
                level.inpainter?.close()
                level.inpainter = null
            }
        }
        dispatcher.close()
    }

    private companion object {
        const val Tag = "InpaintingSession"
    }
}
