package com.librestatic.lightforge.core.database

import androidx.room.Embedded

/** Query projection only: no new table or schema version. */
data class StackTimelineRow(
    @Embedded val media: MediaItemEntity,
    val timelineStackId: String?,
    val timelineStackRevision: String?,
    val timelineStackCount: Int,
)
