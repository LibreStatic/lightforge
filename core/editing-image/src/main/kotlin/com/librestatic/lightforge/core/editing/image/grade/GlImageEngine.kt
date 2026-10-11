package com.librestatic.lightforge.core.editing.image.grade

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.librestatic.lightforge.core.editing.image.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** EGL/GLES 3.0 is unavailable or the grade shader did not compile. */
internal class GlUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A GL call failed at run time (out of memory, context loss...). */
internal class GlOperationException(message: String) : RuntimeException(message)

/**
 * Owns one EGL14 context (GLES 3.0, 1x1 pbuffer) on a dedicated thread and grades bitmaps with the
 * ADR-045 shader. Everything GL happens on that thread. Programs, textures, the framebuffer and the
 * read-back buffer are reused between calls; they are released after [IdleReleaseMillis] without
 * work, on [trim] and on [release]. The next call transparently starts again, and a lost context
 * is rebuilt once per call.
 */
internal class GlImageEngine private constructor(context: Context) {
    private val lock = Any()
    private val handler: Handler = HandlerThread("photo-grade-gl").also {
        it.isDaemon = true
        it.start()
    }.let { Handler(it.looper) }
    private var pending = 0
    private val gl = GlState(context.applicationContext)

    /** Runs on the GL thread: frees the GL objects unless more work has been queued meanwhile. */
    private val releaseIfIdle = Runnable {
        synchronized(lock) {
            if (pending == 0) gl.destroy()
        }
    }

    /**
     * Grades [input] (at most [MaxTileSide] px per side) at ([tileOriginX], [tileOriginY]) of a
     * [frameWidth] x [frameHeight] frame. Returns a new premultiplied ARGB_8888 bitmap.
     */
    fun grade(
        input: Bitmap,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Bitmap = onGlThread {
        gl.withContext {
            gl.grade(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        }
    }

    /** Frees every GL object now (queued behind running work). The next call starts again. */
    fun release() {
        handler.removeCallbacks(releaseIfIdle)
        handler.post(releaseIfIdle)
    }

    /** `onTrimMemory`-friendly alias of [release]. */
    fun trim() = release()

    private fun <T> onGlThread(block: () -> T): T {
        val task = FutureTask(block)
        synchronized(lock) {
            pending++
            handler.removeCallbacks(releaseIfIdle)
        }
        handler.post {
            try {
                task.run()
            } finally {
                synchronized(lock) {
                    pending--
                    if (pending == 0) handler.postDelayed(releaseIfIdle, IdleReleaseMillis)
                }
            }
        }
        try {
            return task.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    companion object {
        const val IdleReleaseMillis = 10_000L
        const val MaxTileSide = 2_048

        @Volatile private var instance: GlImageEngine? = null

        fun get(context: Context): GlImageEngine = instance ?: synchronized(this) {
            instance ?: GlImageEngine(context).also { instance = it }
        }
    }
}

/** GL-thread-only state. */
private class GlState(private val context: Context) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var ready = false
    /** Full-float LUT/base textures when the GPU can filter them; RGBA16F/R16F otherwise (ADR-045). */
    private var floatLinear = false

    private var program = 0
    private val uniforms = HashMap<String, Int>()
    private var sourceTexture = 0
    private var legacyTexture = 0
    private var lookTexture = 0
    private var baseTexture = 0
    private var fbo = 0
    private var fboTexture = 0
    private var fboWidth = 0
    private var fboHeight = 0
    private var readback: ByteBuffer? = null
    private var uploadedLegacy: PhotoLut? = null
    private var uploadedLook: PhotoLut? = null
    private var uploadedBase: PhotoLocalBase? = null
    private val bands = FloatArray(PhotoGradeParams.BandCount * 3)

    /** Runs [block] with a current context; a lost context is rebuilt and the block retried once. */
    fun <T> withContext(block: () -> T): T {
        ensureReady()
        try {
            return block()
        } catch (failure: GlOperationException) {
            if (EGL14.eglGetError() != EGL14.EGL_CONTEXT_LOST && failure.message?.contains("CONTEXT_LOST") != true) {
                throw failure
            }
            Log.w(Tag, "GL context lost; recreating", failure)
            destroy()
            ensureReady()
            return block()
        }
    }

    private fun ensureReady() {
        if (ready) return
        try {
            initEgl()
            initGl()
            ready = true
        } catch (failure: Throwable) {
            destroy()
            throw failure as? GlUnavailableException ?: GlUnavailableException("GLES3 init failed", failure)
        }
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw GlUnavailableException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw GlUnavailableException("eglInitialize failed")
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw GlUnavailableException("No ES3 pbuffer EGL config")
        }
        eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw GlUnavailableException("eglCreateContext failed")
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) throw GlUnavailableException("eglCreatePbufferSurface failed")
        if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
            throw GlUnavailableException("eglMakeCurrent failed")
        }
    }

