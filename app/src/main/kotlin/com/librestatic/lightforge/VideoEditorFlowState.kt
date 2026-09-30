package com.librestatic.lightforge

import com.librestatic.lightforge.core.editing.video.SlowMotionSegment
import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.core.editing.video.VideoOutputQuality
import com.librestatic.lightforge.core.editing.video.VideoDynamicRange

internal val VideoExportJob.isActive: Boolean
    get() = status == VideoExportJobStatus.Queued || status == VideoExportJobStatus.Running

/** Active jobs in the same FIFO order WorkManager will execute them. */
internal fun activeVideoExportQueue(jobs: List<VideoExportJob>): List<VideoExportJob> =
    jobs.asSequence()
        .filter { it.isActive }
        .sortedBy(VideoExportJob::createdAtMillis)
        .toList()

/** Progress shown around the global Updates affordance; null means queued but not started. */
internal fun activeVideoExportProgress(jobs: List<VideoExportJob>): Float? =
    activeVideoExportQueue(jobs)
        .firstOrNull { it.status == VideoExportJobStatus.Running }
        ?.progressPermille
        ?.div(1000f)

/** A terminal export from an earlier editing visit must not be attached to a newly opened editor. */
internal fun activeVideoExportForInput(
    jobs: List<VideoExportJob>,
    inputUri: String,
): VideoExportJob? = jobs.asSequence()
    .filter { it.inputUri == inputUri && it.isActive }
    .maxByOrNull(VideoExportJob::createdAtMillis)

/** Once an editor owns an export id, follow that exact job through its terminal state. */
internal fun trackedVideoExport(
    jobs: List<VideoExportJob>,
    exportJobId: String?,
    inputUri: String,
): VideoExportJob? = exportJobId?.let { id -> jobs.firstOrNull { it.id == id } }
    ?: activeVideoExportForInput(jobs, inputUri)

/**
 * An export owns the recipe that was queued. A persisted draft only supersedes it when it was
 * written after that export started; otherwise it can be a stale debounce result from before save.
 */
internal fun preferredVideoEditorRecipe(
    storedRecipe: VideoEditRecipe?,
    storedUpdatedAtMillis: Long?,
    activeExportRecipe: VideoEditRecipe?,
    activeExportCreatedAtMillis: Long?,
): VideoEditRecipe? = when {
    storedRecipe != null && (
        activeExportCreatedAtMillis == null ||
            storedUpdatedAtMillis != null && storedUpdatedAtMillis > activeExportCreatedAtMillis
        ) -> storedRecipe
    activeExportRecipe != null -> activeExportRecipe
    else -> storedRecipe
}

internal fun clampVideoPosition(
    positionMillis: Long,
    trimStartMillis: Long,
    trimEndMillis: Long,
): Long = positionMillis.coerceIn(
    trimStartMillis,
    (trimEndMillis - 1).coerceAtLeast(trimStartMillis),
)

internal fun VideoEditRecipe.normalizedForEditor(
    durationMillis: Long,
    supportsHevcMain10: Boolean,
    supportsHlgExport: Boolean = supportsHevcMain10,
    supportsHdr10Export: Boolean = supportsHevcMain10,
): VideoEditRecipe {
    val legacySegmentEnd = endMillis ?: durationMillis
    val withModernSlowMotion = if (
        speed in setOf(0.5f, 0.25f, 0.125f) &&
        slowMotionSegments.isEmpty() &&
        legacySegmentEnd > startMillis
    ) {
        copy(
            speed = 1f,
            slowMotionSegments = listOf(
                SlowMotionSegment(
                    startMillis = startMillis,
                    endMillis = legacySegmentEnd,
                    speed = speed,
                ),
            ),
        )
    } else this
    val withSupportedCodec = if (
        withModernSlowMotion.outputQuality == VideoOutputQuality.HevcMain10 &&
        !supportsHevcMain10
    ) {
        withModernSlowMotion.copy(outputQuality = VideoOutputQuality.H264Compatible)
    } else withModernSlowMotion
    val rangeSupported = when (withSupportedCodec.dynamicRange) {
        VideoDynamicRange.SdrRec709 -> true
        VideoDynamicRange.HdrHlg -> supportsHlgExport
        VideoDynamicRange.Hdr10Pq -> supportsHdr10Export
    }
    val withSupportedRange = if (!rangeSupported) {
        withSupportedCodec.copy(dynamicRange = VideoDynamicRange.SdrRec709)
    } else withSupportedCodec
    val consistentOutput = if (withSupportedRange.dynamicRange != VideoDynamicRange.SdrRec709) {
        withSupportedRange.copy(outputQuality = VideoOutputQuality.HevcMain10)
    } else withSupportedRange
    return if (consistentOutput.endMillis == durationMillis) {
        consistentOutput.copy(endMillis = null)
    } else consistentOutput
}
