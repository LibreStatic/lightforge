package com.librestatic.lightforge.core.ml

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
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.LibraryDao
import com.librestatic.lightforge.core.database.LabelSuppressionEntity
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MediaLabelEntity
import com.librestatic.lightforge.core.database.MediaLabelRunEntity
import com.librestatic.lightforge.core.database.MediaOcrEntity
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.search.AppSearchMediaIndex
import com.librestatic.lightforge.core.search.MediaSearchDocument
import com.librestatic.lightforge.core.search.MediaSearchIndex
import com.librestatic.lightforge.core.search.SearchTextNormalizer
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
            } catch (_: android.graphics.ImageDecoder.DecodeException) {
                // The file exists but the platform decoder cannot produce a bitmap from it
                // (truncated download, unsupported vendor codec, corrupt sector). Retrying is
                // pointless: nothing about this generation of the file will ever decode, and a
                // retry loop here starves every later item in the library forever. Record an
                // empty result for this generation + model version so the candidate query stops
                // selecting it. If the user later re-edits or re-downloads the file, MediaStore
                // assigns a new generationModified and the item is re-admitted automatically.
                dao.replaceLabelResult(
                    MediaLabelRunEntity(
                        candidate.volumeName, candidate.mediaStoreId, candidate.generationModified,
                        modelVersion, nowMillis(),
                    ),
                    emptyList(),
                )
                continue
            } catch (_: SecurityException) {
                return@withContext MlChunkOutcome.PermissionLost
            }
            val labels = detected
                .filter { it.confidence >= MinConfidence }
                .groupBy(DetectedLabel::canonical)
                .mapNotNull { (_, values) -> values.maxByOrNull(DetectedLabel::confidence) }
                .map { label ->
                    MediaLabelEntity(
                        candidate.volumeName, candidate.mediaStoreId, label.canonical,
                        label.raw, label.confidence, modelVersion,
                    )
                }
                .keepStrongestPetLabel()
                .filterNot { it.canonicalLabel in suppressed }
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
        dao.purgeLabels(); dao.purgeLabelRuns(); rewriteSearchDocuments(dao, searchIndex)
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
        const val ModelVersion = "mlkit-image-labeling-17.0.9-default-pet-exclusive"
        const val SearchModelVersion = 17_009L
        const val MinConfidence = 0.60f
        const val LabelThumbnail = 640
        const val MinimumInputPixels = 32
    }
}

/** Dog and cat are mutually exclusive automatic collections for a single media item. */
internal fun List<MediaLabelEntity>.keepStrongestPetLabel(): List<MediaLabelEntity> {
    val petLabels = filter { it.canonicalLabel == "dog" || it.canonicalLabel == "cat" }
    if (petLabels.size < 2) return this
    val winner = petLabels.maxWithOrNull(
        compareBy<MediaLabelEntity> { it.confidence }
            .thenBy { if (it.canonicalLabel == "dog") 1 else 0 },
    ) ?: return this
    return filter { it.canonicalLabel != "dog" && it.canonicalLabel != "cat" || it === winner }
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
            } catch (_: android.graphics.ImageDecoder.DecodeException) {
                // Undecodable bytes, not a transient failure: no amount of retrying turns a
                // truncated or codec-unsupported file into a bitmap, and retrying blocks the whole
                // chunk (and therefore the rest of the library) indefinitely. An empty recognition
                // is already a legitimate outcome here, so persist one for this generation +
                // model version; a later edit to the file bumps generationModified and the item
                // is re-admitted to the candidate set on its own.
                OcrDetected("", "[]")
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

    override suspend fun purgeDerivedData() { dao.purgeOcr(); rewriteSearchDocuments(dao, searchIndex) }
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
    durationMillis = durationMillis,
)

/**
 * Labels and OCR live inside each media's AppSearch document, next to its file name and folder.
 * After purging them from Room, re-put every document from Room instead of clearing the index,
 * so keyword search keeps working and only the ML-derived fields disappear.
 */
internal suspend fun rewriteSearchDocuments(dao: LibraryDao, index: MediaSearchIndex) {
    index.ensureSchema()
    var after: MediaKey? = null
    while (true) {
        val page = dao.searchRebuildPage(after?.volumeName, after?.mediaStoreId ?: Long.MIN_VALUE, MediaSearchIndex.MaxBatchSize)
        if (page.isEmpty()) return
        index.put(page.map { row ->
            val media = row.media
            MediaSearchDocument(
                key = media.key(), kind = if (media.mediaType == 3) MediaKind.Video else MediaKind.Image,
                mimeType = media.mimeType, displayName = media.displayName, bucketName = media.bucketDisplayName,
                timelineSortMillis = media.timelineSortMillis, generationModified = media.generationModified,
                favorite = media.isFavorite, width = media.width, height = media.height, ocrText = row.ocrText,
                canonicalLabels = row.canonicalLabelsCsv?.split(',').orEmpty(),
                labelModelVersion = if (row.canonicalLabelsCsv == null) 0 else ImageLabelMlEngine.SearchModelVersion,
                ocrModelVersion = if (row.ocrText == null) 0 else OcrMlEngine.SearchModelVersion,
                durationMillis = media.durationMillis,
            )
        })
        after = page.last().media.key()
    }
}

private fun MediaItemEntity.key() = MediaKey(volumeName, mediaStoreId)
private fun MediaItemEntity.uri() = ContentUris.withAppendedId(
    MediaStore.Images.Media.getContentUri(volumeName), mediaStoreId,
)
