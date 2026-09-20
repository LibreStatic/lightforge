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
) {
    fun sources(): List<Uri> {
        val array = JSONArray(uris)
        return List(array.length()) { Uri.parse(array.getString(it)) }
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

    @Query("DELETE FROM gallery_deliveries WHERE id = :id") suspend fun delete(id: String)
}

/**
 * A delivery that failed is kept so the user can retry or discard it, but a later delivery of the
 * same selection that imports cleanly settles the question: the earlier failure is stale, and
 * leaving its banner up tells the user their working import failed.
 */
internal fun supersedesFailedDelivery(
    completed: PdfGalleryDelivery,
    candidate: PdfGalleryDelivery,
): Boolean = candidate.error != null &&
    candidate.id != completed.id &&
    candidate.uris == completed.uris

/** Gallery access belongs to the media permission flow, not to SAF persistable URI grants. */
internal class PdfGalleryIntake(context: Context) {
    private val db = PdfProjectDatabase.get(context)
    val deliveries = db.galleryDeliveries().observe()

    suspend fun stage(id: String, name: String, sources: List<Uri>) =
        withContext(Dispatchers.IO) {
            require(id.isNotBlank() && id.length <= 80)
            require(name.isNotBlank() && name.length <= 80)
            require(sources.size in 1..100)
            val uris = sources.map(Uri::toString)
            require(uris.sumOf { it.toByteArray().size } <= 128 * 1024)
            val row = PdfGalleryDelivery(id, name, JSONArray(uris).toString())
            db.withTransaction {
                val existing = db.galleryDeliveries().get(id)
                require(existing == null || existing.uris == row.uris)
                // A caller can die after commit but before consuming its navigation payload.
                // Receipts also prevent resurrecting a completed project after the user deletes it.
                if (db.imports().get(id) == null) db.galleryDeliveries().put(row)
            }
        }

    suspend fun pending() = db.galleryDeliveries().all().firstOrNull { it.error == null }

    suspend fun failed(id: String, code: String) =
        withContext(NonCancellable) { db.galleryDeliveries().error(id, code) }

    suspend fun retry(id: String) = db.galleryDeliveries().error(id, null)

    suspend fun discard(id: String) = db.galleryDeliveries().delete(id)

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
