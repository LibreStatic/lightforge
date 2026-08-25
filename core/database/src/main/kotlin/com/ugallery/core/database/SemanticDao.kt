package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

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
           WHERE media_items.isAccessible=1 AND media_items.isTrashed=0
             AND (semantic_embeddings.mediaStoreId IS NULL
               OR semantic_embeddings.generationModified != media_items.generationModified
               OR semantic_embeddings.modelVersion != :modelVersion)
           ORDER BY media_items.timelineSortMillis DESC, media_items.mediaStoreId DESC
           LIMIT :limit""",
    )
    suspend fun pendingMedia(indexId: String, modelVersion: String, limit: Int): List<MediaItemEntity>

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

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh0 IN (:buckets) LIMIT :limit")
    suspend fun band0(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh1 IN (:buckets) LIMIT :limit")
    suspend fun band1(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh2 IN (:buckets) LIMIT :limit")
    suspend fun band2(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh3 IN (:buckets) LIMIT :limit")
    suspend fun band3(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh4 IN (:buckets) LIMIT :limit")
    suspend fun band4(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh5 IN (:buckets) LIMIT :limit")
    suspend fun band5(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh6 IN (:buckets) LIMIT :limit")
    suspend fun band6(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query("SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings WHERE indexId=:indexId AND lsh7 IN (:buckets) LIMIT :limit")
    suspend fun band7(indexId: String, buckets: List<Int>, limit: Int): List<SemanticEmbeddingCandidate>

    @Query(
        """SELECT volumeName,mediaStoreId,quantizedVector FROM semantic_embeddings
           WHERE indexId=:indexId ORDER BY volumeName,mediaStoreId LIMIT :limit OFFSET :offset""",
    )
    suspend fun embeddingPage(indexId: String, offset: Int, limit: Int): List<SemanticEmbeddingCandidate>

    @Query(
        """SELECT * FROM media_items WHERE (volumeName || ':' || mediaStoreId) IN (:encodedKeys)
           AND isAccessible=1 AND isTrashed=0""",
    )
    suspend fun mediaForEncodedKeys(encodedKeys: List<String>): List<MediaItemEntity>
}
