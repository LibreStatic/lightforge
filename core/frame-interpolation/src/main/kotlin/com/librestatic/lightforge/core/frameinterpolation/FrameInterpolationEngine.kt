package com.librestatic.lightforge.core.frameinterpolation

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which accelerator the user asked for. [Automatic] prefers the DSP, which keeps up with live previews and uses less
 * energy per frame than the GPU, and falls back to the GPU where the DSP is unavailable.
 */
enum class FrameInterpolationEngine { Automatic, Dsp, Gpu }

/** Capabilities of the Hexagon DSP (HVX) backend. */
object FrameInterpolationEngines {
    /**
     * Whether the cDSP can run the slow-motion network on this device. The first answer opens a real DSP session
     * (loads the skel and the weights) and is cached per build fingerprint and library version.
     */
    suspend fun isDspAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (!HvxSupport.isSupportedAbi()) return@withContext false
        val cache = HvxSupport.cache(appContext)
        val key = EngineCacheKeys.dsp(Build.FINGERPRINT)
        cache.get(key)?.let { return@withContext it.toBoolean() }
        val modelDir = RifeFrameInterpolator.extractModel(appContext)
        val session = HvxSession.open(appContext, modelDir)
        session?.close()
        (session != null).also { cache.put(key, it.toString()) }
    }
}

/** What [RifeFrameInterpolator] should do once it knows what the device offers. */
internal enum class BackendChoice { UseHvx, UseVulkan, UseCpu }

internal object EngineSelection {
    /** Pure decision. [accelerated] is false when the caller forced the CPU (`preferVulkan = false`). */
    fun choose(
        engine: FrameInterpolationEngine,
        accelerated: Boolean,
        hvxAvailable: Boolean,
        vulkanAvailable: Boolean,
    ): BackendChoice {
        if (!accelerated) return BackendChoice.UseCpu
        val vulkan = if (vulkanAvailable) BackendChoice.UseVulkan else BackendChoice.UseCpu
        return when (engine) {
            FrameInterpolationEngine.Gpu -> vulkan
            FrameInterpolationEngine.Dsp, FrameInterpolationEngine.Automatic ->
                if (hvxAvailable) BackendChoice.UseHvx else vulkan
        }
    }

    /** The DSP network runs at multiples of 32; the guided compose rescales the flow per axis, so it may be anisotropic. */
    fun networkExtent(lowEdge: Int): Int = maxOf(32, (lowEdge + 16) / 32 * 32)

    /** Even-sized copy of a frame whose long edge is at most [longEdge]. */
    fun lowResolution(width: Int, height: Int, longEdge: Int = 512): Pair<Int, Int> {
        val scale = minOf(1f, longEdge.toFloat() / maxOf(width, height))
        return ((width * scale).toInt() and 1.inv()).coerceAtLeast(2) to
            ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
    }
}

internal object EngineCacheKeys {
    /** Bump when the HVX skel or the weight conversion changes, to invalidate stored results. */
    const val LibraryVersion = "lfrife-cd0c6ce-4"

    fun dsp(fingerprint: String) = "dsp|$fingerprint|$LibraryVersion"
}

/** Small key/value store in `noBackupFilesDir`, so a result measured on one device never restores onto another. */
internal class EngineCache(private val file: File) {
    fun get(key: String): String? = synchronized(Lock) { load().getProperty(key) }

    fun put(key: String, value: String) = synchronized(Lock) {
        val properties = load()
        properties.setProperty(key, value)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        runCatching {
            FileOutputStream(temporary).use { properties.store(it, null) }
            check(temporary.renameTo(file))
        }.onFailure { temporary.delete() }
        Unit
    }

    private fun load(): Properties = Properties().also { properties ->
        runCatching { if (file.isFile) file.inputStream().use(properties::load) }
    }

    private companion object {
        val Lock = Any()
    }
}

/** JNI entry points of the Hexagon backend (hvx_jni.cpp). */
internal object HvxNative {
    init {
        System.loadLibrary("ncnn")
        System.loadLibrary("lightforge_frame_interpolation")
    }

    /** False on ABIs other than arm64-v8a, where only stubs are built. */
    external fun nativeCompiled(): Boolean

