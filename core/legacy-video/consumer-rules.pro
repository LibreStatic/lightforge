# The JNI bridge resolves these natives by name (Java_..._FfmpegNative_*), so R8 must neither
# rename the class nor strip/rename its native methods.
-keep class com.librestatic.lightforge.core.legacyvideo.ffmpeg.FfmpegNative {
    native <methods>;
}
