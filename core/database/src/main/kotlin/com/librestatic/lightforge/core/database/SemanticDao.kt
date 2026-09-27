package com.librestatic.lightforge.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction

@Dao
interface SemanticDao {
    @Upsert
    suspend fun upsertIndex(index: SemanticIndexEntity)

    @Query("SELECT * FROM semantic_indexes WHERE indexId=:indexId")
    suspend fun index(indexId: String): SemanticIndexEntity?

    @Query("SELECT * FROM semantic_indexes ORDER BY updatedAtMillis DESC")
    suspend fun indexes(): List<SemanticIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbeddings(embeddings: List<SemanticEmbeddingEntity>)

    @Query(
        """SELECT media_items.* FROM media_items
           LEFT JOIN semantic_embeddings ON semantic_embeddings.indexId=:indexId
             AND semantic_embeddings.volumeName=media_items.volumeName
             AND semantic_embeddings.mediaStoreId=media_items.mediaStoreId
           WHERE media_items.isAccessible=1 AND media_items.isTrashed=0 AND media_items.mediaType IN (1,3)
             AND EXISTS(SELECT 1 FROM semantic_indexes i WHERE i.indexId=:indexId AND i.modelVersion=:modelVersion)
             AND (semantic_embeddings.mediaStoreId IS NULL
               OR semantic_embeddings.generationModified != media_items.generationModified
               OR semantic_embeddings.modelVersion != :modelVersion)
           ORDER BY media_items.timelineSortMillis DESC, media_items.mediaStoreId DESC
           LIMIT :limit""",
    )
    suspend fun pendingMedia(indexId: String, modelVersion: String, limit: Int): List<MediaItemEntity>

    @Query("""SELECT EXISTS(SELECT 1 FROM media_items m JOIN semantic_indexes i
        ON i.indexId=:indexId AND i.modelVersion=:modelVersion
        WHERE m.volumeName=:volume AND m.mediaStoreId=:id AND m.generationModified=:generation
        AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3))""")
    suspend fun canCommitEmbedding(indexId: String, modelVersion: String, volume: String, id: Long, generation: Long): Boolean

    /** A delayed inference may never recreate a deleted index or attach to a changed provider row. */
    @Transaction
    suspend fun upsertCurrentEmbeddings(indexId: String, rows: List<SemanticEmbeddingEntity>): Int {
        require(rows.size <= 2000)
        require(rows.all { it.indexId == indexId })
        val current = rows.filter { row ->
            canCommitEmbedding(indexId, row.modelVersion, row.volumeName, row.mediaStoreId, row.generationModified)
        }
        if (current.isNotEmpty()) upsertEmbeddings(current)
        return current.size
    }

    @Query("""UPDATE semantic_indexes SET status='active',embeddedCount=:count,updatedAtMillis=:updatedAtMillis
        WHERE indexId=:indexId""")
    suspend fun markIndexActiveIfPresent(indexId: String, count: Long, updatedAtMillis: Long): Int

    @Query("SELECT COUNT(*) FROM semantic_embeddings WHERE indexId=:indexId")
    suspend fun embeddingCount(indexId: String): Long

    @Query("SELECT SUM(LENGTH(quantizedVector)) FROM semantic_embeddings WHERE indexId=:indexId")
    suspend fun embeddingPayloadBytes(indexId: String): Long?

    @Query("DELETE FROM semantic_indexes WHERE indexId=:indexId")
    suspend fun deleteIndex(indexId: String)

    @Query("DELETE FROM semantic_indexes WHERE modelId=:modelId")
    suspend fun deleteIndexesForModel(modelId: String)

    @Query("DELETE FROM semantic_indexes")
    suspend fun deleteAllIndexes()

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh0 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band0(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh1 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band1(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh2 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band2(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh3 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band3(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh4 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band4(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh5 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band5(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh6 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band6(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) AND e.lsh7 IN (:buckets) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit")
    suspend fun band7(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query(
        """SELECT e.volumeName,e.mediaStoreId,e.quantizedVector,e.generationModified FROM semantic_embeddings e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId AND m.generationModified=e.generationModified JOIN semantic_indexes i ON i.indexId=e.indexId AND i.modelVersion=e.modelVersion WHERE e.indexId=:indexId AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType IN (1,3) ORDER BY e.volumeName,e.mediaStoreId LIMIT :limit OFFSET :offset""",
    )
    suspend fun embeddingPage(indexId: String, offset: Int, limit: Int): List<SemanticEmbeddingCandidate>

    @Query(
        """SELECT * FROM media_items WHERE (volumeName || ':' || mediaStoreId) IN (:encodedKeys)
           AND isAccessible=1 AND isTrashed=0""",
    )
    suspend fun mediaForEncodedKeys(encodedKeys: List<String>): List<MediaItemEntity>
}
