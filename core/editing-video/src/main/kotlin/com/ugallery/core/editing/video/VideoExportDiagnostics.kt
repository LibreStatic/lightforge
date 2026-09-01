package com.ugallery.core.editing.video

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException

@androidx.annotation.OptIn(UnstableApi::class)
fun videoExportDiagnostic(failure: Throwable, fallback: String): String {
    val causes = generateSequence(failure) { it.cause }.toList()
    val errorCode = causes.filterIsInstance<ExportException>().firstOrNull()?.errorCodeName
    val details = causes.mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    return (listOfNotNull(errorCode) + details)
        .joinToString(": ")
        .ifBlank { fallback }
        .take(500)
}
