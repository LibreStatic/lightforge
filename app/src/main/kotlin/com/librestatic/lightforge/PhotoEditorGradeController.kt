package com.librestatic.lightforge

import android.graphics.Bitmap
import com.librestatic.lightforge.core.editing.image.PhotoImageRenderer
import com.librestatic.lightforge.core.editing.image.grade.PhotoLocalBase
import com.librestatic.lightforge.core.model.EditOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** A graded frame for the editor stage. [graded] is null when the grade is the identity. */
internal class PhotoGradePublication(
    /** The frame that was graded; the receiver drops the result when it is no longer the preview. */
    val frame: Bitmap,
    val graded: Bitmap?,
    val missingLookId: String?,
    val final: Boolean,
)

/**
 * Live colour-grade worker for the photo editor. [request] queues the newest operations on a
 * conflated channel, so while a slider moves only the latest value is graded, one at a time and in
 * order. A dragging request grades a cached [DragMaxDimension] px downscale of the frame; a final
 * request grades the frame itself. [publish] runs on [scope]'s dispatcher (Main in the view model).
 */
internal class PhotoEditorGradeController(
    private val scope: CoroutineScope,
    private val renderer: PhotoImageRenderer,
    private val publish: (PhotoGradePublication) -> Unit,
) {
    private class Request(val frame: Bitmap, val operations: List<EditOperation>, val final: Boolean)

    private var channel: Channel<Request>? = null
    private var job: Job? = null

    /** Owned by one worker coroutine; the downscale is never shown, so it is recycled directly. */
    private class LowFrameCache {
        var frame: Bitmap? = null
        var source: Bitmap? = null

        /** The luma base of [baseSource] (the full frame), shared by the drag downscale and the full grade. */
        var base: PhotoLocalBase? = null
        var baseSource: Bitmap? = null
        var baseGenerationId = 0

        fun release() {
            base = null
            baseSource = null
            frame?.takeIf { !it.isRecycled }?.recycle()
            frame = null
            source = null
        }
    }

    fun start() {
        close()
        val requests = Channel<Request>(Channel.CONFLATED)
        channel = requests
        job = scope.launch {
            val cache = LowFrameCache()
            try {
                for (request in requests) process(request, cache)
            } finally {
                cache.release()
            }
        }
    }

    fun request(frame: Bitmap, operations: List<EditOperation>, final: Boolean) {
        channel?.trySend(Request(frame, operations, final))
    }

    fun close() {
        channel?.close()
        channel = null
        job?.cancel()
        job = null
    }

    private suspend fun process(request: Request, cache: LowFrameCache) {
        val graded = try {
            val params = withContext(Dispatchers.Default) { renderer.gradeParams(request.operations) }
            val output = if (params.isIdentity) null else {
                val input = if (request.final) request.frame else lowFrameFor(request.frame, cache)
                // Built once per full frame, so dragging and releasing look the same and cost no rebuilds.
                val base = if (params.needsLocalBase) localBaseFor(request.frame, cache) else null
                renderer.grade(input, params, base).takeUnless { it === input }
            }
            PhotoGradePublication(request.frame, output, params.missingLookId, request.final)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A frame recycled mid-grade, or a grader failure: keep what is on screen.
            return
        }
        publish(graded)
    }

    private suspend fun localBaseFor(frame: Bitmap, cache: LowFrameCache): PhotoLocalBase {
        val cached = cache.base
        if (cached != null && cache.baseSource === frame && cache.baseGenerationId == frame.generationId) return cached
        return renderer.buildLocalBase(frame).also {
            cache.base = it
            cache.baseSource = frame
            cache.baseGenerationId = frame.generationId
        }
    }

    private fun lowFrameFor(frame: Bitmap, cache: LowFrameCache): Bitmap {
        val longSide = max(frame.width, frame.height)
        if (longSide <= DragMaxDimension) return frame
        cache.frame?.takeIf { cache.source === frame && !it.isRecycled }?.let { return it }
        cache.release()
        val scale = DragMaxDimension.toFloat() / longSide
        val low = Bitmap.createScaledBitmap(
            frame,
            (frame.width * scale).roundToInt().coerceAtLeast(1),
            (frame.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        cache.frame = low
        cache.source = frame
        return low
    }

    companion object {
        const val DragMaxDimension = 1_024
    }
}
