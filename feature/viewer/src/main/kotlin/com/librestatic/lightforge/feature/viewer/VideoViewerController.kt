@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.librestatic.lightforge.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VideoViewerState {
    data object Idle : VideoViewerState
    data class Loading(val uri: Uri, val poster: Bitmap?) : VideoViewerState
    data class Ready(
        val uri: Uri,
        val poster: Bitmap?,
        val isPlaying: Boolean,
        val isMuted: Boolean,
        val isLooping: Boolean,
        val durationMillis: Long,
        val aspectRatio: Float? = null,
        val videoDecoderName: String? = null,
        val usedSoftwareDecoder: Boolean = false,
        /** True once a decoded frame reached the surface; until then a poster stands in. */
        val firstFrameRendered: Boolean = false,
        /**
         * True while frames go through the effects passed to [VideoViewerController.setVideoEffects].
         * False for viewer controllers, and for editor controllers that fell back to plain playback
         * after the effects graph failed; their UI must then preview edits some other way.
         */
        val videoEffectsActive: Boolean = false,
    ) : VideoViewerState
    data class Failure(val uri: Uri, val unsupported: Boolean, val errorCode: Int) : VideoViewerState
    data object Released : VideoViewerState
}

internal interface VideoEngine {
    interface Listener {
        fun onReady(durationMillis: Long, isPlaying: Boolean)
        fun onPlayingChanged(isPlaying: Boolean, durationMillis: Long)
        fun onVideoAspectRatioChanged(aspectRatio: Float)
        fun onFailure(errorCode: Int, unsupported: Boolean)
        fun onDecoderChanged(codecName: String, softwareOnly: Boolean) = Unit
        fun onFirstFrameRendered() = Unit
    }

    var listener: Listener?
    fun setMedia(uri: Uri)
    fun prepare()
    fun play()
    fun pause()
    fun setScrubbingModeEnabled(enabled: Boolean)
    fun seekTo(positionMillis: Long)
    fun stopAndClear()
    fun release()
    fun attachSurface(surfaceView: SurfaceView?)
    /** Renders into [textureView], which (unlike a SurfaceView) honours view transforms. */
    fun attachTextureView(textureView: TextureView?) = Unit
    fun setVolume(volume: Float)
    fun setPlaybackSpeed(speed: Float) = Unit
    fun setRepeatEnabled(enabled: Boolean)
    fun setVideoEffects(effects: List<Effect>)
    fun currentPositionMillis(): Long

    /** Presentation time of the frame most recently rendered, or null when it is not known. */
    fun lastRenderedFrameMillis(): Long? = null

    /**
     * Replaces the player with one running [pipeline], keeping the attached view, volume, speed and
     * repeat mode; media must be set and prepared again. Returns false when the engine cannot.
     */
    fun rebuild(pipeline: VideoPipeline): Boolean = false
}

