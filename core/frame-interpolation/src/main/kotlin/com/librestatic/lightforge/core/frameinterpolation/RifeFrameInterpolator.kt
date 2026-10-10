package com.librestatic.lightforge.core.frameinterpolation

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FrameInterpolationBackend { Vulkan, Hvx, Cpu }

data class FrameInterpolationCapability(
    val backend: FrameInterpolationBackend,
    val modelVersion: String = RifeFrameInterpolator.ModelVersion,
) {
    /** Whether [RifeFrameInterpolator.interpolateGuided] works: the DSP and the GPU can, the CPU cannot. */
    val supportsGuided: Boolean get() = backend != FrameInterpolationBackend.Cpu
}

/**
 * RIFE v4.6 interpolator. Calls are serialized by [interpolate]; guided calls may overlap.
 *
 * [engine] picks the accelerator (see [FrameInterpolationEngine]); [preferVulkan] = false forces the CPU.
 * Only one backend stays loaded. If the DSP fails at runtime the instance switches to the GPU (or CPU) and
 * repeats the call, so [capability] can change from [FrameInterpolationBackend.Hvx] during its lifetime.
 */
class RifeFrameInterpolator(
    context: Context,
    preferVulkan: Boolean = true,
    engine: FrameInterpolationEngine = FrameInterpolationEngine.Automatic,
) : Closeable {
    private val appContext = context.applicationContext
    private val modelDir: File = extractModel(appContext)
    private val lifecycle = ReentrantReadWriteLock()

    // Guarded by [lifecycle]: written under the write lock, read under the read lock (or in init).
    private var handle = 0L
    private var hvx: HvxSession? = null
    private var closed = false

    @Volatile
    var capability: FrameInterpolationCapability
        private set

    init {
        val selected = selectBackend(preferVulkan, engine)
        handle = selected.ncnn
        hvx = selected.hvx
        capability = FrameInterpolationCapability(selected.backend)
    }

    @Synchronized
    fun interpolate(first: Bitmap, second: Bitmap, timestep: Float): Bitmap {
        require(first.width == second.width && first.height == second.height)
        require(timestep > 0f && timestep < 1f)
        val onDsp = lifecycle.readLock().withLock {
            check(!closed) { "Interpolator is closed" }
            hvx != null
        }
        if (onDsp) return interpolateThroughGuided(first, second, timestep)
        return lifecycle.readLock().withLock {
            check(!closed) { "Interpolator is closed" }
            val a = first.asArgb8888()
            val b = second.asArgb8888()
            val output = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
            val status = nativeInterpolate(handle, a, b, timestep, output)
            if (a !== first) a.recycle()
            if (b !== second) b.recycle()
            check(status == 0) { "RIFE interpolation failed ($status)" }
            output
        }
    }

    /** The DSP only produces flow, so a plain call runs the network on downscaled copies and composes at full size. */
    private fun interpolateThroughGuided(first: Bitmap, second: Bitmap, timestep: Float): Bitmap {
        val (lowWidth, lowHeight) = EngineSelection.lowResolution(first.width, first.height)
        val lowA = Bitmap.createScaledBitmap(first, lowWidth, lowHeight, true)
        val lowB = Bitmap.createScaledBitmap(second, lowWidth, lowHeight, true)
        try {
            return interpolateGuided(lowA, lowB, first, second, timestep)
        } finally {
            if (lowA !== first) lowA.recycle()
            if (lowB !== second) lowB.recycle()
        }
    }

    /**
     * Runs RIFE on [lowFirst]/[lowSecond] but composes the intermediate frame at the
     * resolution of [highFirst]/[highSecond] using the network's flow and mask.
     * Requires [FrameInterpolationCapability.supportsGuided] (the GPU or the DSP backend).
     */
    fun interpolateGuided(
        lowFirst: Bitmap,
        lowSecond: Bitmap,
        highFirst: Bitmap,
        highSecond: Bitmap,
        timestep: Float,
    ): Bitmap {
        require(lowFirst.width == lowSecond.width && lowFirst.height == lowSecond.height)
        require(highFirst.width == highSecond.width && highFirst.height == highSecond.height)
        require(timestep > 0f && timestep < 1f)
        while (true) {
            // Shared lock: several timesteps of one pair may run at once so the CPU compose of one frame
            // overlaps the network pass of the next. [close] and the DSP fallback take the exclusive lock.
            val result = lifecycle.readLock().withLock {
                guidedOnce(lowFirst, lowSecond, highFirst, highSecond, timestep)
            }
            if (result != null) return result
            fallBackFromHvx()
            if (!capability.supportsGuided) return interpolate(highFirst, highSecond, timestep)
        }
    }

    /** Null when the DSP call failed and the caller must fall back and retry. */
    private fun guidedOnce(
        lowFirst: Bitmap,
        lowSecond: Bitmap,
        highFirst: Bitmap,
        highSecond: Bitmap,
        timestep: Float,
    ): Bitmap? {
        check(!closed) { "Interpolator is closed" }
        val session = hvx
        check(session != null || capability.supportsGuided) { "Guided interpolation requires Vulkan or the DSP" }
        val lowA = lowFirst.asArgb8888()
        val lowB = lowSecond.asArgb8888()
        val highA = highFirst.asArgb8888()
        val highB = highSecond.asArgb8888()
        val output = Bitmap.createBitmap(highA.width, highA.height, Bitmap.Config.ARGB_8888)
        val status = if (session != null) {
            session.interpolateGuided(lowA, lowB, highA, highB, timestep, output)
        } else {
            nativeInterpolateGuided(handle, lowA, lowB, highA, highB, timestep, output)
        }
        if (lowA !== lowFirst) lowA.recycle()
        if (lowB !== lowSecond) lowB.recycle()
        if (highA !== highFirst) highA.recycle()
        if (highB !== highSecond) highB.recycle()
        if (status != 0) output.recycle()
        if (session != null && status <= HvxNative.DspFailure) return null
        check(status == 0) { "RIFE guided interpolation failed ($status)" }
        return output
    }

    /** Drops the DSP after a failed call and loads the GPU (or CPU) backend in its place. */
    private fun fallBackFromHvx() = lifecycle.writeLock().withLock {
        if (closed || hvx == null) return@withLock
        Log.w(Tag, "The DSP backend failed; switching to the GPU")
        hvx?.close()
        hvx = null
        var created = nativeCreate(modelDir.absolutePath, true)
        if (created == 0L) created = nativeCreate(modelDir.absolutePath, false)
        check(created != 0L) { "The bundled slow-motion model could not be initialized" }
        handle = created
        capability = FrameInterpolationCapability(
            if (nativeUsesVulkan(created)) FrameInterpolationBackend.Vulkan else FrameInterpolationBackend.Cpu,
        )
    }

    private class Selected(val backend: FrameInterpolationBackend, val ncnn: Long, val hvx: HvxSession?)

    /** Opens what the preference and the device allow and leaves exactly one backend loaded. */
    private fun selectBackend(
        preferVulkan: Boolean,
        engine: FrameInterpolationEngine,
    ): Selected {
        val cache = HvxSupport.cache(appContext)
        val dspKey = EngineCacheKeys.dsp(Build.FINGERPRINT)
        val dspMayExist = preferVulkan && engine != FrameInterpolationEngine.Gpu && cache.get(dspKey) != "false"
        val session = if (dspMayExist) HvxSession.open(appContext, modelDir) else null
        // An open DSP always wins, so the GPU instance is only needed when the DSP is missing; a runtime DSP
        // failure creates it in [fallBackFromHvx].
        val ncnn = if (session == null) nativeCreate(modelDir.absolutePath, preferVulkan) else 0L
        if (session != null) cache.put(dspKey, "true")
        check(ncnn != 0L || session != null) { "The bundled slow-motion model could not be initialized" }
        val vulkan = ncnn != 0L && nativeUsesVulkan(ncnn)
        var choice = EngineSelection.choose(engine, preferVulkan, session != null, vulkan)
        if (ncnn == 0L) choice = BackendChoice.UseHvx
        if (choice == BackendChoice.UseHvx) {
            if (ncnn != 0L) nativeClose(ncnn)
            return Selected(FrameInterpolationBackend.Hvx, 0L, session)
        }
        session?.close()
        val backend = if (choice == BackendChoice.UseVulkan && vulkan) {
            FrameInterpolationBackend.Vulkan
        } else {
            FrameInterpolationBackend.Cpu
        }
        return Selected(backend, ncnn, null)
    }

    suspend fun interpolateAsync(first: Bitmap, second: Bitmap, timestep: Float): Bitmap =
        withContext(Dispatchers.Default) { interpolate(first, second, timestep) }

    @Synchronized
    override fun close() = lifecycle.writeLock().withLock {
        if (closed) return@withLock
        closed = true
        hvx?.close()
        hvx = null
        if (handle != 0L) nativeClose(handle)
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
    private external fun nativeInterpolateGuided(
        handle: Long,
        lowFirst: Bitmap,
        lowSecond: Bitmap,
        highFirst: Bitmap,
        highSecond: Bitmap,
        timestep: Float,
        output: Bitmap,
    ): Int
    private external fun nativeClose(handle: Long)

    companion object {
        const val ModelVersion = "rife-ncnn-v4.6-a7532fc3"
        private const val Tag = "LightforgeRife"
        private const val AssetDirectory = "models/rife-v4.6"

        init {
            System.loadLibrary("ncnn")
            System.loadLibrary("lightforge_frame_interpolation")
        }

        @Synchronized
        internal fun extractModel(context: Context): File {
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
