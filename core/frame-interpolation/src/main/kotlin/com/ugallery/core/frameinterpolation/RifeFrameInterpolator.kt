package com.ugallery.core.frameinterpolation

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FrameInterpolationBackend { Vulkan, Cpu }

data class FrameInterpolationCapability(
    val backend: FrameInterpolationBackend,
    val modelVersion: String = RifeFrameInterpolator.ModelVersion,
)

/** Thread-confined RIFE v4.6 interpolator. Calls are serialized by [interpolate]. */
class RifeFrameInterpolator(
    context: Context,
    preferVulkan: Boolean = true,
) : Closeable {
    private var handle: Long

    val capability: FrameInterpolationCapability

    init {
        val modelDir = extractModel(context.applicationContext)
        handle = nativeCreate(modelDir.absolutePath, preferVulkan)
        check(handle != 0L) { "The bundled slow-motion model could not be initialized" }
        capability = FrameInterpolationCapability(
            if (nativeUsesVulkan(handle)) FrameInterpolationBackend.Vulkan else FrameInterpolationBackend.Cpu,
        )
    }

    @Synchronized
    fun interpolate(first: Bitmap, second: Bitmap, timestep: Float): Bitmap {
        check(handle != 0L) { "Interpolator is closed" }
        require(first.width == second.width && first.height == second.height)
        require(timestep > 0f && timestep < 1f)
        val a = first.asArgb8888()
        val b = second.asArgb8888()
        val output = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
        val status = nativeInterpolate(handle, a, b, timestep, output)
        if (a !== first) a.recycle()
        if (b !== second) b.recycle()
        check(status == 0) { "RIFE interpolation failed ($status)" }
        return output
    }

    suspend fun interpolateAsync(first: Bitmap, second: Bitmap, timestep: Float): Bitmap =
        withContext(Dispatchers.Default) { interpolate(first, second, timestep) }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeClose(handle)
        handle = 0L
    }

    private external fun nativeCreate(modelDirectory: String, preferVulkan: Boolean): Long
    private external fun nativeUsesVulkan(handle: Long): Boolean
    private external fun nativeInterpolate(
        handle: Long,
        first: Bitmap,
        second: Bitmap,
        timestep: Float,
        output: Bitmap,
    ): Int
    private external fun nativeClose(handle: Long)

    companion object {
        const val ModelVersion = "rife-ncnn-v4.6-a7532fc3"
        private const val AssetDirectory = "models/rife-v4.6"

        init {
            System.loadLibrary("ncnn")
            System.loadLibrary("ugallery_frame_interpolation")
        }

        @Synchronized
        private fun extractModel(context: Context): File {
            val destination = File(context.noBackupFilesDir, "models/$ModelVersion")
            val expected = mapOf("flownet.bin" to 10_614_320L, "flownet.param" to 16_532L)
            expected.forEach { (name, size) ->
                val output = File(destination, name)
                if (output.length() == size) return@forEach
                destination.mkdirs()
                val temporary = File(destination, "$name.tmp")
                context.assets.open("$AssetDirectory/$name").use { input ->
                    FileOutputStream(temporary).use(input::copyTo)
                }
                check(temporary.length() == size) { "Bundled RIFE model is incomplete" }
                check(temporary.renameTo(output)) { "Could not install bundled RIFE model" }
            }
            return destination
        }
    }
}

private fun Bitmap.asArgb8888(): Bitmap =
    if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