/** Owns exactly one player/decoder chain for the entire viewer surface. */
class VideoViewerController internal constructor(
    private val engine: VideoEngine,
    initialLooping: Boolean = false,
    private val videoEffectsRequested: Boolean = false,
) : AutoCloseable {
    constructor(
        context: Context,
        enableVideoEffects: Boolean = false,
        initialLooping: Boolean = false,
    ) : this(
        Media3VideoEngine(context.applicationContext, VideoPipeline(videoEffects = enableVideoEffects)),
        initialLooping,
        enableVideoEffects,
    )

    private val mutableState = MutableStateFlow<VideoViewerState>(VideoViewerState.Idle)
    val state: StateFlow<VideoViewerState> = mutableState
    private var activeUri: Uri? = null
    private var activePoster: Bitmap? = null
    private var muted = false
    private var looping = initialLooping
    private var released = false
    private var playbackReady = false
    private var playbackIsPlaying = false
    private var playbackDurationMillis = 0L
    private var playbackAspectRatio: Float? = null
    private var videoDecoderName: String? = null
    private var usedSoftwareDecoder = false
    private var firstFrameRendered = false
    private var scrubbing = false
    private var pipeline = VideoPipeline(videoEffects = videoEffectsRequested)
    private var recoveries = 0
    private var videoEffects: List<Effect>? = null
    private var refreshAnchorMillis: Long? = null
    private var refreshPending = false

    init {
        engine.setRepeatEnabled(looping)
        engine.listener = object : VideoEngine.Listener {
            override fun onReady(durationMillis: Long, isPlaying: Boolean) {
                playbackReady = true
                playbackIsPlaying = isPlaying
                playbackDurationMillis = durationMillis.coerceAtLeast(0)
                updateReady(durationMillis, isPlaying)
                if (refreshPending) {
                    refreshPending = false
                    refreshVideoFrame()
                }
            }
            override fun onPlayingChanged(isPlaying: Boolean, durationMillis: Long) {
                playbackIsPlaying = isPlaying
                playbackDurationMillis = durationMillis.coerceAtLeast(0)
                updateReady(durationMillis, isPlaying)
            }
            override fun onVideoAspectRatioChanged(aspectRatio: Float) {
                playbackAspectRatio = aspectRatio.takeIf { it.isFinite() && it > 0f }
                if (playbackReady) updateReady(playbackDurationMillis, playbackIsPlaying)
            }
            override fun onFailure(errorCode: Int, unsupported: Boolean) {
                val uri = activeUri ?: return
                if (recover(uri, errorCode)) return
                mutableState.value = VideoViewerState.Failure(uri, unsupported, errorCode)
            }
            override fun onFirstFrameRendered() {
                if (firstFrameRendered) return
                firstFrameRendered = true
                if (playbackReady) updateReady(playbackDurationMillis, playbackIsPlaying)
            }
            override fun onDecoderChanged(codecName: String, softwareOnly: Boolean) {
                videoDecoderName = codecName
                usedSoftwareDecoder = softwareOnly
                if (playbackReady) updateReady(playbackDurationMillis, playbackIsPlaying)
            }
        }
    }

    @MainThread
    fun select(
        uri: Uri,
        poster: Bitmap? = null,
        autoplay: Boolean = false,
        startMuted: Boolean = false,
    ) {
        check(!released)
        endScrubbing()
        engine.stopAndClear()
        activeUri = uri
        activePoster = poster
        muted = startMuted
        playbackReady = false
        refreshPending = false
        playbackIsPlaying = false
        playbackDurationMillis = 0
        playbackAspectRatio = null
        videoDecoderName = null
        usedSoftwareDecoder = false
        firstFrameRendered = false
        recoveries = 0
        mutableState.value = VideoViewerState.Loading(uri, poster)
        engine.setVolume(if (muted) 0f else 1f)
        engine.setMedia(uri)
        engine.prepare()
        if (autoplay) engine.play()
    }

    /**
     * Retries [uri] on a more conservative pipeline after [errorCode], resuming where playback
     * stopped. Returns false when there is nothing left to try and the failure should be shown.
     */
    private fun recover(uri: Uri, errorCode: Int): Boolean {
        if (released || recoveries >= VideoPlaybackRecovery.MaxRecoveries) return false
        val next = VideoPlaybackRecovery.next(pipeline, errorCode) ?: return false
        val position = engine.currentPositionMillis()
        val wasPlaying = playbackIsPlaying
        if (!engine.rebuild(next)) return false
        recoveries++
        pipeline = next
        playbackReady = false
        playbackIsPlaying = false
        videoDecoderName = null
        usedSoftwareDecoder = false
        firstFrameRendered = false
        mutableState.value = VideoViewerState.Loading(uri, activePoster)
        engine.setRepeatEnabled(looping)
        if (next.videoEffects) videoEffects?.let(engine::setVideoEffects)
        engine.setMedia(uri)
        engine.prepare()
        engine.seekTo(position)
        if (wasPlaying) engine.play()
        return true
    }

    @MainThread fun play() { check(!released); engine.play() }
    @MainThread fun pause() { if (!released) engine.pause() }
    @MainThread
    fun beginScrubbing() {
        check(!released)
        if (scrubbing) return
        scrubbing = true
        engine.setScrubbingModeEnabled(true)
    }
    @MainThread
    fun endScrubbing() {
        if (released || !scrubbing) return
        scrubbing = false
        engine.setScrubbingModeEnabled(false)
    }
    @MainThread
    fun setLooping(enabled: Boolean) {
        check(!released)
        if (looping == enabled) return
        looping = enabled
        engine.setRepeatEnabled(enabled)
        val ready = mutableState.value as? VideoViewerState.Ready ?: return
        mutableState.value = ready.copy(isLooping = enabled)
    }
    @MainThread fun currentPositionMillis(): Long = if (released) 0 else engine.currentPositionMillis()
    @MainThread
    fun mute() {
        if (released || muted) return
        muted = true
        engine.setVolume(0f)
        val ready = mutableState.value as? VideoViewerState.Ready ?: return
        mutableState.value = ready.copy(isMuted = true)
    }
    @MainThread
    fun unmute() {
        if (released || !muted) return
        muted = false
        engine.setVolume(1f)
        val ready = mutableState.value as? VideoViewerState.Ready ?: return
        mutableState.value = ready.copy(isMuted = false)
    }
    @MainThread fun seekTo(positionMillis: Long) { check(!released); engine.seekTo(positionMillis.coerceAtLeast(0)) }
    @MainThread fun setVolume(volume: Float) { check(!released); engine.setVolume(volume.coerceIn(0f, 1f)) }
    @MainThread fun setPlaybackSpeed(speed: Float) {
        check(!released)
        engine.setPlaybackSpeed(speed.coerceIn(0.125f, 4f))
    }
    @MainThread
    fun seekBy(deltaMillis: Long) {
        check(!released)
        val maximum = playbackDurationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE
        engine.seekTo((engine.currentPositionMillis() + deltaMillis).coerceIn(0L, maximum))
    }
    @MainThread
    fun onBackground() {
        endScrubbing()
        pause()
    }
    @MainThread
    fun attachSurface(surfaceView: SurfaceView?) {
        if (released) {
            check(surfaceView == null)
            return
        }
        engine.attachSurface(surfaceView)
    }
    @MainThread
    fun attachTextureView(textureView: TextureView?) {
        if (released) {
            check(textureView == null)
            return
        }
        engine.attachTextureView(textureView)
    }
    @MainThread
    fun setVideoEffects(effects: List<Effect>) {
        check(!released)
        videoEffects = effects
        // A player that fell back to plain playback has no effects graph to update.
        if (videoEffectsRequested && !pipeline.videoEffects) return
        engine.setVideoEffects(effects)
        refreshVideoFrame()
    }
    @MainThread
    fun refreshVideoFrame() {
        if (released || playbackIsPlaying) return
        if (!playbackReady) {
            // The first frame can be drawn before the player reports ready, with whatever
            // effects were current then. Redraw it once ready so a late effect change shows.
            refreshPending = true
            return
        }
        // ExoPlayer ignores a seek to the current position, so a paused frame is re-rendered by
        // seeking to an adjacent millisecond. Stepping back from wherever playback stands made
        // the position drift a millisecond per edit and the picture jump a frame whenever it
        // crossed a frame boundary. Instead, alternate between the displayed frame's own time and
        // the millisecond before it: an exact seek shows the first frame at or after the target,
        // which is that same frame for both positions.
        val current = engine.currentPositionMillis()
        if (playbackDurationMillis <= 1) {
            engine.seekTo(current)
            return
        }
        val frame = engine.lastRenderedFrameMillis()
            ?.takeIf { kotlin.math.abs(it - current) <= FrameSnapToleranceMillis }
        val previousAnchor = refreshAnchorMillis
        val anchor = frame ?: previousAnchor?.takeIf { current == it || current == it - 1 } ?: current
        refreshAnchorMillis = anchor
        val refreshPosition = when {
            anchor <= 0 -> if (current == 0L) 1 else 0
            current == anchor -> anchor - 1
            else -> anchor
        }
        engine.seekTo(refreshPosition)
    }

    @MainThread
    override fun close() {
        if (released) return
        endScrubbing()
        released = true
        activeUri = null
        activePoster = null
        playbackReady = false
        refreshPending = false
        playbackIsPlaying = false
        playbackDurationMillis = 0
        playbackAspectRatio = null
        videoDecoderName = null
        usedSoftwareDecoder = false
        engine.listener = null
        engine.stopAndClear()
        engine.release()
        mutableState.value = VideoViewerState.Released
    }

    private fun updateReady(durationMillis: Long, isPlaying: Boolean) {
        val uri = activeUri ?: return
        mutableState.value = VideoViewerState.Ready(
            uri,
            activePoster,
            isPlaying,
            muted,
            looping,
            durationMillis.coerceAtLeast(0),
            playbackAspectRatio,
            videoDecoderName,
            usedSoftwareDecoder,
            firstFrameRendered,
            videoEffectsActive = videoEffectsRequested && pipeline.videoEffects,
        )
    }
}

