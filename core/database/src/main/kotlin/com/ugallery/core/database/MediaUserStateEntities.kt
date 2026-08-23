package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "video_playback_positions",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoPlaybackPositionEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
)
