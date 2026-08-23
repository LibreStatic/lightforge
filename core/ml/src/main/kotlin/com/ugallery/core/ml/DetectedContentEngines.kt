package com.ugallery.core.ml

import android.content.ContentResolver
import android.content.ContentUris
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.LabelSuppressionEntity
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.MediaLabelEntity
import com.ugallery.core.database.MediaLabelRunEntity
import com.ugallery.core.database.MediaOcrEntity
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.search.AppSearchMediaIndex
import com.ugallery.core.search.MediaSearchDocument
import com.ugallery.core.search.SearchTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.FileNotFoundException
import java.util.Locale

data class RawImageLabel(val text: String, val confidence: Float)
fun interface ImageLabelInference { suspend fun infer(bitmap: android.graphics.Bitmap): List<RawImageLabel> }
data class RawOcrResult(val text: String, val blocksJson: String)
fun interface OcrInference { suspend fun infer(bitmap: android.graphics.Bitmap): RawOcrResult }

class BundledMlKitImageLabelInference : ImageLabelInference, Closeable {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(ImageLabelMlEngine.MinConfidence).build(),
    )
    override suspend fun infer(bitmap: android.graphics.Bitmap): List<RawImageLabel> = withContext(Dispatchers.IO) {
        Tasks.await(labeler.process(InputImage.fromBitmap(bitmap, 0))).map { RawImageLabel(it.text, it.confidence) }
    }
    override fun close() = labeler.close()
}

class BundledMlKitOcrInference : OcrInference, Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    override suspend fun infer(bitmap: android.graphics.Bitmap): RawOcrResult = withContext(Dispatchers.IO) {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val blocks = JSONArray()
        text.textBlocks.forEach { block ->
            val box = block.boundingBox
            blocks.put(JSONObject().apply {
                put("text", block.text)
                if (box != null) put("box", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
            })
        }
        RawOcrResult(text.text, blocks.toString())
    }
    override fun close() = recognizer.close()
}

