package com.ugallery.core.database

data class CleanupSummaryRow(
    val exactGroupCount: Long,
    val exactRecoverableBytes: Long,
    val largeVideoCount: Long,
    val largeVideoBytes: Long,
    val screenshotCount: Long,
    val blurryCandidateCount: Long,
)
