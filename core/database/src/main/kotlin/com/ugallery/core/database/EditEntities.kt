package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "edit_recipes",
    indices = [
        Index(name = "index_edit_recipe_source", value = ["volumeName", "mediaStoreId"]),
        Index(name = "index_edit_recipe_updated", value = ["updatedAtMillis"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EditRecipeEntity(
    @androidx.room.PrimaryKey val recipeId: String,
    val volumeName: String,
    val mediaStoreId: Long,
    val sourceGenerationModified: Long,
    val revision: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "edit_operations",
    primaryKeys = ["recipeId", "ordinal"],
    indices = [Index(name = "index_edit_operation_recipe", value = ["recipeId", "ordinal"])],
    foreignKeys = [
        ForeignKey(
            entity = EditRecipeEntity::class,
            parentColumns = ["recipeId"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EditOperationEntity(
    val recipeId: String,
    val ordinal: Int,
    val encodedOperation: String,
)
