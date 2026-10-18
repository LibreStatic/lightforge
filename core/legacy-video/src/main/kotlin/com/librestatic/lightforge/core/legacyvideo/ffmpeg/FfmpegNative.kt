package com.librestatic.lightforge.core.legacyvideo.ffmpeg

import android.graphics.Bitmap
import android.view.Surface
import java.nio.ByteBuffer

/** JNI entry points of liblightforge_legacy_video. Names are resolved by symbol; see consumer-rules.pro. */
internal object FfmpegNative {
    @JvmStatic external fun nativeOpen(codec: Int, extradata: ByteArray?, threads: Int): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeSendDirect(handle: Long, buf: ByteBuffer, offset: Int, size: Int, timeUs: Long, key: Boolean): Int
    @JvmStatic external fun nativeSendArray(handle: Long, buf: ByteArray, offset: Int, size: Int, timeUs: Long, key: Boolean): Int
    @JvmStatic external fun nativeSendEos(handle: Long): Int
    @JvmStatic external fun nativeReceive(handle: Long, info: LongArray): Int
    @JvmStatic external fun nativeFlush(handle: Long)
    @JvmStatic external fun nativeCopyYuv(handle: Long, dst: ByteBuffer, yStride: Int, uvStride: Int): Boolean
    @JvmStatic external fun nativeRenderCurrent(handle: Long, surface: Surface): Boolean
    @JvmStatic external fun nativeRenderYuv(surface: Surface, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, yStride: Int, uvStride: Int, width: Int, height: Int): Boolean
    @JvmStatic external fun nativeReleaseSurface()
    @JvmStatic external fun nativeCopyBitmap(handle: Long, bitmap: Bitmap): Boolean
}
