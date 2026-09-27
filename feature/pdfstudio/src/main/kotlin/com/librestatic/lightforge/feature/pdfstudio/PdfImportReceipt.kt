package com.librestatic.lightforge.feature.pdfstudio

import androidx.room.*

/** A committed import is acknowledged even if Android restores an older picker delivery. */
@Entity(tableName = "import_receipts")
data class PdfImportReceipt(@PrimaryKey val requestId: String, val projectId: String)

@Dao
interface PdfImportReceiptDao {
    @Query("SELECT * FROM import_receipts WHERE requestId = :id")
    suspend fun get(id: String): PdfImportReceipt?

    @Insert suspend fun put(receipt: PdfImportReceipt)
}