class ImageLabelMlEngine(
    private val resolver: ContentResolver,
    database: GalleryDatabase,
    private val searchIndex: AppSearchMediaIndex,
    private val permission: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val inference: ImageLabelInference = BundledMlKitImageLabelInference(),
) : MlTaskEngine, Closeable {
    private val dao = database.libraryDao()
    override val task = MlTaskType.ImageLabels
    override val modelVersion = ModelVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome = withContext(Dispatchers.IO) {
        val candidates = dao.pendingLabelCandidates(modelVersion, limit)
        if (candidates.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
        searchIndex.ensureSchema()
        val suppressed = dao.suppressedLabels().toSet()
        for (candidate in candidates) {
            if (!permission()) return@withContext MlChunkOutcome.PermissionLost
            val detected = try {
                detect(candidate)
            } catch (_: FileNotFoundException) {
                // MediaStore can notify only its collection URI for deletions. If the
                // corresponding row is still cached locally, drop it instead of retrying
                // the entire ML chunk forever on an item that no longer exists.
                dao.deleteMedia(candidate.volumeName, candidate.mediaStoreId)
                continue
            } catch (_: SecurityException) {
                return@withContext MlChunkOutcome.PermissionLost
            }
            val labels = detected
                .filter { it.confidence >= MinConfidence }
                .groupBy(DetectedLabel::canonical)
                .mapNotNull { (_, values) -> values.maxByOrNull(DetectedLabel::confidence) }
                .filterNot { it.canonical in suppressed }
                .map { label ->
                    MediaLabelEntity(
                        candidate.volumeName, candidate.mediaStoreId, label.canonical,
                        label.raw, label.confidence, modelVersion,
                    )
                }
            searchIndex.put(listOf(candidate.searchDocument(labels, dao.ocr(candidate.volumeName, candidate.mediaStoreId))))
            dao.replaceLabelResult(
                MediaLabelRunEntity(
                    candidate.volumeName, candidate.mediaStoreId, candidate.generationModified,
                    modelVersion, nowMillis(),
                ),
                labels,
            )
        }
        if (candidates.size == limit) {
            MlChunkOutcome.More(candidates.last().key(), candidates.size)
        } else MlChunkOutcome.Complete(candidates.size)
    }

    override suspend fun purgeDerivedData() {
        dao.purgeLabels(); dao.purgeLabelRuns(); searchIndex.clear()
    }

    override fun close() { (inference as? Closeable)?.close(); searchIndex.close() }

    private suspend fun detect(candidate: MediaItemEntity): List<DetectedLabel> {
        val bitmap = resolver.loadThumbnail(candidate.uri(), Size(LabelThumbnail, LabelThumbnail), CancellationSignal())
        if (bitmap.width < MinimumInputPixels || bitmap.height < MinimumInputPixels) return emptyList()
        return inference.infer(bitmap).map { label ->
            DetectedLabel(LabelCanonicalizer.canonical(label.text), label.text, label.confidence)
        }
    }

    private data class DetectedLabel(val canonical: String, val raw: String, val confidence: Float)

    companion object {
        const val ModelVersion = "mlkit-image-labeling-17.0.9-default"
        const val SearchModelVersion = 17_009L
        const val MinConfidence = 0.60f
        const val LabelThumbnail = 640
        const val MinimumInputPixels = 32
    }
}

class OcrMlEngine(
    private val resolver: ContentResolver,
    database: GalleryDatabase,
    private val searchIndex: AppSearchMediaIndex,
    private val permission: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val inference: OcrInference = BundledMlKitOcrInference(),
) : MlTaskEngine, Closeable {
    private val dao = database.libraryDao()
    override val task = MlTaskType.Ocr
    override val modelVersion = ModelVersion
    override fun hasCurrentPermission() = permission()

    override suspend fun process(afterExclusive: MediaKey?, limit: Int): MlChunkOutcome = withContext(Dispatchers.IO) {
        val candidates = dao.pendingOcrCandidates(modelVersion, limit)
        if (candidates.isEmpty()) return@withContext MlChunkOutcome.Complete(0)
        searchIndex.ensureSchema()
        val suppressed = dao.suppressedLabels().toSet()
        for (candidate in candidates) {
            if (!permission()) return@withContext MlChunkOutcome.PermissionLost
            val detected = try {
                recognize(candidate)
            } catch (_: FileNotFoundException) {
                dao.deleteMedia(candidate.volumeName, candidate.mediaStoreId)
                continue
            } catch (_: SecurityException) {
                return@withContext MlChunkOutcome.PermissionLost
            }
            val result = MediaOcrEntity(
                candidate.volumeName, candidate.mediaStoreId, candidate.generationModified,
                modelVersion, detected.text, SearchTextNormalizer.normalize(detected.text),
                detected.blocksJson, nowMillis(),
            )
            val labels = dao.labels(candidate.volumeName, candidate.mediaStoreId)
                .filterNot { it.canonicalLabel in suppressed }
            searchIndex.put(listOf(candidate.searchDocument(labels, result)))
            dao.upsertOcr(result)
        }
        if (candidates.size == limit) MlChunkOutcome.More(candidates.last().key(), candidates.size)
        else MlChunkOutcome.Complete(candidates.size)
    }

    override suspend fun purgeDerivedData() { dao.purgeOcr(); searchIndex.clear() }
    override fun close() { (inference as? Closeable)?.close(); searchIndex.close() }

    private suspend fun recognize(candidate: MediaItemEntity): OcrDetected {
        val bitmap = resolver.loadThumbnail(candidate.uri(), Size(OcrThumbnail, OcrThumbnail), CancellationSignal())
        if (bitmap.width < MinimumInputPixels || bitmap.height < MinimumInputPixels) {
            return OcrDetected("", "[]")
        }
        val text = inference.infer(bitmap)
        return OcrDetected(text.text, text.blocksJson)
    }

    private data class OcrDetected(val text: String, val blocksJson: String)

    companion object {
        const val ModelVersion = "mlkit-text-recognition-16.0.1-latin"
        const val SearchModelVersion = 16_001L
        const val OcrThumbnail = 2_048
        const val MinimumInputPixels = 32
    }
}

class DetectedContentRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()
    suspend fun labels(key: MediaKey) = dao.labels(key.volumeName, key.mediaStoreId)
    suspend fun ocr(key: MediaKey) = dao.ocr(key.volumeName, key.mediaStoreId)
    suspend fun suppressLabel(canonicalLabel: String) {
        dao.suppressLabel(LabelSuppressionEntity(canonicalLabel, nowMillis()))
        dao.purgeLabelRuns()
    }
    suspend fun unsuppressLabel(canonicalLabel: String) {
        dao.unsuppressLabel(canonicalLabel)
        dao.purgeLabelRuns()
    }
}

object LabelCanonicalizer {
    private val Known = mapOf(
        "beach" to "beach", "sea" to "beach", "coast" to "beach",
        "dog" to "dog", "puppy" to "dog", "cat" to "cat", "kitten" to "cat",
        "food" to "food", "dish" to "food", "document" to "document",
        "text" to "document", "screen" to "screenshot", "screenshot" to "screenshot",
        "person" to "person", "people" to "person",
    )

    fun canonical(raw: String): String {
        val normalized = SearchTextNormalizer.normalize(raw)
        return Known[normalized] ?: normalized.replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}

private suspend fun MediaItemEntity.searchDocument(
    labels: List<MediaLabelEntity>,
    ocr: MediaOcrEntity?,
) = MediaSearchDocument(
    key = key(), kind = if (mediaType == 3) MediaKind.Video else MediaKind.Image,
    mimeType = mimeType, displayName = displayName, bucketName = bucketDisplayName,
    timelineSortMillis = timelineSortMillis, generationModified = generationModified,
    favorite = isFavorite, ocrText = ocr?.rawText,
    canonicalLabels = labels.map(MediaLabelEntity::canonicalLabel),
    labelModelVersion = if (labels.isEmpty()) 0 else ImageLabelMlEngine.SearchModelVersion,
    ocrModelVersion = if (ocr == null) 0 else OcrMlEngine.SearchModelVersion,
)

private fun MediaItemEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaItemEntity.uri() = ContentUris.withAppendedId(
    MediaStore.Images.Media.getContentUri(volumeName), mediaStoreId,
)