    /**
     * A native pointer, or an error code in [OpenErrorCodes]. The pointer can be negative as a Long: arm64
     * heap pointers carry a tag in their top byte.
     */
    external fun nativeOpen(skelDir: String, modelDir: String, blobCache: String, width: Int, height: Int): Long
    external fun nativeClose(handle: Long)

    /** 0, a negative bitmap error, or at most [DspFailure] when the DSP call failed. */
    external fun nativeInterpolateGuided(
        handle: Long,
        lowFirst: Bitmap,
        lowSecond: Bitmap,
        highFirst: Bitmap,
        highSecond: Bitmap,
        timestep: Float,
        output: Bitmap,
        netWidth: Int,
        netHeight: Int,
    ): Int

    const val DspFailure = -1001

    /** What [nativeOpen] returns on failure: 0 or a negative lfrife / blob error code. */
    val OpenErrorCodes = -1000L..0L
}

internal object HvxSupport {
    private const val SkelAsset = "hvx/liblfrife_skel.so"
    private const val SkelName = "liblfrife_skel.so"

    fun isSupportedAbi(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") && HvxNative.nativeCompiled()

    fun cache(context: Context) = EngineCache(File(context.noBackupFilesDir, "hvx/engine-cache.properties"))

    fun blobCache(context: Context) = File(
        context.noBackupFilesDir,
        "hvx/rife46_hvx.${RifeFrameInterpolator.ModelVersion}.${EngineCacheKeys.LibraryVersion}.bin",
    )

    /** Blobs of earlier model or library versions are never read again; each one is about 11 MB. */
    fun deleteStaleBlobs(current: File) {
        current.parentFile?.listFiles { file ->
            file.name.startsWith("rife46_hvx.") && file.name.endsWith(".bin") && file.name != current.name
        }?.forEach(File::delete)
    }

    /** Copies the DSP skel out of the APK (it is loaded by the DSP through file reads, not by the linker). */
    @Synchronized
    fun installSkel(context: Context): File {
        val directory = File(context.noBackupFilesDir, "hvx")
        val output = File(directory, SkelName)
        // Compared byte for byte (it is ~50 KB): an update can ship a different skel of the same size.
        val bundled = context.assets.open(SkelAsset).use { it.readBytes() }
        if (output.length() == bundled.size.toLong() && output.readBytes().contentEquals(bundled)) return directory
        directory.mkdirs()
        val temporary = File(directory, "$SkelName.tmp")
        FileOutputStream(temporary).use { it.write(bundled) }
        check(temporary.length() == bundled.size.toLong()) { "Bundled HVX skel is incomplete" }
        temporary.setReadable(true, false)
        check(temporary.renameTo(output)) { "Could not install the HVX skel" }
        return directory
    }
}

/** An open DSP session (one lfrife handle, resized on demand and serialised in native code). */
internal class HvxSession private constructor(private var handle: Long) : Closeable {
    fun interpolateGuided(
        lowFirst: Bitmap,
        lowSecond: Bitmap,
        highFirst: Bitmap,
        highSecond: Bitmap,
        timestep: Float,
        output: Bitmap,
    ): Int = HvxNative.nativeInterpolateGuided(
        handle, lowFirst, lowSecond, highFirst, highSecond, timestep, output,
        EngineSelection.networkExtent(lowFirst.width), EngineSelection.networkExtent(lowFirst.height),
    )

    override fun close() {
        if (handle == 0L) return
        HvxNative.nativeClose(handle)
        handle = 0L
    }

    companion object {
        private const val ProbeWidth = 512
        private const val ProbeHeight = 288

        /** Null when the DSP, the skel or the weights are not usable. */
        fun open(context: Context, modelDir: File): HvxSession? {
            if (!HvxSupport.isSupportedAbi()) return null
            val handle = runCatching {
                val skelDir = HvxSupport.installSkel(context)
                val blob = HvxSupport.blobCache(context)
                blob.parentFile?.mkdirs()
                HvxSupport.deleteStaleBlobs(blob)
                HvxNative.nativeOpen(
                    skelDir.absolutePath, modelDir.absolutePath, blob.absolutePath, ProbeWidth, ProbeHeight,
                )
            }.getOrElse {
                Log.w("LightforgeRife", "HVX backend unavailable", it)
                0L
            }
            if (handle in HvxNative.OpenErrorCodes) {
                Log.i("LightforgeRife", "HVX backend not usable (code $handle)")
                return null
            }
            return HvxSession(handle)
        }
    }
}
