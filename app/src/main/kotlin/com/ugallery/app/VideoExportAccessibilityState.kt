package com.ugallery.app

import com.ugallery.core.editing.video.VideoExportPhase

/** Semantic status only. Keep the exact progress range separate from this coarse spoken value. */
internal data class VideoExportAccessibilityState(
    val status: VideoExportJobStatus,
    val phase: VideoExportPhase?,
    val percent: Int?,
)

/**
 * Equal states within a phase/10-percent bucket produce unchanged live-region text.
 * Null progress means unknown, not zero. The persisted queue currently stores only Int;
 * callers must not infer unknown progress from a legitimate zero or fabricate a fraction.
 * Queued and terminal labels come from status; neither carries a misleading percentage.
 */
internal fun videoExportAccessibilityState(
    status: VideoExportJobStatus,
    phase: VideoExportPhase,
    progressPermille: Int?,
): VideoExportAccessibilityState = VideoExportAccessibilityState(
    status = status,
    phase = phase.takeIf { status == VideoExportJobStatus.Running },
    percent = if (status == VideoExportJobStatus.Running) {
        progressPermille?.coerceIn(0, 1000)?.let { (it / 100) * 10 }
    } else null,
)

/**
 * Only a terminal transition of the same export previously observed active is announced.
 * The host retains the previous snapshot and consumes each transition once; initial history,
 * replacement by a different job, and repeated terminal snapshots never announce completion.
 * Status is authoritative: Failed/Cancelled can retain the last rendering phase in the store.
 */
internal fun videoExportTerminalAnnouncement(
    previous: VideoExportJob?,
    current: VideoExportJob,
): VideoExportJobStatus? {
    if (previous == null || previous.id != current.id) return null
    if (previous.status != VideoExportJobStatus.Queued && previous.status != VideoExportJobStatus.Running) return null
    return current.status.takeIf {
        it == VideoExportJobStatus.Completed || it == VideoExportJobStatus.Failed || it == VideoExportJobStatus.Cancelled
    }
}
