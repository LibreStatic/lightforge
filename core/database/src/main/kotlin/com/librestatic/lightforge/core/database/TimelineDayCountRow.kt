package com.librestatic.lightforge.core.database

/**
 * Query projection only: rows the timeline shows for one local day (`yyyy-MM-dd`), plus the key of
 * the day's earliest row (its cover in the date picker).
 */
data class TimelineDayCountRow(
    val day: String,
    val count: Int,
    val mediaStoreId: Long? = null,
    val volumeName: String? = null,
    val generationModified: Long? = null,
)
