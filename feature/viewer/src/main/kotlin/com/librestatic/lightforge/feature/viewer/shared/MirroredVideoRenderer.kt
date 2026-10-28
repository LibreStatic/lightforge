package com.librestatic.lightforge.feature.viewer.shared

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws the frames of one video decode onto any number of output [Surface]s, each with its own
 * rotation, so the same playback can be shown on several screens (or screen halves) at once.
 *
 * The player renders into [inputSurface]. Every frame is uploaded once to an external OES texture
 * and then drawn, letterboxed, onto each output. All GL work runs on a private thread; the public
 * methods can be called from any thread. [setOutput] and [removeOutput] return only after the
 * render thread has finished with the surface, so a caller may free its surface right after.
 *
 * Playback goes through the SDR path: HDR video is not tone mapped here.
 */
internal class MirroredVideoRenderer {
    private class Output(
        val surface: Surface,
        var eglSurface: EGLSurface,
        var width: Int,
        var height: Int,
        var rotationDegrees: Int,
    )

    private val thread = HandlerThread("MirroredVideoRenderer").apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below is touched only on the render thread, apart from the volatile flags.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionLocation = 0
    private var mvpLocation = 0
    private var textureMatrixLocation = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val outputs = LinkedHashMap<Any, Output>()
    private val textureMatrix = FloatArray(16)
    private var hasFrame = false
    private var contentWidth = 1f
    private var contentHeight = 1f

    @Volatile private var released = false

    /** Surface the player renders into. Valid until [release]. */
    val inputSurface: Surface

    init {
        var created: Surface? = null
        var failure: Throwable? = null
        val ready = CountDownLatch(1)
        handler.post {
            try {
                initGl()
                val texture = SurfaceTexture(textureId)
                surfaceTexture = texture
                texture.setOnFrameAvailableListener({ drawFrame() }, handler)
                created = Surface(texture)
            } catch (error: Throwable) {
                failure = error
            }
            ready.countDown()
        }
        ready.await()
        val surface = created
        if (surface == null) {
            thread.quitSafely()
            throw IllegalStateException("Cannot start the mirrored video renderer", failure)
        }
        inputSurface = surface
    }

    /** Default size of the input buffers; a decoder sets its own, only producers like canvases need it. */
    fun setInputBufferSize(width: Int, height: Int) = post {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /**
     * Upright display size of the video (rotation metadata and pixel aspect ratio applied). Frames
     * are fitted into each output with this aspect ratio.
     */
    fun setVideoSize(width: Float, height: Float) = post {
        if (width <= 0f || height <= 0f || (width == contentWidth && height == contentHeight)) return@post
        contentWidth = width
        contentHeight = height
        if (hasFrame) drawAll()
    }

    /** Adds an output, or replaces the one with the same [key]. A paused video is redrawn onto it. */
    fun setOutput(key: Any, surface: Surface, width: Int, height: Int, rotationDegrees: Int) {
        postAndWait {
            outputs.remove(key)?.let(::destroyOutput)
            if (!surface.isValid || width <= 0 || height <= 0) return@postAndWait
            val eglSurface = EGL14.eglCreateWindowSurface(
                display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return@postAndWait
            }
            val output = Output(surface, eglSurface, width, height, rotationDegrees)
            outputs[key] = output
            if (hasFrame) draw(output)
        }
    }

    /** Removes the output [key], if present, and frees its EGL surface before returning. */
    fun removeOutput(key: Any) {
        postAndWait { outputs.remove(key)?.let(::destroyOutput) }
    }

    /** Tears everything down. Safe to call more than once and from any thread except the render thread. */
    fun release() {
        if (released) return
        released = true
        postAndWait(force = true) {
            outputs.values.forEach(::destroyOutput)
            outputs.clear()
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface.release()
            surfaceTexture?.release()
            surfaceTexture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idleSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            idleSurface = EGL14.EGL_NO_SURFACE
            program = 0
            textureId = 0
        }
        thread.quitSafely()
    }

    private fun post(block: () -> Unit) {
        if (!released) handler.post { if (!released) block() }
    }

    private fun postAndWait(force: Boolean = false, block: () -> Unit) {
        if (released && !force) return
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val accepted = handler.post {
            try {
                if (force || !released) block()
            } finally {
                done.countDown()
            }
        }
        if (accepted && !done.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Render thread did not answer within ${WAIT_TIMEOUT_MS}ms")
        }
    }

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No EGL config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        // Keeps a context current while no output exists, so frames can still be consumed.
        idleSurface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(idleSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        mvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
        textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Link failed: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Compile failed: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }

    private fun destroyOutput(output: Output) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        // Never leave a surface that is about to disappear as the current one.
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
        EGL14.eglDestroySurface(display, output.eglSurface)
        output.eglSurface = EGL14.EGL_NO_SURFACE
    }

    /** A new frame is queued: latch it into the texture and show it everywhere. */
    private fun drawFrame() {
        val texture = surfaceTexture ?: return
        if (released || display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
        try {
            texture.updateTexImage()
        } catch (error: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", error)
            return
        }
        texture.getTransformMatrix(textureMatrix)
        hasFrame = true
        drawAll()
    }

    private fun drawAll() {
        // A removed-by-the-system surface makes the swap fail; those outputs are dropped.
        val iterator = outputs.values.iterator()
        while (iterator.hasNext()) {
            val output = iterator.next()
            if (!draw(output)) {
                destroyOutput(output)
                iterator.remove()
            }
        }
    }

    /** Returns false when the output's surface is gone and the output should be discarded. */
    private fun draw(output: Output): Boolean {
        if (!EGL14.eglMakeCurrent(display, output.eglSurface, output.eglSurface, context)) return false
        EGL14.eglSwapInterval(display, 0)
        GLES20.glViewport(0, 0, output.width, output.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val mvp = MirrorLayout.quadMatrix(output.width, output.height, contentWidth, contentHeight, output.rotationDegrees)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        return EGL14.eglSwapBuffers(display, output.eglSurface)
    }

    private companion object {
        const val TAG = "MirroredVideoRenderer"
        const val WAIT_TIMEOUT_MS = 1_000L

        val QUAD: java.nio.FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec2 aPosition;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aPosition * 0.5 + 0.5, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
