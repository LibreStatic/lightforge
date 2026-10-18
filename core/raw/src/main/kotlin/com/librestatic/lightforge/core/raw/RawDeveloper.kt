package com.librestatic.lightforge.core.raw

import com.librestatic.lightforge.core.model.RawFormats
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.librestatic.lightforge.core.model.RawCameraCrop
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawMetadata
import com.librestatic.lightforge.core.model.RawOutputFormat
import com.librestatic.lightforge.core.model.RawSensorLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Closeable
import kotlin.coroutines.coroutineContext

data class RawNativeImage(val argb: IntArray, val width: Int, val height: Int)

sealed interface RawExportOutcome {
    data class Completed(
        val file: File,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val warnings: List<String> = emptyList(),
    ) : RawExportOutcome
    /** [reason] is diagnostic English; the UI shows a localized message chosen by [kind]. */
    data class Failure(
        val reason: String,
        val recoverable: Boolean = true,
        val kind: RawExportFailureKind = RawExportFailureKind.RenderFailed,
        val requiredMegabytes: Long = 0,
    ) : RawExportOutcome
}

enum class RawExportFailureKind { InsufficientStorage, TooLargeForJpeg, RenderFailed }

internal object LibRawBridge {
    init { System.loadLibrary("lightforge_raw") }
    external fun nativeInspect(source: String): String
    external fun nativeRender(source: String, settings: FloatArray, maxDimension: Int): RawNativeImage?
    /** Returns the developed ARGB_8888 [Bitmap], or a String error ("OutOfMemory" if it cannot be allocated). */
    external fun nativeRenderToBitmap(source: String, settings: FloatArray, maxDimension: Int): Any
    external fun nativeOpenPreview(source: String, cameraFraming: Boolean): Long
    external fun nativeRenderPreview(
        handle: Long, settings: FloatArray, maxDimension: Int, approximate: Boolean,
    ): RawNativeImage?
    external fun nativeClosePreview(handle: Long)
    external fun nativeExportTiff(source: String, destination: String, settings: FloatArray): String
}

