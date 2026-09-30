package com.librestatic.lightforge.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_edit_recipes",
    primaryKeys = ["volumeName", "mediaStoreId", "sourceGenerationModified"],
    indices = [Index(name = "index_video_edit_recipe_updated", value = ["updatedAtMillis"])],
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["volumeName", "mediaStoreId"],
        childColumns = ["volumeName", "mediaStoreId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class VideoEditRecipeEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val sourceGenerationModified: Long,
    val encodedRecipe: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "custom_luts",
    indices = [Index(value = ["displayName"], unique = true)],
)
data class CustomLutEntity(
    @PrimaryKey(autoGenerate = true) val lutId: Long = 0,
    val displayName: String,
    val fileName: String,
    val cubeSize: Int,
    val sha256: String,
    val importedAtMillis: Long,
)
