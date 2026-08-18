package com.ugallery.core.editing.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.EditRecipe
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-resolution still exporter for sources that cannot fit in one Android Bitmap.
 *
 * The source is region-decoded in bounded tiles and emitted as a streaming PNG. This avoids
 * allocating either a 200 MP source or a 200 MP destination bitmap. The output is deliberately
 * PNG so the encoder can consume rows incrementally; callers receive the MIME fallback in the
 * result warning and must publish it with an image/png name.
 */
internal class TiledPngPhotoExporter(
    private val resolver: ContentResolver,
    private val tileSize: Int = 512,
) {
    init { require(tileSize in 128..2_048) }

    suspend fun export(
        uri: Uri,
        sourceWidth: Int,
        sourceHeight: Int,
        recipe: EditRecipe,
        destination: File,
        onProgress: suspend (Long) -> Unit,
    ): TiledPngExportResult {
        val plan = TiledTransformPlan(sourceWidth, sourceHeight, recipe.operations)
        destination.parentFile?.mkdirs()
        val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open source image")
        val decoder = BitmapRegionDecoder.newInstance(input, false)
            ?: throw IOException("Unable to create region decoder")
        try {
            PngStreamEncoder(destination, plan.outputWidth, plan.outputHeight).use { png ->
                var tileRowTop = 0
                while (tileRowTop < plan.outputHeight) {
                    coroutineContext.ensureActive()
                    val tileHeight = min(tileSize, plan.outputHeight - tileRowTop)
                    val renderedTiles = ArrayList<Bitmap>()
                    try {
                        var tileLeft = 0
                        while (tileLeft < plan.outputWidth) {
                            coroutineContext.ensureActive()
                            val tileWidth = min(tileSize, plan.outputWidth - tileLeft)
                            val outputRect = Rect(tileLeft, tileRowTop, tileLeft + tileWidth, tileRowTop + tileHeight)
                            val sourceRect = plan.sourceRectFor(outputRect)
                            val decoded = decoder.decodeRegion(sourceRect, BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inScaled = false
                            }) ?: throw IOException("Unable to decode source tile $sourceRect")
                            renderedTiles += plan.renderTile(decoded, sourceRect, outputRect)
                            if (!decoded.isRecycled) decoded.recycle()
                            tileLeft += tileWidth
                        }

                        var row = 0
                        while (row < tileHeight) {
                            coroutineContext.ensureActive()
                            png.writeRow(renderedTiles, row)
                            row++
                        }
                    } finally {
                        renderedTiles.forEach { if (!it.isRecycled) it.recycle() }
                    }
                    tileRowTop += tileHeight
                    onProgress(tileRowTop.toLong() * plan.outputWidth.toLong())
                }
            }
        } finally {
            decoder.recycle()
            input.close()
        }
        check(destination.isFile && destination.length() > 0) { "Tiled PNG export is empty" }
        return TiledPngExportResult(plan.outputWidth, plan.outputHeight, destination)
    }
}

internal data class TiledPngExportResult(
    val width: Int,
    val height: Int,
    val file: File,
)

