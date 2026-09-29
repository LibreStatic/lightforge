package com.librestatic.lightforge.feature.videoeditor

/** UI position only: never normalizes or changes the source recipe. End is exclusive. */
internal fun videoEditorDraftPosition(
    positionMillis: Long,
    trimStartMillis: Long,
    trimEndMillis: Long,
    durationMillis: Long,
): Long {
    val duration = durationMillis.coerceAtLeast(1)
    val start = trimStartMillis.coerceIn(0, duration - 1)
    val end = trimEndMillis.takeIf { it > start }?.coerceAtMost(duration) ?: duration
    return positionMillis.coerceIn(start, end - 1)
}

/** A Loading/Released controller has no trustworthy position; preserve the saved draft value. */
internal fun videoEditorCheckpointPosition(
    readyPlayerPositionMillis: Long?,
    savedPositionMillis: Long,
    trimStartMillis: Long,
    trimEndMillis: Long,
    durationMillis: Long,
): Long = videoEditorDraftPosition(
    readyPlayerPositionMillis ?: savedPositionMillis,
    trimStartMillis,
    trimEndMillis,
    durationMillis,
)

/** Exact editing timecode, with localized surrounding labels supplied by the UI. */
internal fun formatVideoEditorDraftTime(millis: Long): String {
    require(millis >= 0)
    val minutes = millis / 60_000
    val seconds = (millis / 1_000) % 60
    val fraction = millis % 1_000
    return "$minutes:${seconds.toString().padStart(2, '0')}.${fraction.toString().padStart(3, '0')}"
}

/** Compact timecode for the timeline labels (`m:ss`); the exact value stays in the semantics. */
internal fun formatVideoEditorShortTime(millis: Long): String {
    require(millis >= 0)
    val totalSeconds = millis / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
