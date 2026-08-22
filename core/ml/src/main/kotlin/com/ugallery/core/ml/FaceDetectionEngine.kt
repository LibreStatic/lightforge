package com.ugallery.core.ml

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Rect
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.ugallery.core.database.DetectedFaceEntity
import com.ugallery.core.database.FaceDetectionRunEntity
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RawFaceLandmark(val type: Int, val x: Float, val y: Float)

data class RawDetectedFace(
    val boundingBox: Rect,
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val landmarks: List<RawFaceLandmark>,
)

fun interface FaceDetectionInference {
    suspend fun infer(bitmap: Bitmap): List<RawDetectedFace>
}

class BundledMlKitFaceDetectionInference : FaceDetectionInference, Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build(),
    )

    override suspend fun infer(bitmap: Bitmap): List<RawDetectedFace> = withContext(Dispatchers.IO) {
        Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0))).map { face ->
            RawDetectedFace(
                boundingBox = face.boundingBox,
                eulerX = face.headEulerAngleX,
                eulerY = face.headEulerAngleY,
                eulerZ = face.headEulerAngleZ,
                landmarks = LandmarkTypes.mapNotNull { type ->
                    face.getLandmark(type)?.position?.let { RawFaceLandmark(type, it.x, it.y) }
                },
            )
        }
    }

    override fun close() = detector.close()

    private companion object {
        val LandmarkTypes = listOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM,
        )
    }
}

data class FaceQualityResult(
    val accepted: Boolean,
    val score: Float,
    val crop: Rect,
)

object FaceQualityFilter {
    const val MinimumFacePixels = 96
    const val MinimumScore = 0.58f

    fun evaluate(face: RawDetectedFace, imageWidth: Int, imageHeight: Int): FaceQualityResult {
        val bounds = face.boundingBox
        val shortestSide = min(bounds.width(), bounds.height()).coerceAtLeast(0)
        val sizeScore = (shortestSide / 180f).coerceIn(0f, 1f)
        val poseMagnitude = max(abs(face.eulerX) / 30f, abs(face.eulerY) / 35f)
        val poseScore = (1f - poseMagnitude).coerceIn(0f, 1f)
        val imageBounds = Rect(0, 0, imageWidth, imageHeight)
        val visible = Rect(bounds).also { it.intersect(imageBounds) }
        val originalArea = (bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)).toFloat()
        val visibleScore = if (originalArea == 0f) 0f else visible.width() * visible.height() / originalArea
        val score = (sizeScore * 0.45f + poseScore * 0.40f + visibleScore * 0.15f).coerceIn(0f, 1f)
        return FaceQualityResult(
            accepted = shortestSide >= MinimumFacePixels &&
                abs(face.eulerX) <= 30f && abs(face.eulerY) <= 35f &&
                visibleScore >= 0.92f && score >= MinimumScore,
            score = score,
            crop = squareCrop(bounds, imageWidth, imageHeight),
        )
    }

    private fun squareCrop(bounds: Rect, width: Int, height: Int): Rect {
        val side = (max(bounds.width(), bounds.height()) * 1.35f).roundToInt()
            .coerceAtLeast(1).coerceAtMost(min(width, height))
        val centerX = bounds.centerX().coerceIn(side / 2, width - (side - side / 2))
        val centerY = bounds.centerY().coerceIn(side / 2, height - (side - side / 2))
        return Rect(centerX - side / 2, centerY - side / 2, centerX - side / 2 + side, centerY - side / 2 + side)
    }
}

/** Produces only a bounded transient crop. Callers own and must recycle the returned bitmap. */
object FaceCropper {
    const val OutputPixels = 160

    fun crop(source: Bitmap, spec: Rect, outputPixels: Int = OutputPixels): Bitmap {
        require(outputPixels in 64..512)
        val bounded = Rect(spec).also { check(it.intersect(0, 0, source.width, source.height)) }
        val region = Bitmap.createBitmap(source, bounded.left, bounded.top, bounded.width(), bounded.height())
        return if (region.width == outputPixels && region.height == outputPixels) region else {
            Bitmap.createScaledBitmap(region, outputPixels, outputPixels, true).also { region.recycle() }
        }
    }
}

