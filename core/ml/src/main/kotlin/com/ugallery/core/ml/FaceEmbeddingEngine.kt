package com.ugallery.core.ml

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.mlkit.vision.face.FaceLandmark
import com.ugallery.core.database.DetectedFaceEntity
import com.ugallery.core.database.FaceEmbeddingEntity
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.Closeable
import java.io.FileNotFoundException
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun interface FaceEmbeddingInference {
    fun embed(alignedFace: Bitmap): FloatArray
}

class SFaceLiteRtEmbeddingInference(
    context: Context,
    accelerator: Accelerator = Accelerator.CPU,
) : FaceEmbeddingInference, Closeable {
    private val environment = Environment.create(context.applicationContext)
    private val model = CompiledModel.create(
        context.assets,
        ModelAsset,
        CompiledModel.Options(accelerator),
        environment,
    )
    private val input: TensorBuffer = model.createInputBuffers().single()
    private val output: TensorBuffer = model.createOutputBuffers().single()

    @Synchronized
    override fun embed(alignedFace: Bitmap): FloatArray {
        require(alignedFace.width == InputPixels && alignedFace.height == InputPixels)
        val pixels = IntArray(InputPixels * InputPixels)
        alignedFace.getPixels(pixels, 0, InputPixels, 0, 0, InputPixels, InputPixels)
        val bgr = FloatArray(InputPixels * InputPixels * 3)
        pixels.forEachIndexed { index, pixel ->
            val offset = index * 3
            bgr[offset] = (pixel and 0xff).toFloat()
            bgr[offset + 1] = (pixel ushr 8 and 0xff).toFloat()
            bgr[offset + 2] = (pixel ushr 16 and 0xff).toFloat()
        }
        input.writeFloat(bgr)
        model.run(listOf(input), listOf(output))
        return output.readFloat().also { require(it.size == Dimensions) }
    }

    override fun close() {
        output.close()
        input.close()
        model.close()
        environment.close()
    }

    companion object {
        const val ModelAsset = "models/sface_2021dec_float16.tflite"
        const val ModelVersion = "opencv-zoo-sface-2021dec-f16-0eed234c"
        const val InputPixels = 112
        const val Dimensions = 128
    }
}

object CompactFaceEmbedding {
    const val Dimensions = 128

    fun quantize(raw: FloatArray): ByteArray {
        require(raw.size == Dimensions)
        val norm = sqrt(raw.sumOf { value -> (value * value).toDouble() }).toFloat()
        require(norm.isFinite() && norm > 0f)
        return ByteArray(Dimensions) { index ->
            ((raw[index] / norm) * 127f).roundToInt().coerceIn(-127, 127).toByte()
        }
    }

    fun cosine(a: ByteArray, b: ByteArray): Float {
        require(a.size == Dimensions && b.size == Dimensions)
        var dot = 0L
        var aNorm = 0L
        var bNorm = 0L
        repeat(Dimensions) { index ->
            val av = a[index].toInt()
            val bv = b[index].toInt()
            dot += av * bv
            aNorm += av * av
            bNorm += bv * bv
        }
        if (aNorm == 0L || bNorm == 0L) return 0f
        return (dot / sqrt(aNorm.toDouble() * bNorm.toDouble())).toFloat().coerceIn(-1f, 1f)
    }
}

