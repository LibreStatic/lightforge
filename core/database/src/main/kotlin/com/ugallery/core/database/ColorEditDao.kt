package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ColorEditDao {
    @Query("SELECT * FROM video_edit_recipes WHERE volumeName=:volume AND mediaStoreId=:id AND sourceGenerationModified=:generation")
    suspend fun videoRecipe(volume: String, id: Long, generation: Long): VideoEditRecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveVideoRecipe(recipe: VideoEditRecipeEntity)

    @Query("DELETE FROM video_edit_recipes WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun deleteVideoRecipes(volume: String, id: Long)

    @Query("SELECT * FROM custom_luts ORDER BY displayName COLLATE NOCASE")
    fun customLuts(): Flow<List<CustomLutEntity>>

    @Query("SELECT * FROM custom_luts WHERE lutId=:id")
    suspend fun customLut(id: Long): CustomLutEntity?

    @Query("SELECT * FROM custom_luts WHERE sha256=:sha256 LIMIT 1")
    suspend fun customLutByHash(sha256: String): CustomLutEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM custom_luts WHERE displayName=:displayName COLLATE NOCASE)")
    suspend fun hasCustomLutNamed(displayName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomLut(lut: CustomLutEntity): Long

    @Delete
    suspend fun deleteCustomLut(lut: CustomLutEntity)
}
