package com.ugallery.core.raw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.ugallery.core.model.RawDevelopmentSettings
import com.ugallery.core.model.RawMetadata
import com.ugallery.core.model.RawOutputFormat
import com.ugallery.core.model.RawSensorLayout
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
    data class Failure(val reason: String, val recoverable: Boolean = true) : RawExportOutcome
}

internal object LibRawBridge {
    init { System.loadLibrary("ugallery_raw") }
    external fun nativeInspect(source: String): String
    external fun nativeRender(source: String, settings: FloatArray, maxDimension: Int): RawNativeImage?
    external fun nativeExportTiff(source: String, destination: String, settings: FloatArray): String
}

class RawDeveloper(
    private val resolver: ContentResolver,
    private val scratchDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        destination.parentFile?.mkdirs()
        destination.delete()
        when (format) {
            RawOutputFormat.Tiff16Srgb -> {
                val required = estimateScratchBytes(metadata)
                if (scratchDirectory.usableSpace < required) {
                    return@withSource RawExportOutcome.Failure(
                        "RAW export needs ${required / (1_024 * 1_024)} MB of free temporary storage",
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
                } else RawExportOutcome.Completed(
                    destination, metadata.width, metadata.height, "image/tiff",
                )
            }
            RawOutputFormat.JpegSrgb -> {
                val pixels = metadata.width.toLong() * metadata.height.toLong()
                if (pixels > MaxJpegPixels) {
                    return@withSource RawExportOutcome.Failure(
                        "This RAW is too large for bounded JPEG export; choose 16-bit TIFF",
                    )
                }
                val native = LibRawBridge.nativeRender(
                    source.absolutePath, settings.nativeValues(), maxOf(metadata.width, metadata.height),
                ) ?: return@withSource RawExportOutcome.Failure("RAW JPEG render failed")
                val bitmap = Bitmap.createBitmap(native.argb, native.width, native.height, Bitmap.Config.ARGB_8888)
                try {
                    destination.outputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    }
                    RawExportOutcome.Completed(destination, native.width, native.height, "image/jpeg")
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

    private fun estimateScratchBytes(metadata: RawMetadata): Long =
        metadata.width.toLong() * metadata.height.toLong() * 10L + 128L * 1_024 * 1_024

    private companion object { const val MaxJpegPixels = 36_000_000L }
}

/** A staged RAW source that avoids copying the content URI for every interactive preview frame. */
class RawPreviewSession internal constructor(
    private val source: File,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    suspend fun inspect(): RawMetadata = withContext(ioDispatcher) {
        parseRawMetadata(LibRawBridge.nativeInspect(source.absolutePath))
    }

    suspend fun renderPreview(settings: RawDevelopmentSettings, maxDimension: Int): Bitmap =
        withContext(ioDispatcher) {
            require(maxDimension in 256..4_096)
            val native = LibRawBridge.nativeRender(source.absolutePath, settings.nativeValues(), maxDimension)
                ?: throw IOException("RAW preview could not be developed")
            Bitmap.createBitmap(native.argb, native.width, native.height, Bitmap.Config.ARGB_8888)
        }

    override fun close() {
        source.delete()
    }
}

private fun parseRawMetadata(encoded: String): RawMetadata {
    val values = encoded.split('|')
    if (values.firstOrNull() == "ERROR") throw IOException(values.drop(1).joinToString(" "))
    require(values.size >= 13) { "RAW metadata is incomplete" }
    val blackLevel = values[6].toInt().coerceAtLeast(0)
    val whiteLevel = values[5].toInt().coerceAtLeast(blackLevel + 1)
    val bitsPerSample = (32 - whiteLevel.countLeadingZeroBits()).coerceIn(1, 16)
    return RawMetadata(
        make = values[0], model = values[1], lens = values[2],
        width = values[3].toInt(), height = values[4].toInt(), bitsPerSample = bitsPerSample,
        whiteLevel = whiteLevel, blackLevel = blackLevel,
        iso = values[7].toFloatOrNull()?.toInt()?.takeIf { it > 0 },
        shutterSeconds = values[8].toDoubleOrNull()?.takeIf { it > 0.0 },
        aperture = values[9].toDoubleOrNull()?.takeIf { it > 0.0 },
        focalLengthMm = values[10].toDoubleOrNull()?.takeIf { it > 0.0 },
        sensorLayout = runCatching { RawSensorLayout.valueOf(values[11]) }.getOrDefault(RawSensorLayout.Unknown),
        colorDescription = values[12],
    )
}

fun isRawMimeOrName(mimeType: String?, displayName: String?): Boolean {
    val mime = mimeType?.lowercase().orEmpty()
    if (mime in RawMimes || mime.contains("raw")) return true
    val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return extension in RawExtensions
}

private val RawMimes = setOf(
    "image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef",
    "image/x-sony-arw", "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2",
    "image/x-pentax-pef", "image/x-samsung-srw", "image/x-canon-crw", "image/x-epson-erf",
    "image/x-hasselblad-3fr", "image/x-kodak-dcr", "image/x-kodak-kdc", "image/x-leaf-mos",
    "image/x-leica-rwl", "image/x-minolta-mrw", "image/x-phaseone-iiq", "image/x-sigma-x3f",
)
private val RawExtensions = setOf(
    "3fr", "ari", "arw", "bay", "bmq", "cap", "cine", "cr2", "cr3", "crw", "cs1", "dc2",
    "dcr", "dng", "drf", "eip", "erf", "fff", "iiq", "k25", "kdc", "mdc", "mef", "mos",
    "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx", "pxn", "r3d", "raf", "rdc", "rw2",
    "rwl", "rwz", "sr2", "srf", "srw", "x3f",
)

private fun RawDevelopmentSettings.nativeValues() = floatArrayOf(
    exposureEv, temperatureKelvin.toFloat(), tint, highlights, shadows, whites, blacks,
    contrast, saturation, vibrance, highlightRecovery, luminanceNoiseReduction,
    chromaNoiseReduction, sharpening,
    if (chromaticAberrationCorrection) 1f else 0f,
    if (lensCorrection) 1f else 0f,
)
