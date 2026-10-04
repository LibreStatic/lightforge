package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogProfileDetector(private val context: Context) {
    suspend fun detect(uri: Uri): LogProfileDetection = withContext(Dispatchers.IO) {
        var videoFormat: MediaFormat? = null
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
                    repeat(extractor.trackCount) { index ->
                        val format = extractor.getTrackFormat(index)
                        if (videoFormat == null && format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            videoFormat = format
                        }
                        append(' ').append(format)
                    }
                } finally { extractor.release() }
            }
        }
        videoFormat?.let { format ->
            detectOpenCineLog(
                location = location(uri),
                colorStandard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD),
                colorTransfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                colorRange = format.intOrNull(MediaFormat.KEY_COLOR_RANGE),
                frameRate = format.frameRateOrNull(),
            )
        } ?: detectDescription(description)
    }

    /** Relative path plus display name, e.g. "DCIM/OpenCineCam/OCC_TAKE_x/OCC_x.mp4". */
    private fun location(uri: Uri): String {
        val columns = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, OpenableColumns.DISPLAY_NAME)
        val fromProvider = runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    .takeIf { it >= 0 }?.let(cursor::getString).orEmpty()
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let(cursor::getString).orEmpty()
                path + name
            }
        }.getOrNull()
        // Providers without RELATIVE_PATH reject the projection; retry with the name alone.
            ?: runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        return fromProvider ?: uri.path.orEmpty()
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** KEY_FRAME_RATE is an Integer or a Float depending on the extractor. */
    private fun MediaFormat.frameRateOrNull(): Float? =
        if (containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() }.getOrNull()
        } else null

    internal companion object {
        /**
         * OpenCineCam leaves the transfer unspecified because OCLog2 has no H.273 code; its sidecar
         * names the curve, but it lives in Download where a gallery cannot read it. The camera's
         * file layout plus the full-range BT.2020 non-HDR signature identify the take instead. The
         * sidecar's middle-grey reference is unknown here, so the default NATIVE one is assumed.
         */
        fun detectOpenCineLog(
            location: String,
            colorStandard: Int?,
            colorTransfer: Int?,
            colorRange: Int?,
            frameRate: Float?,
        ): LogProfileDetection? {
            val fromOpenCineCam = location.contains("OpenCineCam/OCC_TAKE_") ||
                OpenCineFileName.containsMatchIn(location)
            val signature = colorStandard == MediaFormat.COLOR_STANDARD_BT2020 &&
                colorRange == MediaFormat.COLOR_RANGE_FULL &&
                colorTransfer !in NamedTransfers
            if (!fromOpenCineCam || !signature) return null
            // Constrained high-speed takes (120/240 fps) come from the ISP's Rec.709 tier.
            val profile = if ((frameRate ?: 0f) >= 100f) LogInputProfile.OpenCineLog2Hfr else LogInputProfile.OpenCineLog2Hlg
            return LogProfileDetection(profile, 0.9f, "OpenCineCam file layout and unspecified BT.2020 transfer")
        }

        private val OpenCineFileName = Regex("""(^|/)OCC_[^/]+\.mp4$""", RegexOption.IGNORE_CASE)

        private val NamedTransfers = setOf(
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            MediaFormat.COLOR_TRANSFER_HLG,
            MediaFormat.COLOR_TRANSFER_ST2084,
        )

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
