package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ugallery.core.model.EditOperationCodec
import com.ugallery.core.model.EditRecipe
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.flow.Flow

@Dao
interface EditRecipeDao {
    @Query("SELECT * FROM edit_recipes WHERE recipeId=:recipeId")
    suspend fun recipe(recipeId: String): EditRecipeEntity?

    @Query("SELECT * FROM edit_operations WHERE recipeId=:recipeId ORDER BY ordinal")
    suspend fun operations(recipeId: String): List<EditOperationEntity>

    @Query("SELECT * FROM edit_recipes WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun recipeForSource(volumeName: String, mediaStoreId: Long): EditRecipeEntity?

    @Query("SELECT * FROM edit_recipes ORDER BY updatedAtMillis DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EditRecipeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecipe(recipe: EditRecipeEntity)

    @Query("DELETE FROM edit_operations WHERE recipeId=:recipeId")
    suspend fun deleteOperations(recipeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperations(operations: List<EditOperationEntity>)

    @Query("DELETE FROM edit_recipes WHERE recipeId=:recipeId")
    suspend fun deleteRecipe(recipeId: String)

    @Transaction
    suspend fun replace(recipe: EditRecipe, nowMillis: Long) {
        val source = requireNotNull(recipe.source) { "Ephemeral recipes cannot be persisted" }
        upsertRecipe(
            EditRecipeEntity(
                recipeId = recipe.recipeId,
                volumeName = source.volumeName,
                mediaStoreId = source.mediaStoreId,
                sourceGenerationModified = recipe.sourceGenerationModified,
                revision = recipe.revision,
                createdAtMillis = recipeCreatedAt(recipe.recipeId, nowMillis),
                updatedAtMillis = nowMillis,
            ),
        )
        deleteOperations(recipe.recipeId)
        insertOperations(recipe.operations.mapIndexed { ordinal, operation ->
            EditOperationEntity(recipe.recipeId, ordinal, EditOperationCodec.encode(operation))
        })
    }

    suspend fun load(recipeId: String): EditRecipe? {
        val stored = recipe(recipeId) ?: return null
        return EditRecipe(
            recipeId = stored.recipeId,
            source = MediaKey(stored.volumeName, stored.mediaStoreId),
            sourceGenerationModified = stored.sourceGenerationModified,
            operations = operations(stored.recipeId).map { EditOperationCodec.decode(it.encodedOperation) },
            revision = stored.revision,
        )
    }

    private suspend fun recipeCreatedAt(recipeId: String, nowMillis: Long): Long =
        recipe(recipeId)?.createdAtMillis ?: nowMillis
}
