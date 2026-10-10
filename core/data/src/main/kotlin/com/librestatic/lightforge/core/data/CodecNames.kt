package com.librestatic.lightforge.core.data

import android.media.MediaCodecInfo.CodecProfileLevel as P
import android.media.MediaFormat

/** Maps the integer profile/level constants in a [MediaFormat] to the names used in codec specs. */
internal object CodecNames {
    fun videoProfile(mime: String?, profile: Int): String? = when (mime?.lowercase()) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> when (profile) {
            P.AVCProfileBaseline -> "Baseline"
            P.AVCProfileConstrainedBaseline -> "Constrained Baseline"
            P.AVCProfileMain -> "Main"
            P.AVCProfileExtended -> "Extended"
            P.AVCProfileHigh -> "High"
            P.AVCProfileConstrainedHigh -> "Constrained High"
            P.AVCProfileHigh10 -> "High 10"
            P.AVCProfileHigh422 -> "High 4:2:2"
            P.AVCProfileHigh444 -> "High 4:4:4"
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
            P.HEVCProfileMain -> "Main"
            P.HEVCProfileMain10 -> "Main 10"
            P.HEVCProfileMainStill -> "Main Still Picture"
            P.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
            P.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_VP9 -> when (profile) {
            P.VP9Profile0 -> "Profile 0"
            P.VP9Profile1 -> "Profile 1"
            P.VP9Profile2, P.VP9Profile2HDR -> "Profile 2"
            P.VP9Profile3, P.VP9Profile3HDR -> "Profile 3"
            P.VP9Profile2HDR10Plus -> "Profile 2 HDR10+"
            P.VP9Profile3HDR10Plus -> "Profile 3 HDR10+"
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
            P.AV1ProfileMain8 -> "Main 8"
            P.AV1ProfileMain10 -> "Main 10"
            P.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
            P.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> null
        }
        else -> null
    }

    fun videoLevel(mime: String?, level: Int): String? {
        if (level <= 0) return null
        return when (mime?.lowercase()) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> when (level) {
                P.AVCLevel1 -> "1"
                P.AVCLevel1b -> "1b"
                P.AVCLevel11 -> "1.1"
                P.AVCLevel12 -> "1.2"
                P.AVCLevel13 -> "1.3"
                P.AVCLevel2 -> "2"
                P.AVCLevel21 -> "2.1"
                P.AVCLevel22 -> "2.2"
                P.AVCLevel3 -> "3"
                P.AVCLevel31 -> "3.1"
                P.AVCLevel32 -> "3.2"
                P.AVCLevel4 -> "4"
                P.AVCLevel41 -> "4.1"
                P.AVCLevel42 -> "4.2"
                P.AVCLevel5 -> "5"
                P.AVCLevel51 -> "5.1"
                P.AVCLevel52 -> "5.2"
                P.AVCLevel6 -> "6"
                P.AVCLevel61 -> "6.1"
                P.AVCLevel62 -> "6.2"
                else -> null
            }
            MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                // Main and High tier alternate per level, so bit 2n is Main and 2n+1 High.
                val bit = Integer.numberOfTrailingZeros(level)
                val name = HevcLevels.getOrNull(bit / 2) ?: return null
                if (bit % 2 == 1) "$name (High tier)" else name
            }
            MediaFormat.MIMETYPE_VIDEO_VP9 -> Vp9Levels.getOrNull(Integer.numberOfTrailingZeros(level))
            MediaFormat.MIMETYPE_VIDEO_AV1 -> Av1Levels.getOrNull(Integer.numberOfTrailingZeros(level))
            else -> null
        }
    }

    /** Bit depth implied by the profile, or null when the profile does not settle it. */
    fun videoBitDepth(mime: String?, profile: Int): Int? = when (mime?.lowercase()) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> when (profile) {
            P.AVCProfileBaseline, P.AVCProfileConstrainedBaseline, P.AVCProfileMain, P.AVCProfileExtended,
            P.AVCProfileHigh, P.AVCProfileConstrainedHigh -> 8
            P.AVCProfileHigh10 -> 10
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
            P.HEVCProfileMain, P.HEVCProfileMainStill -> 8
            P.HEVCProfileMain10, P.HEVCProfileMain10HDR10, P.HEVCProfileMain10HDR10Plus -> 10
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_VP9 -> when (profile) {
            P.VP9Profile0, P.VP9Profile1 -> 8
            P.VP9Profile2, P.VP9Profile2HDR, P.VP9Profile2HDR10Plus -> 10
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
            P.AV1ProfileMain8 -> 8
            P.AV1ProfileMain10, P.AV1ProfileMain10HDR10, P.AV1ProfileMain10HDR10Plus -> 10
            else -> null
        }
        else -> null
    }

    fun aacProfile(profile: Int): String? = when (profile) {
        P.AACObjectMain -> "Main"
        P.AACObjectLC -> "LC"
        P.AACObjectSSR -> "SSR"
        P.AACObjectLTP -> "LTP"
        P.AACObjectHE -> "HE-AAC"
        P.AACObjectScalable -> "Scalable"
        P.AACObjectERLC -> "ER LC"
        P.AACObjectLD -> "LD"
        P.AACObjectHE_PS -> "HE-AACv2"
        P.AACObjectELD -> "ELD"
        P.AACObjectXHE -> "xHE-AAC"
        else -> null
    }

    fun pcmBitDepth(encoding: Int): Int? = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    private val HevcLevels = listOf("1", "2", "2.1", "3", "3.1", "4", "4.1", "5", "5.1", "5.2", "6", "6.1", "6.2")
    private val Vp9Levels = listOf("1", "1.1", "2", "2.1", "3", "3.1", "4", "4.1", "5", "5.1", "5.2", "6", "6.1", "6.2")
    private val Av1Levels = listOf(
        "2.0", "2.1", "2.2", "2.3", "3.0", "3.1", "3.2", "3.3", "4.0", "4.1", "4.2", "4.3",
        "5.0", "5.1", "5.2", "5.3", "6.0", "6.1", "6.2", "6.3", "7.0", "7.1", "7.2", "7.3",
    )
}