    private fun initGl() {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, readRaw(R.raw.photo_grade_vertex))
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, readRaw(R.raw.photo_grade_fragment))
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            throw GlUnavailableException("Grade program link failed: ${GLES30.glGetProgramInfoLog(program)}")
        }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)

        val ids = IntArray(4)
        GLES30.glGenTextures(4, ids, 0)
        sourceTexture = ids[0]
        legacyTexture = ids[1]
        lookTexture = ids[2]
        baseTexture = ids[3]
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]
        GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        floatLinear = GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_OES_texture_float_linear") == true
        check("init")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw GlUnavailableException("Grade shader compile failed: $log")
        }
        return shader
    }

    private fun readRaw(id: Int): String =
        context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    fun grade(
        input: Bitmap,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Bitmap {
        val width = input.width
        val height = input.height
        require(width <= GlImageEngine.MaxTileSide && height <= GlImageEngine.MaxTileSide)
        var upload = input
        if (input.config != Bitmap.Config.ARGB_8888 || input.isRecycled) {
            upload = input.copy(Bitmap.Config.ARGB_8888, false)
        }
        try {
            GLES30.glUseProgram(program)
            // Source: premultiplied RGBA8 bytes, texture row 0 = bitmap row 0.
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
            setParameters(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, upload, 0)

            val legacy = params.legacyLut
            val look = params.look.takeIf { params.hasLook }
            if (legacy != null) uploadLegacy(legacy)
            if (look != null) uploadLook(look)
            val hasBase = params.needsLocalBase && base != null
            if (hasBase) uploadBase(base!!)

            ensureFbo(width, height)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, width, height)

            bindUniforms(params, legacy, look, hasBase, tileOriginX, tileOriginY, frameWidth, frameHeight)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            check("draw")

            val needed = width * height * 4
            var buffer = readback
            if (buffer == null || buffer.capacity() < needed) {
                buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                readback = buffer
            }
            buffer!!.clear().limit(needed)
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            check("readPixels")
            buffer.position(0)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.copyPixelsFromBuffer(buffer)
            return output
        } finally {
            if (upload !== input) upload.recycle()
        }
    }

    private fun bindUniforms(
        params: PhotoGradeParams,
        legacy: PhotoLut?,
        look: PhotoLut?,
        hasBase: Boolean,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        GLES30.glUniform1i(loc("uSource"), 0)
        GLES30.glUniform1i(loc("uLegacyLut"), 1)
        GLES30.glUniform1i(loc("uLookLut"), 2)
        GLES30.glUniform1i(loc("uBase"), 3)
        GLES30.glUniform1f(loc("uBrightness"), params.brightness)
        GLES30.glUniform1f(loc("uContrast"), params.contrast)
        GLES30.glUniform1f(loc("uTone"), params.tone)
        GLES30.glUniform1f(loc("uWhitePoint"), params.whitePoint)
        GLES30.glUniform1f(loc("uHighlights"), params.highlights)
        GLES30.glUniform1f(loc("uShadows"), params.shadows)
        GLES30.glUniform1f(loc("uBlackPoint"), params.blackPoint)
        GLES30.glUniform1f(loc("uSaturation"), params.saturation)
        GLES30.glUniform1f(loc("uWarmth"), params.warmth)
        GLES30.glUniform1f(loc("uTint"), params.tint)
        GLES30.glUniform1f(loc("uSkinTone"), params.skinTone)
        GLES30.glUniform1f(loc("uBlueTone"), params.blueTone)
        GLES30.glUniform1f(loc("uVignette"), params.vignette)
        params.colorMix.forEachIndexed { i, band ->
            bands[i * 3] = band.hue
            bands[i * 3 + 1] = band.saturation
            bands[i * 3 + 2] = band.luminance
        }
        GLES30.glUniform3fv(loc("uBands[0]"), PhotoGradeParams.BandCount, bands, 0)
        GLES30.glUniform1f(loc("uLookIntensity"), params.lookIntensity)
        GLES30.glUniform1f(loc("uLegacySize"), (legacy?.size ?: 2).toFloat())
        GLES30.glUniform1f(loc("uLookSize"), (look?.size ?: 2).toFloat())
        GLES30.glUniform1i(loc("uHasMix"), if (params.hasColorMix) 1 else 0)
        GLES30.glUniform1i(loc("uHasLegacy"), if (legacy != null) 1 else 0)
        GLES30.glUniform1i(loc("uHasLook"), if (look != null) 1 else 0)
        GLES30.glUniform1i(loc("uNeedsBase"), if (params.needsLocalBase) 1 else 0)
        GLES30.glUniform1i(loc("uHasBase"), if (hasBase) 1 else 0)
        GLES30.glUniform2i(loc("uTileOrigin"), tileOriginX, tileOriginY)
        GLES30.glUniform2i(loc("uFrame"), frameWidth, frameHeight)
        check("uniforms")
    }

    private fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun uploadLegacy(lut: PhotoLut) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, legacyTexture)
        if (uploadedLegacy !== lut) {
            upload3d(lut)
            uploadedLegacy = lut
        }
    }

    private fun uploadLook(lut: PhotoLut) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lookTexture)
        if (uploadedLook !== lut) {
            upload3d(lut)
            uploadedLook = lut
        }
    }

    /** Uploads [lut] to the bound 3D texture as RGBA16F/32F (alpha 1) with linear filtering. */
    private fun upload3d(lut: PhotoLut) {
        val n = lut.size
        val rgba = ByteBuffer.allocateDirect(n * n * n * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until n * n * n) {
            rgba.put(lut.data[i * 3]).put(lut.data[i * 3 + 1]).put(lut.data[i * 3 + 2]).put(1f)
        }
        rgba.position(0)
        setParameters(GLES30.GL_TEXTURE_3D, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, if (floatLinear) GLES30.GL_RGBA32F else GLES30.GL_RGBA16F, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, rgba,
        )
        check("lut upload")
    }

    private fun uploadBase(base: PhotoLocalBase) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTexture)
        if (uploadedBase === base) return
        val data = ByteBuffer.allocateDirect(base.luma.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(base.luma).position(0)
        setParameters(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, if (floatLinear) GLES30.GL_R32F else GLES30.GL_R16F, base.width, base.height, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, data,
        )
        check("base upload")
        uploadedBase = base
    }

    private fun setParameters(target: Int, filter: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Keeps one RGBA8 render target at least as large as the request. */
    private fun ensureFbo(width: Int, height: Int) {
        if (fboTexture != 0 && fboWidth >= width && fboHeight >= height) return
        if (fboTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTexture), 0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        fboTexture = ids[0]
        fboWidth = maxOf(width, fboWidth)
        fboHeight = maxOf(height, fboHeight)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
        setParameters(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, fboWidth, fboHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexture, 0,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw GlOperationException("Framebuffer incomplete")
        }
        check("fbo")
    }

    private fun check(step: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            val lost = EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST
            throw GlOperationException("GL error 0x${error.toString(16)} at $step" + if (lost) " CONTEXT_LOST" else "")
        }
    }

    /** Frees every GL object and the EGL context. The display stays initialised (it is process-wide). */
    fun destroy() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (ready && eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching {
                    GLES30.glDeleteProgram(program)
                    GLES30.glDeleteTextures(5, intArrayOf(sourceTexture, legacyTexture, lookTexture, baseTexture, fboTexture), 0)
                    GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                }
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        ready = false
        program = 0
        uniforms.clear()
        sourceTexture = 0; legacyTexture = 0; lookTexture = 0; baseTexture = 0
        fbo = 0; fboTexture = 0; fboWidth = 0; fboHeight = 0
        readback = null
        uploadedLegacy = null; uploadedLook = null; uploadedBase = null
    }

    private companion object { const val Tag = "GlImageEngine" }
}
