package com.librestatic.lightforge.feature.pdfstudio

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class PdfExportPhase {
    Queued,
    Running,
    Ready,
    Publishing,
    Published,
    Failed,
    Cancelled,
    Cancelling,
}

@Entity(tableName = "export_jobs")
data class PdfExportJob(
    @PrimaryKey val id: String,
    val workId: String,
    val projectName: String,
    val manifest: String,
    val compact: Boolean,
    val status: String,
    val completed: Int,
    val total: Int,
    val created: Long,
    val updated: Long,
    val destination: String? = null,
    val error: String? = null,
    val outputHash: String? = null,
    @ColumnInfo(defaultValue = "0") val outputBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val portable: Boolean = false,
) {
    val phase: PdfExportPhase
        get() = PdfExportPhase.valueOf(status)

    val keepsSources: Boolean
        get() = phase !in setOf(PdfExportPhase.Published, PdfExportPhase.Cancelled)

    fun interrupted(): PdfExportJob =
        if (
            phase in
                setOf(
                    PdfExportPhase.Cancelled,
                    PdfExportPhase.Cancelling,
                    PdfExportPhase.Published,
                    PdfExportPhase.Ready,
                )
        )
            this
        else copy(status = PdfExportPhase.Queued.name, completed = 0, error = null)
}

@Dao
interface PdfExportDao {
    @Query(
        "SELECT id,workId,projectName,'' AS manifest,compact,status,completed,total,created,updated,destination,error,outputHash,outputBytes,portable FROM export_jobs ORDER BY created DESC"
    )
    fun observe(): Flow<List<PdfExportJob>>

    @Query("SELECT * FROM export_jobs") suspend fun all(): List<PdfExportJob>

    @Query("SELECT * FROM export_jobs WHERE id=:id") suspend fun get(id: String): PdfExportJob?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(job: PdfExportJob)

    @Query("DELETE FROM export_jobs WHERE id=:id") suspend fun delete(id: String)
}

internal object PdfStorageLock {
    val mutex = kotlinx.coroutines.sync.Mutex()
}

@Entity(tableName = "destination_grants")
data class PdfDestinationGrant(@PrimaryKey val uri: String, val ownedFlags: Int)

@Dao
interface PdfDestinationGrantDao {
    @Query("SELECT * FROM destination_grants") suspend fun all(): List<PdfDestinationGrant>

    @Query("SELECT * FROM destination_grants WHERE uri=:uri")
    suspend fun get(uri: String): PdfDestinationGrant?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(grant: PdfDestinationGrant)

    @Query("DELETE FROM destination_grants WHERE uri=:uri") suspend fun delete(uri: String)
}

internal object PdfWorkLocks {
    private val locks =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    fun forJob(id: String): kotlinx.coroutines.sync.Mutex =
        locks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }
}