object SFaceAligner {
    fun align(source: Bitmap, face: DetectedFaceEntity): Bitmap {
        val landmarks = parseLandmarks(face.landmarksJson, source.width, source.height)
        val eyes = listOfNotNull(landmarks[FaceLandmark.LEFT_EYE], landmarks[FaceLandmark.RIGHT_EYE]).sortedBy(PointF::x)
        val mouths = listOfNotNull(landmarks[FaceLandmark.MOUTH_LEFT], landmarks[FaceLandmark.MOUTH_RIGHT]).sortedBy(PointF::x)
        if (eyes.size == 2 && mouths.size == 2) {
            val sourcePoints = floatArrayOf(
                eyes[0].x, eyes[0].y,
                eyes[1].x, eyes[1].y,
                (mouths[0].x + mouths[1].x) / 2f, (mouths[0].y + mouths[1].y) / 2f,
            )
            val targetPoints = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.1396f, 92.2848f)
            val transform = Matrix()
            if (transform.setPolyToPoly(sourcePoints, 0, targetPoints, 0, 3)) {
                return Bitmap.createBitmap(InputPixels, InputPixels, Bitmap.Config.ARGB_8888).also { output ->
                    Canvas(output).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(source, transform, null)
                    }
                }
            }
        }
        return FaceCropper.crop(source, face.cropRect(source.width, source.height), InputPixels)
    }

    private fun parseLandmarks(json: String, width: Int, height: Int): Map<Int, PointF> {
        val result = mutableMapOf<Int, PointF>()
        val values = runCatching { JSONArray(json) }.getOrElse { return emptyMap() }
        repeat(values.length()) { index ->
            val point = values.optJSONObject(index) ?: return@repeat
            result[point.optInt("t")] = PointF(
                point.optInt("x") * width / 1000f,
                point.optInt("y") * height / 1000f,
            )
        }
        return result
    }

    private const val InputPixels = SFaceLiteRtEmbeddingInference.InputPixels
}

class FaceEmbeddingMlEngine(
    private val resolver: ContentResolver,
    database: GalleryDatabase,
    private val permission: () -> Boolean,
    private val inference: FaceEmbeddingInference,
    private val embeddingModelVersion: String = SFaceLiteRtEmbeddingInference.ModelVersion,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MlTaskEngine, Closeable {
    private val dao = database.libraryDao()
    override val task = MlTaskType.FaceEmbeddings
    override val modelVersion = embeddingModelVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome = withContext(Dispatchers.IO) {
        val media = dao.pendingFaceEmbeddingMedia(modelVersion, limit)
        if (media.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
        var processed = 0
        for (item in media) {
            if (!permission()) return@withContext MlChunkOutcome.PermissionLost
            val bitmap = try {
                resolver.loadThumbnail(item.uri(), Size(DetectionThumbnail, DetectionThumbnail), CancellationSignal())
            } catch (_: SecurityException) {
                return@withContext MlChunkOutcome.PermissionLost
            } catch (_: FileNotFoundException) {
                dao.deleteDetectedFaces(item.volumeName, item.mediaStoreId)
                processed += 1
                continue
            }
            try {
                val current = dao.faceEmbeddings(item.volumeName, item.mediaStoreId).associateBy { it.faceOrdinal }
                val embeddings = dao.detectedFaces(item.volumeName, item.mediaStoreId).mapNotNull { face ->
                    val existing = current[face.faceOrdinal]
                    if (existing?.detectionModelVersion == face.modelVersion && existing.embeddingModelVersion == modelVersion) {
                        null
                    } else {
                        val aligned = SFaceAligner.align(bitmap, face)
                        val vector = try { CompactFaceEmbedding.quantize(inference.embed(aligned)) } finally { aligned.recycle() }
                        FaceEmbeddingEntity(
                            face.volumeName,
                            face.mediaStoreId,
                            face.faceOrdinal,
                            face.modelVersion,
                            modelVersion,
                            vector,
                            nowMillis(),
                        )
                    }
                }
                if (embeddings.isNotEmpty()) dao.upsertFaceEmbeddings(embeddings)
                processed += 1
            } finally {
                bitmap.recycle()
            }
        }
        if (media.size == limit) MlChunkOutcome.More(media.last().key(), processed)
        else MlChunkOutcome.Complete(processed)
    }

    override suspend fun purgeDerivedData() { dao.purgeFaceEmbeddings() }
    override fun close() { (inference as? Closeable)?.close() }

    private companion object { const val DetectionThumbnail = 1_024 }
}

private fun DetectedFaceEntity.cropRect(width: Int, height: Int) = Rect(
    cropLeftPermille * width / 1000,
    cropTopPermille * height / 1000,
    cropRightPermille * width / 1000,
    cropBottomPermille * height / 1000,
)

private fun MediaItemEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaItemEntity.uri() = ContentUris.withAppendedId(
    MediaStore.Images.Media.getContentUri(volumeName), mediaStoreId,
)
