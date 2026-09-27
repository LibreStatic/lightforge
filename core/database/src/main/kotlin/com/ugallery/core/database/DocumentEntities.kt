package com.ugallery.core.database

import androidx.room.*

/** User decisions, separate from replaceable OCR/model output; originals are not moved. */
@Entity(
    tableName = "document_annotations",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["volumeName", "mediaStoreId"],
                childColumns = ["volumeName", "mediaStoreId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class DocumentAnnotationEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val category: String,
    val updatedAtMillis: Long,
)

data class DocumentRow(
    @Embedded val media: MediaItemEntity,
    val category: String?,
    val ocrText: String?,
    val archived: Boolean,
)

internal const val DocumentFrom =
    """ FROM media_items m
LEFT JOIN document_annotations c ON c.volumeName=m.volumeName AND c.mediaStoreId=m.mediaStoreId
LEFT JOIN media_ocr o ON o.volumeName=m.volumeName AND o.mediaStoreId=m.mediaStoreId AND o.generationModified=m.generationModified
LEFT JOIN archived_media a ON a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId
WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1 """
internal const val DocumentEligible =
    """ AND (
c.category IN ('Receipt','Ticket','Note','Other') OR (c.category IS NULL AND (
length(trim(o.rawText))>0 OR EXISTS (
SELECT 1 FROM media_labels l JOIN media_label_runs r ON r.volumeName=l.volumeName AND r.mediaStoreId=l.mediaStoreId
WHERE l.volumeName=m.volumeName AND l.mediaStoreId=m.mediaStoreId AND r.generationModified=m.generationModified
AND r.modelVersion=l.modelVersion AND l.canonicalLabel='document' AND l.confidence>=0.5
AND NOT EXISTS (SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='document'))))) """
internal const val DocumentProjection =
    "SELECT m.*, c.category, substr(o.rawText,1,20000) AS ocrText, (a.mediaStoreId IS NOT NULL) AS archived "

@Dao
interface DocumentDao {
    @Query(
        DocumentProjection +
            DocumentFrom +
            """ AND (
        (:category='Excluded' AND c.category='Excluded') OR
        (:category!='Excluded' """ +
            DocumentEligible +
            """ AND
            (:category='All' OR (:category='Suggested' AND c.category IS NULL) OR c.category=:category)))
        AND (coalesce(m.displayName,'') LIKE :search ESCAPE '\' OR coalesce(o.rawText,'') LIKE :search ESCAPE '\')
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC"""
    )
    fun page(category: String, search: String): androidx.paging.PagingSource<Int, DocumentRow>

    /** Bounded, non-paged list for surfaces that just need "the most recent eligible documents"
     * without a scrolling PagingSource (PDF Studio's Media panel, Phase F item 3). */
    @Query(
        DocumentProjection +
            DocumentFrom +
            DocumentEligible +
            " ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit"
    )
    suspend fun recent(limit: Int): List<DocumentRow>

    @Query("SELECT COUNT(*) " + DocumentFrom + DocumentEligible)
    fun count(): kotlinx.coroutines.flow.Flow<Long>

    @Query(DocumentProjection + DocumentFrom + " AND m.volumeName=:volume AND m.mediaStoreId=:id")
    fun observe(volume: String, id: Long): kotlinx.coroutines.flow.Flow<DocumentRow?>

    @Query(DocumentProjection + DocumentFrom + " AND m.volumeName=:volume AND m.mediaStoreId=:id")
    suspend fun get(volume: String, id: Long): DocumentRow?

    @Upsert suspend fun put(annotation: DocumentAnnotationEntity)

    @Query("DELETE FROM document_annotations WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun clear(volume: String, id: Long)

    @Query(
        "SELECT archivedAtMillis FROM archived_media WHERE volumeName=:volume AND mediaStoreId=:id"
    )
    suspend fun archivedAt(volume: String, id: Long): Long?
}