private class TiledTransformPlan(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    operations: List<EditOperation>,
) {
    var outputWidth: Int = sourceWidth
        private set
    var outputHeight: Int = sourceHeight
        private set

    private val sourceToOutput = Matrix()
    private val colorOperations = operations.filter { it is EditOperation.Tone || it is EditOperation.Filter }

    init {
        operations.forEach { operation ->
            when (operation) {
                is EditOperation.Crop -> {
                    val left = (outputWidth * operation.leftPermille / 1_000f).roundToInt()
                        .coerceIn(0, outputWidth - 1)
                    val top = (outputHeight * operation.topPermille / 1_000f).roundToInt()
                        .coerceIn(0, outputHeight - 1)
                    val right = (outputWidth * operation.rightPermille / 1_000f).roundToInt()
                        .coerceIn(left + 1, outputWidth)
                    val bottom = (outputHeight * operation.bottomPermille / 1_000f).roundToInt()
                        .coerceIn(top + 1, outputHeight)
                    concat(Matrix().apply { setTranslate(-left.toFloat(), -top.toFloat()) })
                    outputWidth = right - left
                    outputHeight = bottom - top
                }

                is EditOperation.Rotate -> {
                    val normalized = ((operation.degrees % 360) + 360) % 360
                    val width = outputWidth
                    val height = outputHeight
                    val rotation = Matrix().apply {
                        setValues(
                            when (normalized) {
                                90 -> floatArrayOf(0f, -1f, height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
                                180 -> floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
                                270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width.toFloat(), 0f, 0f, 1f)
                                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                            },
                        )
                    }
                    concat(rotation)
                    if (normalized == 90 || normalized == 270) {
                        outputWidth = height
                        outputHeight = width
                    }
                }

                is EditOperation.Flip -> {
                    val flip = Matrix().apply {
                        setValues(
                            if (operation.horizontal) {
                                floatArrayOf(-1f, 0f, outputWidth.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f)
                            } else {
                                floatArrayOf(1f, 0f, 0f, 0f, -1f, outputHeight.toFloat(), 0f, 0f, 1f)
                            },
                        )
                    }
                    concat(flip)
                }

                is EditOperation.Tone, is EditOperation.Filter -> Unit
            }
        }
    }

    private fun concat(operation: Matrix) {
        val combined = Matrix()
        combined.setConcat(operation, sourceToOutput)
        sourceToOutput.set(combined)
    }

    fun sourceRectFor(outputRect: Rect): Rect {
        val inverse = Matrix()
        check(sourceToOutput.invert(inverse)) { "Edit transform is not invertible" }
        val points = floatArrayOf(
            outputRect.left.toFloat(), outputRect.top.toFloat(),
            outputRect.right.toFloat(), outputRect.top.toFloat(),
            outputRect.right.toFloat(), outputRect.bottom.toFloat(),
            outputRect.left.toFloat(), outputRect.bottom.toFloat(),
        )
        inverse.mapPoints(points)
        var left = floor(points.filterIndexed { index, _ -> index % 2 == 0 }.min()).toInt()
        var top = floor(points.filterIndexed { index, _ -> index % 2 == 1 }.min()).toInt()
        var right = ceil(points.filterIndexed { index, _ -> index % 2 == 0 }.max()).toInt()
        var bottom = ceil(points.filterIndexed { index, _ -> index % 2 == 1 }.max()).toInt()
        left = left.coerceIn(0, sourceWidth)
        top = top.coerceIn(0, sourceHeight)
        right = right.coerceIn(left + 1, sourceWidth)
        bottom = bottom.coerceIn(top + 1, sourceHeight)
        return Rect(left, top, right, bottom)
    }

    fun renderTile(source: Bitmap, sourceRect: Rect, outputRect: Rect): Bitmap {
        val tile = Bitmap.createBitmap(outputRect.width(), outputRect.height(), Bitmap.Config.ARGB_8888)
        val localToOutput = Matrix()
        val sourceLocalToGlobal = Matrix().apply { setTranslate(sourceRect.left.toFloat(), sourceRect.top.toFloat()) }
        localToOutput.setConcat(sourceToOutput, sourceLocalToGlobal)
        localToOutput.postTranslate(-outputRect.left.toFloat(), -outputRect.top.toFloat())
        Canvas(tile).drawBitmap(source, localToOutput, Paint(Paint.ANTI_ALIAS_FLAG))
        return colorize(tile)
    }

    private fun colorize(source: Bitmap): Bitmap {
        var current = source
        colorOperations.forEach { operation ->
            val matrix = when (operation) {
                is EditOperation.Tone -> toneMatrix(operation)
                is EditOperation.Filter -> filterMatrix(operation.name)
                else -> ColorMatrix()
            }
            val next = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(current, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
            if (!current.isRecycled) current.recycle()
            current = next
        }
        return current
    }

    private fun toneMatrix(operation: EditOperation.Tone): ColorMatrix {
        val contrast = operation.contrast
        val translate = (1f - contrast) * 127.5f + operation.brightness * 255f
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
        if (operation.saturation != 1f) {
            matrix.postConcat(ColorMatrix().apply { setSaturation(operation.saturation) })
        }
        return matrix
    }

    private fun filterMatrix(name: String): ColorMatrix = when (name) {
        "natural" -> toneMatrix(EditOperation.Tone(contrast = 1.08f, saturation = 0.9f))
        "vivid" -> toneMatrix(EditOperation.Tone(brightness = 0.03f, saturation = 1.2f))
        "mono" -> ColorMatrix().apply { setSaturation(0f) }
        else -> ColorMatrix()
    }
}

private class PngStreamEncoder(
    file: File,
    private val width: Int,
    private val height: Int,
) : Closeable {
    private val output = file.outputStream()
    private val deflater = Deflater(6)
    private val compressed = ByteArray(64 * 1_024)
    private var closed = false

    init {
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArray(13)
        writeInt(header, 0, width)
        writeInt(header, 4, height)
        header[8] = 8
        header[9] = 6
        writeChunk("IHDR", header)
    }

    fun writeRow(tiles: List<Bitmap>, row: Int) {
        val data = ByteArray(1 + width * 4)
        var offset = 1
        tiles.forEach { tile ->
            val pixels = IntArray(tile.width)
            tile.getPixels(pixels, 0, tile.width, 0, row, tile.width, 1)
            pixels.forEach { pixel ->
                data[offset++] = Color.red(pixel).toByte()
                data[offset++] = Color.green(pixel).toByte()
                data[offset++] = Color.blue(pixel).toByte()
                data[offset++] = Color.alpha(pixel).toByte()
            }
        }
        deflater.setInput(data)
        drainDeflater()
    }

    private fun drainDeflater() {
        while (!deflater.needsInput()) {
            val count = deflater.deflate(compressed)
            if (count == 0) break
            writeChunk("IDAT", compressed.copyOf(count))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        deflater.finish()
        while (!deflater.finished()) {
            val count = deflater.deflate(compressed)
            if (count > 0) writeChunk("IDAT", compressed.copyOf(count))
        }
        writeChunk("IEND", ByteArray(0))
        deflater.end()
        output.close()
    }

    private fun writeChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val length = ByteArray(4)
        writeInt(length, 0, data.size)
        output.write(length)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        writeInt(crcBytes, 0, crc.value.toInt())
        output.write(crcBytes)
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}
