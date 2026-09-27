package com.ugallery.feature.pdfstudio

import android.content.Context
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "gallery_deliveries")
data class PdfGalleryDelivery(
    @PrimaryKey val id: String,
    val name: String,
    val uris: String,
    val error: String? = null,
    // Used from Phase F onward to place a Media-panel drop on a specific project/page/position.
    val targetProjectId: String? = null,
    val targetPageId: String? = null,
    val placementX: Double? = null,
    val placementY: Double? = null,
) {
    fun sources(): List<Uri> {
        val array = JSONArray(uris)
        return List(array.length()) { Uri.parse(array.getString(it)) }
    }

    /** The persisted [PdfFailure] code (or "Cancelled"), without the rejected source number. */
    fun failure(): String? = error?.substringBefore(SOURCE_SEPARATOR)

    /** 1-based position of the source that was rejected, when the failure names one. */
    fun failedSource(): Int? = error?.substringAfter(SOURCE_SEPARATOR, "")?.toIntOrNull()

    companion object {
        const val SOURCE_SEPARATOR = '#'
    }
}

@Dao
interface PdfGalleryDeliveryDao {
    @Query("SELECT * FROM gallery_deliveries ORDER BY rowid")
    fun observe(): Flow<List<PdfGalleryDelivery>>

    @Query("SELECT * FROM gallery_deliveries ORDER BY rowid")
    suspend fun all(): List<PdfGalleryDelivery>

    @Query("SELECT * FROM gallery_deliveries WHERE id = :id")
    suspend fun get(id: String): PdfGalleryDelivery?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun put(row: PdfGalleryDelivery)

    @Query("UPDATE gallery_deliveries SET error = :error WHERE id = :id")
    suspend fun error(id: String, error: String?)

    /** Edits the pending sources list (Replace photo / Remove & retry) and clears any failure in
     * the same statement, since both actions are followed by an immediate retry of the batch. */
    @Query("UPDATE gallery_deliveries SET uris = :uris, error = NULL WHERE id = :id")
    suspend fun updateUris(id: String, uris: String)

    @Query("DELETE FROM gallery_deliveries WHERE id = :id") suspend fun delete(id: String)
}

/**
 * A delivery that failed is kept so the user can retry or discard it, but a later delivery that
 * imports cleanly settles the question: the user has moved on to a selection that works, and
 * leaving the earlier banner up tells them their working import failed.
 */
internal fun supersedesFailedDelivery(
    completed: PdfGalleryDelivery,
    candidate: PdfGalleryDelivery,
): Boolean = candidate.error != null && candidate.id != completed.id

/** Gallery access belongs to the media permission flow, not to SAF persistable URI grants. */
internal class PdfGalleryIntake(context: Context) {
    private val db = PdfProjectDatabase.get(context)
    val deliveries = db.galleryDeliveries().observe()

    suspend fun stage(
        id: String,
        name: String,
        sources: List<Uri>,
        targetProjectId: String? = null,
        targetPageId: String? = null,
        placementX: Double? = null,
        placementY: Double? = null,
    ) =
        withContext(Dispatchers.IO) {
            require(id.isNotBlank() && id.length <= 80)
            require(name.isNotBlank() && name.length <= 80)
            require(sources.size in 1..100)
            require((targetProjectId == null) == (targetPageId == null))
            require((placementX == null) == (placementY == null))
            require(placementX == null || targetPageId != null)
            val uris = sources.map(Uri::toString)
            require(uris.sumOf { it.toByteArray().size } <= 128 * 1024)
            val row =
                PdfGalleryDelivery(
                    id,
                    name,
                    JSONArray(uris).toString(),
                    targetProjectId = targetProjectId,
                    targetPageId = targetPageId,
                    placementX = placementX,
                    placementY = placementY,
                )
            db.withTransaction {
                val existing = db.galleryDeliveries().get(id)
                require(existing == null || existing.uris == row.uris)
                // A caller can die after commit but before consuming its navigation payload.
                // Receipts also prevent resurrecting a completed project after the user deletes it.
                if (db.imports().get(id) == null) db.galleryDeliveries().put(row)
            }
        }

    suspend fun pending() = db.galleryDeliveries().all().firstOrNull { it.error == null }

    suspend fun failed(id: String, code: String, source: Int? = null) =
        withContext(NonCancellable) {
            db.galleryDeliveries()
                .error(id, source?.let { "$code${PdfGalleryDelivery.SOURCE_SEPARATOR}$it" } ?: code)
        }

    suspend fun retry(id: String) = db.galleryDeliveries().error(id, null)

    suspend fun discard(id: String) = db.galleryDeliveries().delete(id)

    /**
     * "Replace photo": swaps the rejected source at [index] (1-based, matching
     * [PdfGalleryDelivery.failedSource]) for [replacement] and clears the failure so the whole
     * batch retries. The all-or-nothing model never partially commits, so this is the only way to
     * recover a batch whose failure names an unsupported/damaged source.
     */
    suspend fun replaceSource(id: String, index: Int, replacement: Uri) =
        withContext(Dispatchers.IO) {
            val row = db.galleryDeliveries().get(id) ?: return@withContext
            val sources = row.sources().toMutableList()
            val position = index - 1
            if (position !in sources.indices) return@withContext
            sources[position] = replacement
            db.galleryDeliveries()
                .updateUris(id, JSONArray(sources.map(Uri::toString)).toString())
        }

    /**
     * "Remove & retry": drops the rejected source at [index] (1-based) from the pending delivery
     * and clears the failure so the remaining sources retry as a batch. Discards the whole
     * delivery instead if that was its only source.
     */
    suspend fun removeSource(id: String, index: Int) =
        withContext(Dispatchers.IO) {
            val row = db.galleryDeliveries().get(id) ?: return@withContext
            val sources = row.sources().toMutableList()
            val position = index - 1
            if (position !in sources.indices) return@withContext
            sources.removeAt(position)
            if (sources.isEmpty()) db.galleryDeliveries().delete(id)
            else
                db.galleryDeliveries()
                    .updateUris(id, JSONArray(sources.map(Uri::toString)).toString())
        }

    suspend fun process(
        row: PdfGalleryDelivery,
        repository: PdfProjectRepository,
        progress: (Int, Int) -> Unit,
    ): PdfProject {
        val project = repository.importGallery(row, progress)
        withContext(NonCancellable) {
            db.galleryDeliveries().delete(row.id)
            db.galleryDeliveries().all()
                .filter { supersedesFailedDelivery(row, it) }
                .forEach { db.galleryDeliveries().delete(it.id) }
        }
        return project
    }
}
