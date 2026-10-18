package com.librestatic.lightforge.core.legacyvideo

/** MIME types for legacy FLV video codecs that Media3 cannot demux or decode on its own. */
object LegacyVideoMimeTypes {
    /** Sorenson Spark (H.263 variant), FLV codec id 2. */
    const val VIDEO_FLV1 = "video/x-flv1"

    /** On2 VP6, FLV codec id 4. */
    const val VIDEO_VP6F = "video/x-vp6f"

    /** On2 VP6 with alpha, FLV codec id 5. */
    const val VIDEO_VP6A = "video/x-vp6a"

    fun isLegacy(mimeType: String?): Boolean =
        mimeType == VIDEO_FLV1 || mimeType == VIDEO_VP6F || mimeType == VIDEO_VP6A
}
