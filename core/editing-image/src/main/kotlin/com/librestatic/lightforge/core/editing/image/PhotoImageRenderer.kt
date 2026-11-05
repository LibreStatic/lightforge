package com.librestatic.lightforge.core.editing.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.Trace
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.net.Uri
import com.librestatic.lightforge.core.editing.image.grade.CpuPhotoGrader
import com.librestatic.lightforge.core.editing.image.grade.PhotoGradeParams
import com.librestatic.lightforge.core.editing.image.grade.PhotoGrader
import com.librestatic.lightforge.core.editing.image.grade.PhotoLocalBase
import com.librestatic.lightforge.core.editing.image.grade.PhotoLut
import com.librestatic.lightforge.core.editing.image.grade.PhotoLutBaker
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class ImageBounds(val width: Int, val height: Int, val mimeType: String?)

/** A lossy fallback the export applied; the UI maps each one to a localized notice. */
enum class PhotoExportWarning {
    /** The source exceeded the bitmap budget, so the tiled path wrote a PNG. */
    FullResolutionPng,
    /** HEIC/AVIF HDR gain maps and container metadata were not carried into the re-encode. */
    HdrMetadataNotCopied,
}

sealed interface PhotoExportOutcome {
    data class Completed(
        val file: File,
        val width: Int,
        val height: Int,
        val wasDownscaled: Boolean,
        val warnings: List<PhotoExportWarning> = emptyList(),
        val mimeType: String? = null,
        /** Id of a look whose LUT could not be resolved; the export rendered without it. */
        val missingLookId: String? = null,
        /** Where the written output differs from the requested [PhotoOutputSettings]. */
        val adjustments: List<PhotoOutputAdjustment> = emptyList(),
    ) : PhotoExportOutcome

    /** [reason] is diagnostic English; the UI shows a localized message chosen by [kind]. */
    data class Failure(
        val reason: String,
        val recoverable: Boolean = true,
        val kind: PhotoExportFailureKind = PhotoExportFailureKind.Failed,
    ) : PhotoExportOutcome
}

enum class PhotoExportFailureKind { DecodeFailed, Failed }

/**
 * Bounded native image renderer. Preview never decodes a full 200 MP source; identity exports
 * stream original bytes while large transformed sources use the tiled PNG path instead of
 * allocating a full-resolution Android Bitmap.
 */
