package com.ugallery.core.editing.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.net.Uri
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.EditRecipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

data class ImageBounds(val width: Int, val height: Int, val mimeType: String?)

sealed interface PhotoExportOutcome {
    data class Completed(
        val file: File,
        val width: Int,
        val height: Int,
        val wasDownscaled: Boolean,
        val warnings: List<String> = emptyList(),
        val mimeType: String? = null,
    ) : PhotoExportOutcome

    data class Failure(val reason: String, val recoverable: Boolean = true) : PhotoExportOutcome
}

/**
 * Bounded native image renderer. Preview never decodes a full 200 MP source; identity exports
 * stream original bytes while large transformed sources use the tiled PNG path instead of
 * allocating a full-resolution Android Bitmap.
 */
class PhotoImageRenderer(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxExportPixels: Long = 24_000_000L,
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
            applyRecipe(decoded, recipe)
        }

    /** Applies a recipe to an already decoded bitmap, consuming it when a transform replaces it. */
    suspend fun renderDecoded(source: Bitmap, recipe: EditRecipe): Bitmap = withContext(ioDispatcher) {
        applyRecipe(source, recipe)
    }

    suspend fun export(
        uri: Uri,
        recipe: EditRecipe,
        destination: File,
        onProgress: suspend (Long) -> Unit = {},
    ): PhotoExportOutcome = withContext(ioDispatcher) {
        try {
            coroutineContext.ensureActive()
            val sourceBounds = decodeBounds(uri)
            if (recipe.isIdentity) {
                copyIdentity(uri, destination, onProgress)
                return@withContext PhotoExportOutcome.Completed(
                    destination,
                    sourceBounds.width,
                    sourceBounds.height,
                    wasDownscaled = false,
                    mimeType = sourceBounds.mimeType,
                )
            }
            val sourcePixels = sourceBounds.width.toLong() * sourceBounds.height.toLong()
            if (sourcePixels > maxExportPixels) {
                val tiled = TiledPngPhotoExporter(resolver).export(
                    uri = uri,
                    sourceWidth = sourceBounds.width,
                    sourceHeight = sourceBounds.height,
                    recipe = recipe,
                    destination = destination,
                    onProgress = onProgress,
                )
                return@withContext PhotoExportOutcome.Completed(
                    file = tiled.file,
                    width = tiled.width,
                    height = tiled.height,
                    wasDownscaled = false,
                    warnings = listOf(
                        "Full-resolution tiled export used PNG to stay within the device bitmap budget",
                    ) + if (sourceBounds.mimeType?.contains("heic", true) == true ||
                        sourceBounds.mimeType?.contains("avif", true) == true
                    ) {
                        listOf("HDR/container metadata was not copied into the 8-bit PNG output")
                    } else {
                        emptyList()
                    },
                    mimeType = "image/png",
                )
            }
            // The > maxExportPixels branch above is the tiled path; regular exports stay at
            // source resolution and therefore never silently downsample transformed content.
            val sample = 1
            val decoded = decode(uri, sample)
                ?: return@withContext PhotoExportOutcome.Failure("Unable to decode source image")
            val rendered = try {
                applyRecipe(decoded, recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!decoded.isRecycled) decoded.recycle()
                return@withContext PhotoExportOutcome.Failure(
                    failure.message ?: "Image render failed",
                )
            }
            coroutineContext.ensureActive()
            destination.parentFile?.mkdirs()
            val renderedWidth = rendered.width
            val renderedHeight = rendered.height
            if (!destination.outputStream().use { output ->
                    rendered.compress(compressFormat(sourceBounds.mimeType), 95, output)
                }
            ) {
                if (!rendered.isRecycled) rendered.recycle()
                return@withContext PhotoExportOutcome.Failure("Image encoder rejected output")
            }
            if (!rendered.isRecycled) rendered.recycle()
            check(destination.isFile && destination.length() > 0) { "Image export is empty" }
            PhotoExportOutcome.Completed(
                file = destination,
                width = renderedWidth,
                height = renderedHeight,
                wasDownscaled = false,
                warnings = buildList {
                    if (sourceBounds.mimeType?.contains("heic", true) == true ||
                        sourceBounds.mimeType?.contains("avif", true) == true
                    ) add("Output was encoded as JPEG; HDR/container metadata was not copied")
                },
                mimeType = compressMime(sourceBounds.mimeType),
            )
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (failure: Throwable) {
            destination.delete()
            PhotoExportOutcome.Failure(failure.message ?: "Image export failed")
        }
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

    private fun applyRecipe(source: Bitmap, recipe: EditRecipe): Bitmap {
        var current = source
        recipe.operations.forEach { operation ->
            val next = when (operation) {
                is EditOperation.Crop -> crop(current, operation)
                is EditOperation.Rotate -> transform(current, Matrix().apply { postRotate(operation.degrees.toFloat()) })
                is EditOperation.Flip -> transform(current, Matrix().apply { postScale(if (operation.horizontal) -1f else 1f, if (operation.horizontal) 1f else -1f) })
                is EditOperation.Straighten -> straighten(current, operation.degrees)
                is EditOperation.Tone, is EditOperation.Filter ->
                    color(current, PhotoColorTransform.matrixFor(operation))
                is EditOperation.RawDevelop -> current
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

    private fun straighten(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val rotated = transform(bitmap, Matrix().apply { postRotate(degrees) })
        val radians = Math.toRadians(abs(degrees).toDouble())
        val sine = sin(radians)
        val cosine = cos(radians)
        val denominator = cosine * cosine - sine * sine
        val width: Double
        val height: Double
        if (minOf(bitmap.width, bitmap.height) <= 2 * sine * cosine * maxOf(bitmap.width, bitmap.height) ||
            abs(denominator) < 0.000_001
        ) {
            val halfShort = 0.5 * minOf(bitmap.width, bitmap.height)
            if (bitmap.width >= bitmap.height) {
                width = halfShort / sine.coerceAtLeast(0.000_001)
                height = halfShort / cosine.coerceAtLeast(0.000_001)
            } else {
                width = halfShort / cosine.coerceAtLeast(0.000_001)
                height = halfShort / sine.coerceAtLeast(0.000_001)
            }
        } else {
            width = (bitmap.width * cosine - bitmap.height * sine) / denominator
            height = (bitmap.height * cosine - bitmap.width * sine) / denominator
        }
        val cropWidth = width.roundToInt().coerceIn(1, rotated.width)
        val cropHeight = height.roundToInt().coerceIn(1, rotated.height)
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

    private fun color(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return output
    }

    private fun compressFormat(mimeType: String?): Bitmap.CompressFormat = when (mimeType?.lowercase()) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp", "image/webp-lossless" -> Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> Bitmap.CompressFormat.JPEG
    }

    private fun compressMime(mimeType: String?): String = when (mimeType?.lowercase()) {
        "image/png" -> "image/png"
        "image/webp", "image/webp-lossless" -> "image/webp"
        else -> "image/jpeg"
    }
}
