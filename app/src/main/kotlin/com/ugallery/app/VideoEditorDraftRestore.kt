package com.ugallery.app

import java.io.Serializable

/** Common editor review state; source validation belongs to each concrete snapshot. */
interface VideoEditorDraftRestore : Serializable {
    val id: String
    val durationMillis: Long
    val recipe: String
    val baselineRecipe: String
    val positionMillis: Long
    val pendingExportRecipe: String?
    val exportJobId: String?
    val selectedAnnotationId: String?
    val selectedSlowMotionSegmentId: String?
    val slowMotionMarkInMillis: Long?
    val annotationTrackingCorrectionMillis: Long?
    val undoAnnotations: List<String>
    val redoAnnotations: List<String>
}