class PhotoImageRenderer(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxExportPixels: Long = 24_000_000L,
    /** Runs the ADR-045 colour grade on the geometry result. Pass `PhotoGraders.default(context)` for the GPU. */
    private val grader: PhotoGrader = CpuPhotoGrader(),
    /** Resolves a look id to its LUT; null means the look is missing and is skipped. */
    private val lookResolver: (String) -> PhotoLut? = PhotoLutBaker::bake,
) {
    init { require(maxExportPixels >= 1_000_000L) }

    suspend fun bounds(uri: Uri): ImageBounds = withContext(ioDispatcher) {
        decodeBounds(uri)
    }

    suspend fun renderPreview(uri: Uri, recipe: EditRecipe, maxDimension: Int): Bitmap =
        withContext(ioDispatcher) {
            require(maxDimension in 256..8_192)
            val sourceBounds = decodeBounds(uri)
            val sample = sampleForDimension(sourceBounds.width, sourceBounds.height, maxDimension)
            val decoded = decode(uri, sample)
                ?: throw IOException("Unable to decode image preview")
            applyRecipe(decoded, upright(uri, recipe).operations)
        }

    /**
     * Copies [source]'s capture EXIF (date, GPS, camera) into an upright [destination] of
     * [width] x [height] that was encoded outside this renderer. Returns false when not possible.
     */
    suspend fun copyCaptureMetadata(source: Uri, destination: File, width: Int, height: Int): Boolean =
        withContext(ioDispatcher) { PhotoExifMetadata.copy(resolver, source, destination, width, height) }

    /** Applies a recipe to an already decoded bitmap, consuming it when a transform replaces it. */
    suspend fun renderDecoded(source: Bitmap, recipe: EditRecipe): Bitmap = withContext(ioDispatcher) {
        applyRecipe(source, recipe.operations)
    }

    /**
     * Applies only the geometry operations (crop, rotate, flip, straighten, perspective) of
     * [recipe] to [source], in recipe order, consuming it when a transform replaces it. The colour
     * stage is left to [grade], so a live preview can cache the geometry result.
     */
    suspend fun renderGeometry(source: Bitmap, recipe: EditRecipe): Bitmap = withContext(ioDispatcher) {
        applyGeometry(source, recipe.operations)
    }

    /** The grade parameters [recipe] resolves to, including [PhotoGradeParams.missingLookId]. */
    fun gradeParams(operations: List<EditOperation>): PhotoGradeParams =
        PhotoGradeParams.from(operations, lookResolver)

    /**
     * Colour-grades the geometry-applied [frame] with [params] using this renderer's grader. The
     * local base is built from [frame] when needed, unless the caller passes a [base] from
     * [buildLocalBase]; the base is sampled by frame UV, so one built from a larger frame can grade
     * a downscale of it. Does not recycle [frame]; identity params return [frame] itself.
     */
    suspend fun grade(frame: Bitmap, params: PhotoGradeParams, base: PhotoLocalBase? = null): Bitmap =
        withContext(ioDispatcher) { gradeFrame(frame, params, recycleInput = false, suppliedBase = base) }

    /** The blurred luma base of [frame], for callers that grade the same frame (or its downscales) repeatedly. */
    suspend fun buildLocalBase(frame: Bitmap): PhotoLocalBase = withContext(ioDispatcher) { PhotoLocalBase.from(frame) }

    /**
     * The output [settings] resolve to for [recipe] on [uri], for showing the result before
     * saving. Reads only the image bounds and EXIF orientation.
     */
    suspend fun outputPlan(
        uri: Uri,
        recipe: EditRecipe,
        settings: PhotoOutputSettings,
        capabilities: PhotoOutputCapabilities = PhotoEncoders.capabilities,
    ): PhotoOutputPlan = withContext(ioDispatcher) {
        val sourceBounds = decodeBounds(uri)
        planFor(sourceBounds, upright(uri, recipe), settings, capabilities)
    }

    /** [outputPlan] for an upright source of known size that is not readable yet (a RAW before development). */
    fun outputPlan(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceMimeType: String?,
        recipe: EditRecipe,
        settings: PhotoOutputSettings,
        capabilities: PhotoOutputCapabilities = PhotoEncoders.capabilities,
    ): PhotoOutputPlan = planFor(ImageBounds(sourceWidth, sourceHeight, sourceMimeType), recipe, settings, capabilities)

    /**
     * Exports [recipe] applied to the upright source in the format, size and metadata of
     * [output]. [preserveMetadata] copies capture EXIF (date, GPS, camera) into re-encoded output
     * as far as [output] allows; share sanitizing leaves it off to strip EXIF.
     */
    suspend fun export(
        uri: Uri,
        recipe: EditRecipe,
        destination: File,
        onProgress: suspend (Long) -> Unit = {},
        preserveMetadata: Boolean = false,
        output: PhotoOutputSettings = PhotoOutputSettings(),
        capabilities: PhotoOutputCapabilities = PhotoEncoders.capabilities,
    ): PhotoExportOutcome = withContext(ioDispatcher) {
        // Async slice: the export suspends and may resume on another IO thread.
        val traceCookie = System.identityHashCode(destination)
        Trace.beginAsyncSection("PhotoExport", traceCookie)
        try {
            coroutineContext.ensureActive()
            val sourceBounds = decodeBounds(uri)
            if (recipe.isIdentity && output.isDefault) {
                copyIdentity(uri, destination, onProgress)
                return@withContext PhotoExportOutcome.Completed(
                    destination,
                    sourceBounds.width,
                    sourceBounds.height,
                    wasDownscaled = false,
                    mimeType = sourceBounds.mimeType,
                )
            }
            val uprightRecipe = upright(uri, recipe)
            val gradeParams = PhotoGradeParams.from(uprightRecipe.operations, lookResolver)
            val plan = planFor(sourceBounds, uprightRecipe, output, capabilities)
            val hdrWarning = if (sourceBounds.mimeType?.contains("heic", true) == true ||
                sourceBounds.mimeType?.contains("avif", true) == true
            ) listOf(PhotoExportWarning.HdrMetadataNotCopied) else emptyList()
            if (plan.tiled) {
                // The tiled plan maps output tiles back through the leading orientation ops, so
                // region-decoded tiles are rotated/flipped like the single-bitmap path.
                val tiled = TiledPngPhotoExporter(resolver).export(
                    uri = uri,
                    sourceWidth = sourceBounds.width,
                    sourceHeight = sourceBounds.height,
                    recipe = uprightRecipe,
                    destination = destination,
                    onProgress = onProgress,
                    grader = grader,
                    gradeParams = gradeParams,
                )
                if (preserveMetadata && plan.copyMetadata) {
                    copyMetadata(uri, tiled.file, tiled.width, tiled.height, plan.removeLocation)
                }
                return@withContext PhotoExportOutcome.Completed(
                    file = tiled.file,
                    width = tiled.width,
                    height = tiled.height,
                    wasDownscaled = false,
                    warnings = listOf(PhotoExportWarning.FullResolutionPng) + hdrWarning,
                    mimeType = PhotoEncoding.Png.mimeType,
                    missingLookId = gradeParams.missingLookId,
                    adjustments = plan.adjustments,
                )
            }
            val decoded = decode(uri, plan.sample)
                ?: return@withContext PhotoExportOutcome.Failure(
                    "Unable to decode source image",
                    kind = PhotoExportFailureKind.DecodeFailed,
                )
            val rendered = try {
                scaleTo(applyRecipe(decoded, uprightRecipe.operations, gradeParams), plan.width, plan.height)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!decoded.isRecycled) decoded.recycle()
                return@withContext PhotoExportOutcome.Failure(
                    failure.message ?: "Image render failed",
                )
            }
            coroutineContext.ensureActive()
            val encoded = try {
                encodeWithFallback(rendered, plan, destination)
            } finally {
                if (!rendered.isRecycled) rendered.recycle()
            }
            if (preserveMetadata && encoded.copyMetadata) {
                copyMetadata(uri, destination, encoded.width, encoded.height, encoded.removeLocation)
            }
            PhotoExportOutcome.Completed(
                file = destination,
                width = encoded.width,
                height = encoded.height,
                wasDownscaled = encoded.sample > 1 || encoded.width != plan.width,
                warnings = hdrWarning,
                mimeType = encoded.mimeType,
                missingLookId = gradeParams.missingLookId,
                adjustments = encoded.adjustments,
            )
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (failure: Throwable) {
            destination.delete()
            PhotoExportOutcome.Failure(failure.message ?: "Image export failed")
        } finally {
            Trace.endAsyncSection("PhotoExport", traceCookie)
        }
    }

    /**
     * Encodes an already rendered, upright [rendered] frame of [source] (for example the object
     * eraser result) with [output], resizing and copying capture metadata like [export]. Does not
     * recycle [rendered].
     */
    suspend fun exportRendered(
        source: Uri,
        sourceMimeType: String?,
        rendered: Bitmap,
        destination: File,
        output: PhotoOutputSettings,
        preserveMetadata: Boolean = true,
        capabilities: PhotoOutputCapabilities = PhotoEncoders.capabilities,
    ): PhotoExportOutcome = withContext(ioDispatcher) {
        try {
            val plan = PhotoOutputPlan.resolve(
                output, sourceMimeType, rendered.width, rendered.height, capabilities,
                // The frame is already in memory, so only the output size is bounded.
                maxBitmapPixels = Long.MAX_VALUE,
            )
            val scaled = scaleTo(rendered, plan.width, plan.height, recycleInput = false)
            val encoded = try {
                encodeWithFallback(scaled, plan, destination)
            } finally {
                if (scaled !== rendered && !scaled.isRecycled) scaled.recycle()
            }
            if (preserveMetadata && encoded.copyMetadata) {
                copyMetadata(source, destination, encoded.width, encoded.height, encoded.removeLocation)
            }
            PhotoExportOutcome.Completed(
                file = destination,
                width = encoded.width,
                height = encoded.height,
                wasDownscaled = encoded.width != rendered.width,
                mimeType = encoded.mimeType,
                adjustments = encoded.adjustments,
            )
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (failure: Throwable) {
            destination.delete()
            PhotoExportOutcome.Failure(failure.message ?: "Image export failed")
        }
    }

    private fun planFor(
        sourceBounds: ImageBounds,
        uprightRecipe: EditRecipe,
        settings: PhotoOutputSettings,
        capabilities: PhotoOutputCapabilities,
    ): PhotoOutputPlan {
        val edited = TiledTransformPlan(sourceBounds.width, sourceBounds.height, uprightRecipe.operations)
        return PhotoOutputPlan.resolve(
            settings = settings,
            sourceMimeType = sourceBounds.mimeType,
            editedWidth = edited.outputWidth,
            editedHeight = edited.outputHeight,
            capabilities = capabilities,
            maxBitmapPixels = maxExportPixels,
            sourceWidth = sourceBounds.width,
            sourceHeight = sourceBounds.height,
        )
    }

    /**
     * Encodes [bitmap] with [plan]; when the device's HEIC/AVIF encoder fails at run time the
     * frame is written as JPEG instead and the returned plan says so.
     */
    private suspend fun encodeWithFallback(bitmap: Bitmap, plan: PhotoOutputPlan, destination: File): PhotoOutputPlan {
        val job = coroutineContext[Job]
        val actual = plan.copy(width = bitmap.width, height = bitmap.height)
        return try {
            PhotoEncoders.encode(bitmap, actual, destination) { job?.ensureActive() }
            actual
        } catch (unavailable: PhotoEncoderUnavailableException) {
            // JPEG holds EXIF, so metadata the requested container could not carry is kept again.
            val wantedMetadata = actual.copyMetadata || PhotoOutputAdjustment.MetadataUnsupported in actual.adjustments
            val fallback = actual.copy(
                encoding = PhotoEncoding.Jpeg,
                copyMetadata = wantedMetadata,
                adjustments = (actual.adjustments - PhotoOutputAdjustment.MetadataUnsupported) +
                    PhotoOutputAdjustment.FormatUnavailable,
            )
            PhotoEncoders.encode(bitmap, fallback, destination)
            fallback
        }
    }

    private fun copyMetadata(source: Uri, destination: File, width: Int, height: Int, removeLocation: Boolean) {
        if (PhotoExifMetadata.copy(resolver, source, destination, width, height) && removeLocation) {
            PhotoExifMetadata.removeLocation(destination)
        }
    }

    /** Scales [bitmap] to exactly [width] x [height] with filtering; returns it unchanged when it already is. */
    private fun scaleTo(bitmap: Bitmap, width: Int, height: Int, recycleInput: Boolean = true): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (recycleInput && scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }

    /** Prefixes the EXIF orientation fix so every recipe operation sees the upright image. */
    private fun upright(uri: Uri, recipe: EditRecipe): EditRecipe {
        val orientation = orientationOperations(PhotoExifMetadata.readOrientation(resolver, uri))
        return if (orientation.isEmpty()) recipe else recipe.copy(operations = orientation + recipe.operations)
    }

    private fun decodeBounds(uri: Uri): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid image bounds")
        return ImageBounds(options.outWidth, options.outHeight, options.outMimeType)
    }

    private fun decode(uri: Uri, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun sampleForDimension(width: Int, height: Int, maxDimension: Int): Int =
        ceil(max(width, height).toDouble() / maxDimension.toDouble()).roundToInt().coerceAtLeast(1)

    private suspend fun copyIdentity(uri: Uri, destination: File, onProgress: suspend (Long) -> Unit) {
        destination.parentFile?.mkdirs()
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(256 * 1_024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        } ?: throw IOException("Unable to open source image")
        check(total > 0) { "Source image is empty" }
    }

    /** Geometry in recipe order, then the colour grade of the whole final frame. */
    private fun applyRecipe(
        source: Bitmap,
        operations: List<EditOperation>,
        params: PhotoGradeParams = PhotoGradeParams.from(operations, lookResolver),
    ): Bitmap = gradeFrame(applyGeometry(source, operations), params, recycleInput = true)

    private fun gradeFrame(
        frame: Bitmap,
        params: PhotoGradeParams,
        recycleInput: Boolean,
        suppliedBase: PhotoLocalBase? = null,
    ): Bitmap {
        if (params.isIdentity) return frame
        val base = if (params.needsLocalBase) suppliedBase ?: PhotoLocalBase.from(frame) else null
        val graded = grader.grade(frame, params, base, 0, 0, frame.width, frame.height)
        if (recycleInput && graded !== frame && !frame.isRecycled) frame.recycle()
        return graded
    }

    private fun applyGeometry(source: Bitmap, operations: List<EditOperation>): Bitmap {
        var current = source
        operations.forEach { operation ->
            val next = when (operation) {
                is EditOperation.Crop -> crop(current, operation)
                is EditOperation.Rotate -> transform(current, Matrix().apply { postRotate(operation.degrees.toFloat()) })
                is EditOperation.Flip -> transform(current, Matrix().apply { postScale(if (operation.horizontal) -1f else 1f, if (operation.horizontal) 1f else -1f) })
                is EditOperation.Straighten -> straighten(current, operation.degrees)
                is EditOperation.Perspective -> perspective(current, operation)
                // Colour operations are applied once, after all geometry, by the grader.
                is EditOperation.Tone, is EditOperation.Filter, is EditOperation.RawDevelop,
                is EditOperation.Adjust, is EditOperation.ColorMix, is EditOperation.Look -> current
            }
            if (next !== current && !current.isRecycled) current.recycle()
            current = next
        }
        return current
    }

    private fun crop(bitmap: Bitmap, operation: EditOperation.Crop): Bitmap {
        val left = (bitmap.width * operation.leftPermille / 1_000f).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * operation.topPermille / 1_000f).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * operation.rightPermille / 1_000f).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * operation.bottomPermille / 1_000f).roundToInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun transform(bitmap: Bitmap, matrix: Matrix): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

    /** Maps the quad of source corners onto the full frame, keeping the frame size. */
    private fun perspective(bitmap: Bitmap, operation: EditOperation.Perspective): Bitmap {
        if (operation.isIdentity) return bitmap
        val values = PerspectiveGeometry.matrixValues(operation.corners, bitmap.width, bitmap.height)
            ?: return bitmap
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            bitmap,
            Matrix().apply { setValues(values) },
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    private fun straighten(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val rotated = transform(bitmap, Matrix().apply { postRotate(degrees) })
        val (width, height) = StraightenGeometry.inscribedSize(bitmap.width, bitmap.height, degrees)
        val cropWidth = width.coerceIn(1, rotated.width)
        val cropHeight = height.coerceIn(1, rotated.height)
        val result = Bitmap.createBitmap(
            rotated,
            (rotated.width - cropWidth) / 2,
            (rotated.height - cropHeight) / 2,
            cropWidth,
            cropHeight,
        )
        if (result !== rotated && !rotated.isRecycled) rotated.recycle()
        return result
    }
}