class FaceDetectionMlEngine(
    private val resolver: ContentResolver,
    private val database: GalleryDatabase,
    private val permission: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val inference: FaceDetectionInference = BundledMlKitFaceDetectionInference(),
) : MlTaskEngine, Closeable {
    private val dao = database.libraryDao()
    override val task = MlTaskType.FaceDetection
    override val modelVersion = ModelVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome = withContext(Dispatchers.IO) {
        val candidates = dao.pendingFaceDetectionCandidates(modelVersion, limit)
        if (candidates.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
        for (candidate in candidates) {
            if (!permission()) return@withContext MlChunkOutcome.PermissionLost
            val bitmap = try {
                resolver.loadThumbnail(candidate.uri(), Size(DetectionThumbnail, DetectionThumbnail), CancellationSignal())
            } catch (_: SecurityException) {
                return@withContext MlChunkOutcome.PermissionLost
            } catch (_: FileNotFoundException) {
                // MediaStore can briefly retain a row after its backing object disappears. Recording an
                // empty generation result prevents one stale row from starving every later chunk; normal
                // observer reconciliation will remove or replace it.
                dao.replaceFaceDetection(
                    FaceDetectionRunEntity(
                        candidate.volumeName, candidate.mediaStoreId, candidate.generationModified,
                        modelVersion, 0, nowMillis(),
                    ),
                    emptyList(),
                )
                continue
            }
            val inferenceBitmap = if (bitmap.config == Config.HARDWARE) {
                bitmap.copy(Config.ARGB_8888, false)
            } else {
                bitmap
            }
            val bitmapWidth = inferenceBitmap.width
            val bitmapHeight = inferenceBitmap.height
            if (bitmapWidth < MinimumInputPixels || bitmapHeight < MinimumInputPixels) {
                if (inferenceBitmap !== bitmap) inferenceBitmap.recycle()
                bitmap.recycle()
                dao.replaceFaceDetection(
                    FaceDetectionRunEntity(
                        candidate.volumeName, candidate.mediaStoreId, candidate.generationModified,
                        modelVersion, 0, nowMillis(),
                    ),
                    emptyList(),
                )
                continue
            }
            val accepted = try {
                inference.infer(inferenceBitmap)
                    .sortedWith(compareBy<RawDetectedFace> { it.boundingBox.top }.thenBy { it.boundingBox.left })
                    .mapNotNull { face ->
                        val quality = FaceQualityFilter.evaluate(face, inferenceBitmap.width, inferenceBitmap.height)
                        if (!quality.accepted) null else face to quality
                    }
            } finally {
                if (inferenceBitmap !== bitmap) inferenceBitmap.recycle()
                bitmap.recycle()
            }
            val run = FaceDetectionRunEntity(
                candidate.volumeName, candidate.mediaStoreId, candidate.generationModified,
                modelVersion, accepted.size, nowMillis(),
            )
            dao.replaceFaceDetection(
                run,
                accepted.mapIndexed { ordinal, (face, quality) ->
                    face.entity(candidate, ordinal, quality, modelVersion, bitmapWidth, bitmapHeight)
                },
            )
        }
        if (candidates.size == limit) MlChunkOutcome.More(candidates.last().key(), candidates.size)
        else MlChunkOutcome.Complete(candidates.size)
    }

    override suspend fun purgeDerivedData() {
        purgeAllPersonIdentityData(database)
    }
    override fun close() { (inference as? Closeable)?.close() }

    companion object {
        const val ModelVersion = "mlkit-face-detection-16.1.7-quality-v1"
        const val DetectionThumbnail = 1_024
        const val MinimumInputPixels = 32
    }
}

private fun RawDetectedFace.entity(
    media: MediaItemEntity,
    ordinal: Int,
    quality: FaceQualityResult,
    modelVersion: String,
    imageWidth: Int,
    imageHeight: Int,
): DetectedFaceEntity {
    fun x(value: Int) = (value * 1000f / imageWidth).roundToInt().coerceIn(0, 1000)
    fun y(value: Int) = (value * 1000f / imageHeight).roundToInt().coerceIn(0, 1000)
    val landmarksJson = JSONArray().also { array ->
        landmarks.forEach { point ->
            array.put(JSONObject().put("t", point.type).put("x", x(point.x.roundToInt())).put("y", y(point.y.roundToInt())))
        }
    }.toString()
    return DetectedFaceEntity(
        media.volumeName, media.mediaStoreId, ordinal, modelVersion,
        x(boundingBox.left), y(boundingBox.top), x(boundingBox.right), y(boundingBox.bottom),
        x(quality.crop.left), y(quality.crop.top), x(quality.crop.right), y(quality.crop.bottom),
        eulerX, eulerY, eulerZ, quality.score, landmarksJson,
    )
}

private fun MediaItemEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaItemEntity.uri() = ContentUris.withAppendedId(
    MediaStore.Images.Media.getContentUri(volumeName), mediaStoreId,
)
