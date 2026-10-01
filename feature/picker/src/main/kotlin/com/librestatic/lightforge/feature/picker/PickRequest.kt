package com.librestatic.lightforge.feature.picker

import android.content.Intent

/** Which items of one media kind a caller accepts. */
sealed interface KindFilter {
    data object None : KindFilter
    data object Any : KindFilter
    data class Exact(val mimeTypes: Set<String>) : KindFilter

    operator fun plus(other: KindFilter): KindFilter = when {
        this == Any || other == Any -> Any
        this is Exact && other is Exact -> Exact(mimeTypes + other.mimeTypes)
        this is Exact -> this
        else -> other
    }

    val accepts: Boolean get() = this != None
}

/**
 * What a GET_CONTENT or PICK caller asked for. The caller's MIME type, its EXTRA_MIME_TYPES and,
 * for a PICK on a MediaStore collection, the collection URI all narrow the same two filters.
 */
data class PickRequest(
    val images: KindFilter,
    val videos: KindFilter,
    val allowMultiple: Boolean,
) {
    /** The caller asked only for types this gallery does not hold (e.g. PDFs). */
    val isUnsatisfiable: Boolean get() = !images.accepts && !videos.accepts

    companion object {
        fun from(intent: Intent): PickRequest = parse(
            type = intent.type,
            extraMimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList(),
            dataPath = intent.data?.path,
            allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        )

        fun parse(
            type: String?,
            extraMimeTypes: List<String>?,
            dataPath: String?,
            allowMultiple: Boolean,
        ): PickRequest {
            // EXTRA_MIME_TYPES refines a broad type such as "*/*"; it wins whenever it is set.
            val requested = extraMimeTypes?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)
                ?: listOfNotNull(type?.takeIf(String::isNotBlank))
            var images: KindFilter = KindFilter.None
            var videos: KindFilter = KindFilter.None
            requested.map { it.trim().lowercase() }.forEach { mime ->
                when {
                    mime == "*/*" || mime == "*" -> { images += KindFilter.Any; videos += KindFilter.Any }
                    mime == "image/*" || mime == CURSOR_DIR_IMAGE -> images += KindFilter.Any
                    mime == "video/*" || mime == CURSOR_DIR_VIDEO -> videos += KindFilter.Any
                    mime.startsWith("image/") -> images += KindFilter.Exact(setOf(mime))
                    mime.startsWith("video/") -> videos += KindFilter.Exact(setOf(mime))
                }
            }
            if (requested.isEmpty()) {
                // A PICK on a MediaStore collection URI carries no type of its own.
                val segments = dataPath?.lowercase()?.split('/').orEmpty()
                when {
                    "images" in segments -> images = KindFilter.Any
                    "video" in segments -> videos = KindFilter.Any
                    else -> { images = KindFilter.Any; videos = KindFilter.Any }
                }
            }
            return PickRequest(images, videos, allowMultiple)
        }

        private const val CURSOR_DIR_IMAGE = "vnd.android.cursor.dir/image"
        private const val CURSOR_DIR_VIDEO = "vnd.android.cursor.dir/video"
    }
}