class RawDeveloper(
    private val resolver: ContentResolver,
    private val scratchDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val availableMemoryBytes: () -> Long = ::systemAvailableMemoryBytes,
) {
    suspend fun openPreviewSession(uri: Uri): RawPreviewSession = withContext(ioDispatcher) {
        scratchDirectory.mkdirs()
        val source = File(scratchDirectory, "raw-preview-${System.nanoTime()}.bin")
        try {
            resolver.openInputStream(uri)?.use { input ->
                source.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1_024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("RAW source cannot be opened")
            check(source.length() > 0) { "RAW source is empty" }
            RawPreviewSession(source, ioDispatcher)
        } catch (failure: Throwable) {
            source.delete()
            throw failure
        }
    }

    suspend fun inspect(uri: Uri): RawMetadata = withSource(uri) { source ->
        parseMetadata(LibRawBridge.nativeInspect(source.absolutePath))
    }

    suspend fun renderPreview(
        uri: Uri,
        settings: RawDevelopmentSettings,
        maxDimension: Int = 1_600,
    ): Bitmap = withSource(uri) { source ->
        require(maxDimension in 256..4_096)
        val native = LibRawBridge.nativeRender(source.absolutePath, settings.nativeValues(), maxDimension)
            ?: throw IOException("RAW preview could not be developed")
        Bitmap.createBitmap(native.argb, native.width, native.height, Bitmap.Config.ARGB_8888)
    }

    suspend fun export(
        uri: Uri,
        settings: RawDevelopmentSettings,
        format: RawOutputFormat,
        destination: File,
    ): RawExportOutcome = withSource(uri) { source ->
        val metadata = parseMetadata(LibRawBridge.nativeInspect(source.absolutePath))
        // Sizes of the frame that will actually be developed (framed or full sensor).
        val frame = metadata.dimensions(settings.cameraFraming)
        destination.parentFile?.mkdirs()
        destination.delete()
        when (format) {
            RawOutputFormat.Tiff16Srgb -> {
                val required = estimateScratchBytes(frame.width, frame.height)
                if (scratchDirectory.usableSpace < required) {
                    return@withSource RawExportOutcome.Failure(
                        "RAW export needs ${required / (1_024 * 1_024)} MB of free temporary storage",
                        kind = RawExportFailureKind.InsufficientStorage,
                        requiredMegabytes = required / (1_024 * 1_024),
                    )
                }
                val error = LibRawBridge.nativeExportTiff(
                    source.absolutePath,
                    destination.absolutePath,
                    settings.nativeValues(),
                )
                if (error.isNotBlank() || !destination.isFile || destination.length() == 0L) {
                    destination.delete()
                    RawExportOutcome.Failure(error.ifBlank { "RAW TIFF export failed" })
                } else {
                    // LibRaw writes the TIFF pixels already rotated for the orientation flip.
                    val transposed = metadata.flip and 4 != 0
                    RawExportOutcome.Completed(
                        destination,
                        if (transposed) frame.height else frame.width,
                        if (transposed) frame.width else frame.height,
                        "image/tiff",
                    )
                }
            }
            RawOutputFormat.JpegSrgb -> {
                // LibRaw's mem image is already rotated for the orientation flip.
                val transposed = metadata.flip and 4 != 0
                val width = if (transposed) frame.height else frame.width
                val height = if (transposed) frame.width else frame.height
                val required = estimateJpegBytes(width, height)
                if (required + JpegMemoryMargin > availableMemoryBytes()) {
                    return@withSource RawExportOutcome.Failure(
                        "Not enough memory to develop this RAW as a JPEG; choose 16-bit TIFF",
                        kind = RawExportFailureKind.TooLargeForJpeg,
                        requiredMegabytes = required / (1_024 * 1_024),
                    )
                }
                val bitmap = when (
                    val result = LibRawBridge.nativeRenderToBitmap(
                        source.absolutePath, settings.nativeValues(), maxOf(frame.width, frame.height),
                    )
                ) {
                    is Bitmap -> result
                    "OutOfMemory" -> return@withSource RawExportOutcome.Failure(
                        "Not enough memory to develop this RAW as a JPEG; choose 16-bit TIFF",
                        kind = RawExportFailureKind.TooLargeForJpeg,
                        requiredMegabytes = required / (1_024 * 1_024),
                    )
                    else -> return@withSource RawExportOutcome.Failure("RAW JPEG render failed: $result")
                }
                try {
                    destination.outputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    }
                    RawExportOutcome.Completed(destination, bitmap.width, bitmap.height, "image/jpeg")
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private suspend fun <T> withSource(uri: Uri, block: suspend (File) -> T): T =
        withContext(ioDispatcher) {
            scratchDirectory.mkdirs()
            val source = File(scratchDirectory, "raw-source-${System.nanoTime()}.bin")
            try {
                resolver.openInputStream(uri)?.use { input ->
                    source.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1_024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("RAW source cannot be opened")
                check(source.length() > 0) { "RAW source is empty" }
                block(source)
            } finally {
                source.delete()
            }
        }

    private fun parseMetadata(encoded: String): RawMetadata = parseRawMetadata(encoded)

    private fun estimateScratchBytes(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * 10L + 128L * 1_024 * 1_024

    /** Bitmap (4) + LibRaw mem image (3) + LibRaw working buffers (8) bytes per pixel. */
    private fun estimateJpegBytes(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * 15L

    private companion object { const val JpegMemoryMargin = 256L * 1_024 * 1_024 }
}

/** MemAvailable from /proc/meminfo; unlimited when it cannot be read, leaving the OOM catch as the guard. */
private fun systemAvailableMemoryBytes(): Long = try {
    File("/proc/meminfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("MemAvailable:") }
            ?.filter(Char::isDigit)?.toLongOrNull()?.times(1_024L)
    } ?: Long.MAX_VALUE
} catch (_: IOException) {
    Long.MAX_VALUE
}

/** A staged RAW source that avoids copying the content URI for every interactive preview frame. */
class RawPreviewSession internal constructor(
    private val source: File,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    suspend fun inspect(): RawMetadata = withContext(ioDispatcher) {
        parseRawMetadata(LibRawBridge.nativeInspect(source.absolutePath))
    }

    private val lock = Any()
    private var handle = 0L
    private var closed = false
    private var rendering = false

    /**
     * [approximate] lets exposure and white balance be corrected on the cached development
     * instead of re-developing, for live feedback while a slider is dragged.
     */
    suspend fun renderPreview(
        settings: RawDevelopmentSettings,
        maxDimension: Int,
        approximate: Boolean = false,
    ): Bitmap = withContext(ioDispatcher) {
        require(maxDimension in 256..4_096)
        val native = synchronized(lock) {
            if (closed) throw IOException("RAW preview session is closed")
            if (handle == 0L) handle = LibRawBridge.nativeOpenPreview(source.absolutePath, settings.cameraFraming)
            if (handle == 0L) throw IOException("RAW preview could not be developed")
            rendering = true
            handle
        }.let { current ->
            try {
                LibRawBridge.nativeRenderPreview(current, settings.nativeValues(), maxDimension, approximate)
            } finally {
                synchronized(lock) {
                    rendering = false
                    if (closed) releaseHandle()
                }
            }
        } ?: throw IOException("RAW preview could not be developed")
        Bitmap.createBitmap(native.argb, native.width, native.height, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        // Called from the main thread: a render in flight releases the handle when it finishes.
        synchronized(lock) {
            closed = true
            if (!rendering) releaseHandle()
        }
    }

    private fun releaseHandle() {
        if (handle != 0L) LibRawBridge.nativeClosePreview(handle)
        handle = 0L
        source.delete()
    }
}

internal fun parseRawMetadata(encoded: String): RawMetadata {
    val values = encoded.split('|')
    if (values.firstOrNull() == "ERROR") throw IOException(values.drop(1).joinToString(" "))
    require(values.size >= 13) { "RAW metadata is incomplete" }
    val blackLevel = values[6].toInt().coerceAtLeast(0)
    val whiteLevel = values[5].toInt().coerceAtLeast(blackLevel + 1)
    // Older builds emitted 13 fields; the framing fields follow as full size, crop rect and flip.
    val hasFraming = values.size >= 20
    val width = values[3].toInt()
    val height = values[4].toInt()
    val fullWidth = if (hasFraming) values[13].toInt() else width
    val fullHeight = if (hasFraming) values[14].toInt() else height
    val cameraCrop = if (hasFraming) {
        val crop = RawCameraCrop(
            left = values[15].toInt(), top = values[16].toInt(),
            width = values[17].toInt(), height = values[18].toInt(),
        )
        crop.takeIf {
            it.width > 0 && it.height > 0 && (it.width < fullWidth || it.height < fullHeight)
        }
    } else null
    val bitsPerSample = (32 - whiteLevel.countLeadingZeroBits()).coerceIn(1, 16)
    return RawMetadata(
        make = values[0], model = values[1], lens = values[2],
        width = width, height = height, bitsPerSample = bitsPerSample,
        whiteLevel = whiteLevel, blackLevel = blackLevel,
        iso = values[7].toFloatOrNull()?.toInt()?.takeIf { it > 0 },
        shutterSeconds = values[8].toDoubleOrNull()?.takeIf { it > 0.0 },
        aperture = values[9].toDoubleOrNull()?.takeIf { it > 0.0 },
        focalLengthMm = values[10].toDoubleOrNull()?.takeIf { it > 0.0 },
        sensorLayout = runCatching { RawSensorLayout.valueOf(values[11]) }.getOrDefault(RawSensorLayout.Unknown),
        colorDescription = values[12],
        fullWidth = fullWidth, fullHeight = fullHeight, cameraCrop = cameraCrop,
        flip = if (hasFraming) values[19].toInt() else 0,
    )
}

fun isRawMimeOrName(mimeType: String?, displayName: String?): Boolean =
    RawFormats.isRaw(mimeType, displayName)


internal fun RawDevelopmentSettings.nativeValues() = floatArrayOf(
    exposureEv, temperatureKelvin.toFloat(), tint, highlights, shadows, whites, blacks,
    contrast, saturation, vibrance, highlightRecovery, luminanceNoiseReduction,
    chromaNoiseReduction, sharpening,
    if (chromaticAberrationCorrection) 1f else 0f,
    if (lensCorrection) 1f else 0f,
    if (cameraFraming) 1f else 0f,
)
