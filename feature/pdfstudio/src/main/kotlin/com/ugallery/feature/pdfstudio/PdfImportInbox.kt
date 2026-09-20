package com.ugallery.feature.pdfstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

@Entity(tableName = "import_deliveries")
data class PdfImportDelivery(
    @PrimaryKey val id: String,
    val portable: Boolean,
    val projectId: String?,
    val pageId: String?,
    val uris: String,
) {
    internal fun request(): PdfImportRequest {
        val a = JSONArray(uris)
        require(a.length() in 1..if (portable) 1 else 100)
        return PdfImportRequest(
            id,
            portable,
            projectId,
            pageId,
            List(a.length()) { a.getString(it) },
        )
    }
}

@Dao
interface PdfImportDeliveryDao {
    @Query("SELECT * FROM import_deliveries ORDER BY rowid")
    suspend fun all(): List<PdfImportDelivery>

    @Query("SELECT * FROM import_deliveries WHERE id = :id")
    suspend fun get(id: String): PdfImportDelivery?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun put(delivery: PdfImportDelivery)

    @Query("DELETE FROM import_deliveries WHERE id = :id") suspend fun delete(id: String)
}

/** Durable foreground intake. Reopening Studio resumes delivery without Activity saved state. */
internal class PdfImportInbox(private val context: Context) {
    private val db = PdfProjectDatabase.get(context)
    private val queue = PdfExportQueue(context)

    suspend fun pending(): List<PdfImportRequest> = db.importDeliveries().all().map { it.request() }

    suspend fun stage(request: PdfImportRequest) =
        withContext(Dispatchers.IO) {
            require(request.uris.size in 1..if (request.portable) 1 else 100)
            require(request.uris.sumOf { it.toByteArray().size } <= 128 * 1024)
            PdfPermissionLock.mutex.withLock {
                val delivery =
                    PdfImportDelivery(
                        request.id,
                        request.portable,
                        request.projectId,
                        request.pageId,
                        JSONArray(request.uris).toString(),
                    )
                val existing = db.importDeliveries().get(request.id)
                require(existing == null || existing == delivery)
                // The journal exists before grant acquisition or source copying. Grant ownership is
                // shared with exports, so neither subsystem revokes permissions still needed by the
                // other.
                db.importDeliveries().put(delivery)
                if (db.imports().get(request.id) == null) {
                    request.uris
                        .withIndex()
                        .distinctBy { it.value }
                        .filter { Uri.parse(it.value).scheme == "content" }
                        .forEach { source ->
                            try {
                                queue.acquireGrantUnlocked(
                                    Uri.parse(source.value),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // De-duplicating grant work must not renumber the user's sources.
                                throw PdfSourceFailure(source.index + 1, e)
                            }
                        }
                }
            }
        }

    suspend fun finish(id: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            PdfPermissionLock.mutex.withLock { db.importDeliveries().delete(id) }
            queue.releaseUnusedGrants()
        }
}

/** Independent of source-copy serialization: picker delivery must journal access even during IO. */
internal object PdfPermissionLock {
    val mutex = kotlinx.coroutines.sync.Mutex()
}
