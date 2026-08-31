package com.ugallery.core.editing.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogProfileDetector(private val context: Context) {
    suspend fun detect(uri: Uri): LogProfileDetection = withContext(Dispatchers.IO) {
        val description = buildString {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    append(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)).append(' ')
                    append(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER)).append(' ')
                    append(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE))
                }
            }
            runCatching {
                val extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                try {
                    repeat(extractor.trackCount) { index -> append(' ').append(extractor.getTrackFormat(index)) }
                } finally { extractor.release() }
            }
        }
        detectDescription(description)
    }

    internal companion object {
        fun detectDescription(description: String): LogProfileDetection {
            val normalized = description.lowercase().replace('_', ' ')
            return DetectionRules.firstOrNull { (needles, _) ->
                needles.any { needle -> normalized.containsMetadataToken(needle) }
            }
            ?.let { (_, profile) -> LogProfileDetection(profile, 0.92f, "Container or codec metadata") }
            ?: LogProfileDetection(LogInputProfile.Standard, 0.25f, "No reliable LOG profile metadata")
        }

        private fun String.containsMetadataToken(token: String): Boolean {
            var start = indexOf(token)
            while (start >= 0) {
                val end = start + token.length
                val startsAtBoundary = start == 0 || !this[start - 1].isLetterOrDigit()
                val endsAtBoundary = end == length || !this[end].isLetterOrDigit()
                if (startsAtBoundary && endsAtBoundary) return true
                start = indexOf(token, start + 1)
            }
            return false
        }

        val DetectionRules = listOf(
            listOf("apple log", "applelog") to LogInputProfile.AppleLog,
            listOf("s-log3", "slog3", "s log3") to LogInputProfile.SonySLog3,
            listOf("s-log2", "slog2", "s log2") to LogInputProfile.SonySLog2,
            listOf("canon log 3", "canonlog3", "c-log3", "clog3") to LogInputProfile.CanonLog3,
            listOf("canon log 2", "canonlog2", "c-log2", "clog2") to LogInputProfile.CanonLog2,
            listOf("v-log", "vlog") to LogInputProfile.PanasonicVLog,
            listOf("d-log", "dlog") to LogInputProfile.DjiDLog,
            listOf("f-log2", "flog2") to LogInputProfile.FujifilmFLog2,
            listOf("f-log", "flog") to LogInputProfile.FujifilmFLog,
            listOf("n-log", "nlog") to LogInputProfile.NikonNLog,
            listOf("blackmagic film", "bmdfilm") to LogInputProfile.BlackmagicFilmGen5,
            listOf("logc4", "log c4") to LogInputProfile.ArriLogC4,
            listOf("logc3", "log c3", "arri log c") to LogInputProfile.ArriLogC3,
            listOf("log3g10", "redlog3g10") to LogInputProfile.RedLog3G10,
        )
    }
}