/** A rendered-frame time further than this from the playback position is treated as stale. */
private const val FrameSnapToleranceMillis = 250L

private class Media3VideoEngine(
    private val context: Context,
    private var pipeline: VideoPipeline,
) : VideoEngine {
    private val decoderSelector = MediaCodecSelector { mimeType, secure, tunneling ->
        val hardwareFirst = compareByDescending<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            it.hardwareAccelerated
        }.thenBy { it.softwareOnly }
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            .sortedWith(
                (if (pipeline.preferSoftwareDecoder) hardwareFirst.reversed() else hardwareFirst)
                    .thenBy { it.name },
            )
    }
    override var listener: VideoEngine.Listener? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var volume = 1f
    private var speed = 1f
    private var repeat = false
    @Volatile private var lastRenderedFrameUs = C.TIME_UNSET
    private var player = createPlayer()

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setMediaCodecSelector(decoderSelector)
            .setEnableDecoderFallback(true),
    ).build().also(::configure)

    override fun rebuild(pipeline: VideoPipeline): Boolean {
        val previous = player
        previous.stop()
        previous.clearVideoSurface()
        previous.release()
        this.pipeline = pipeline
        player = createPlayer()
        player.volume = volume
        player.setPlaybackSpeed(speed)
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        textureView?.let(player::setVideoTextureView) ?: surfaceView?.let(player::setVideoSurfaceView)
        return true
    }

    private fun configure(player: ExoPlayer) {
        // Media3 only creates its GL video graph when effects are registered before the
        // renderer is enabled. Register an empty chain up front so later editor changes
        // can be applied live without recreating playback.
        if (pipeline.videoEffects) player.setVideoEffects(emptyList())
        lastRenderedFrameUs = C.TIME_UNSET
        // Called on the playback thread as each frame is released for display.
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (this.player === player) lastRenderedFrameUs = presentationTimeUs
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    listener?.onReady(player.duration.coerceAtLeast(0), player.isPlaying)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying, player.duration.coerceAtLeast(0))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio
                val height = videoSize.height.toFloat()
                val aspectRatio = width / height
                if (aspectRatio.isFinite() && aspectRatio > 0f) {
                    listener?.onVideoAspectRatioChanged(aspectRatio)
                }
            }

            override fun onRenderedFirstFrame() {
                listener?.onFirstFrameRendered()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Posted: recovery may release this very player, which must not happen while it
                // is still dispatching its own listener callbacks.
                val unsupported = error.errorCode in UnsupportedErrorCodes
                mainHandler.post {
                    if (this@Media3VideoEngine.player === player) listener?.onFailure(error.errorCode, unsupported)
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val softwareOnly = runCatching {
                    decoderSelector.getDecoderInfos(
                        player.videoFormat?.sampleMimeType ?: return,
                        false,
                        false,
                    ).firstOrNull { it.name == decoderName }?.softwareOnly
                }.getOrNull() ?: decoderName.lowercase().let {
                    it.startsWith("omx.google.") || it.startsWith("c2.android.") || it.contains("software")
                }
                listener?.onDecoderChanged(decoderName, softwareOnly)
            }
        })
    }

    override fun setMedia(uri: Uri) {
        lastRenderedFrameUs = C.TIME_UNSET
        player.setMediaItem(MediaItem.fromUri(uri))
    }
    override fun prepare() = player.prepare()
    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
    }
    override fun pause() = player.pause()
    override fun setScrubbingModeEnabled(enabled: Boolean) = player.setScrubbingModeEnabled(enabled)
    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis)
    override fun stopAndClear() { player.stop(); player.clearMediaItems() }
    override fun release() = player.release()
    override fun attachSurface(surfaceView: SurfaceView?) {
        player.clearVideoSurface()
        this.surfaceView = surfaceView
        if (surfaceView != null) {
            textureView = null
            player.setVideoSurfaceView(surfaceView)
        } else {
            textureView?.let(player::setVideoTextureView)
        }
    }
    override fun attachTextureView(textureView: TextureView?) {
        player.clearVideoSurface()
        this.textureView = textureView
        if (textureView != null) {
            surfaceView = null
            player.setVideoTextureView(textureView)
        } else {
            surfaceView?.let(player::setVideoSurfaceView)
        }
    }
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        player.volume = this.volume
    }
    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        player.setPlaybackSpeed(speed)
    }
    override fun setRepeatEnabled(enabled: Boolean) {
        repeat = enabled
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    override fun setVideoEffects(effects: List<Effect>) {
        if (pipeline.videoEffects) player.setVideoEffects(effects)
    }
    override fun currentPositionMillis(): Long = player.currentPosition.coerceAtLeast(0)
    override fun lastRenderedFrameMillis(): Long? =
        lastRenderedFrameUs.takeIf { it != C.TIME_UNSET && it >= 0 }?.let { it / 1_000 }

    private companion object {
        val UnsupportedErrorCodes = setOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
