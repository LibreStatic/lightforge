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
 * Erases with the downloaded inpainting model when it is installed, and with the explicit fallback otherwise.
 *
 * The compiled model is opened on first use and kept until [close], on one dedicated thread so the GPU context
 * never changes threads. A model that fails to open or run is dropped and the fallback is used, so an erase
 * never fails only because the accelerator did; [ObjectEraser.EraseResult.method] says which one ran.
 */
class InpaintingSession(context: Context) : Closeable {
    private val store = InpaintModelStore(context)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "inpainting") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var inpainter: MiganInpainter? = null

    suspend fun erase(bitmap: Bitmap, regions: List<ObjectEraser.EraseRegion>): ObjectEraser.EraseResult =
        withContext(dispatcher) {
            val eraser = ObjectEraser()
            val model = inpainter ?: if (store.installed()) {
                runCatching { store.openInpainter() }
                    .onFailure { Log.w(Tag, "Inpainting model unavailable; using the fallback", it) }
                    .getOrNull()
                    ?.also { inpainter = it }
            } else null
            if (model != null) {
                try {
                    val context = coroutineContext
                    return@withContext eraser.inpaintRegions(bitmap, regions, model) { context.ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(Tag, "Inpainting on ${model.accelerator} failed; using the fallback", failure)
                    model.close()
                    inpainter = null
                }
            }
            eraser.eraseRegions(bitmap, regions)
        }

    override fun close() {
        executor.execute {
            inpainter?.close()
            inpainter = null
        }
        dispatcher.close()
    }

    private companion object {
        const val Tag = "InpaintingSession"
    }
}
