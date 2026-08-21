package com.ugallery.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
        val durationMillis: Long,
    ) : VideoViewerState
    data class Failure(val uri: Uri, val unsupported: Boolean, val errorCode: Int) : VideoViewerState
    data object Released : VideoViewerState
}

internal interface VideoEngine {
    interface Listener {
        fun onReady(durationMillis: Long, isPlaying: Boolean)
        fun onPlayingChanged(isPlaying: Boolean, durationMillis: Long)
        fun onFailure(errorCode: Int, unsupported: Boolean)
    }

    var listener: Listener?
    fun setMedia(uri: Uri)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun stopAndClear()
    fun release()
    fun attachSurface(surfaceView: SurfaceView?)
    fun setVolume(volume: Float)
    fun setVideoEffects(effects: List<Effect>)
    fun currentPositionMillis(): Long
}

/** Owns exactly one player/decoder chain for the entire viewer surface. */
class VideoViewerController internal constructor(private val engine: VideoEngine) : AutoCloseable {
    constructor(context: Context) : this(Media3VideoEngine(context.applicationContext))

    private val mutableState = MutableStateFlow<VideoViewerState>(VideoViewerState.Idle)
    val state: StateFlow<VideoViewerState> = mutableState
    private var activeUri: Uri? = null
    private var activePoster: Bitmap? = null
    private var muted = false
    private var released = false
    private var playbackReady = false
    private var playbackIsPlaying = false

    init {
        engine.listener = object : VideoEngine.Listener {
            override fun onReady(durationMillis: Long, isPlaying: Boolean) {
                playbackReady = true
                playbackIsPlaying = isPlaying
                updateReady(durationMillis, isPlaying)
            }
            override fun onPlayingChanged(isPlaying: Boolean, durationMillis: Long) {
                playbackIsPlaying = isPlaying
                updateReady(durationMillis, isPlaying)
            }
            override fun onFailure(errorCode: Int, unsupported: Boolean) {
                val uri = activeUri ?: return
                mutableState.value = VideoViewerState.Failure(uri, unsupported, errorCode)
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
        engine.stopAndClear()
        activeUri = uri
        activePoster = poster
        muted = startMuted
        playbackReady = false
        playbackIsPlaying = false
        mutableState.value = VideoViewerState.Loading(uri, poster)
        engine.setVolume(if (muted) 0f else 1f)
        engine.setMedia(uri)
        engine.prepare()
        if (autoplay) engine.play()
    }

    @MainThread fun play() { check(!released); engine.play() }
    @MainThread fun pause() { if (!released) engine.pause() }
    @MainThread
    fun unmute() {
        if (released || !muted) return
        muted = false
        engine.setVolume(1f)
        val ready = mutableState.value as? VideoViewerState.Ready ?: return
        mutableState.value = ready.copy(isMuted = false)
    }
    @MainThread fun seekTo(positionMillis: Long) { check(!released); engine.seekTo(positionMillis.coerceAtLeast(0)) }
    @MainThread fun onBackground() = pause()
    @MainThread fun attachSurface(surfaceView: SurfaceView?) { check(!released); engine.attachSurface(surfaceView) }
    @MainThread
    fun setVideoEffects(effects: List<Effect>) {
        check(!released)
        engine.setVideoEffects(effects)
        if (playbackReady && !playbackIsPlaying) {
            engine.seekTo(engine.currentPositionMillis())
        }
    }

    @MainThread
    override fun close() {
        if (released) return
        released = true
        activeUri = null
        activePoster = null
        playbackReady = false
        playbackIsPlaying = false
        engine.listener = null
        engine.stopAndClear()
        engine.release()
        mutableState.value = VideoViewerState.Released
    }

    private fun updateReady(durationMillis: Long, isPlaying: Boolean) {
        val uri = activeUri ?: return
        mutableState.value = VideoViewerState.Ready(
            uri, activePoster, isPlaying, muted, durationMillis.coerceAtLeast(0),
        )
    }
}

private class Media3VideoEngine(context: Context) : VideoEngine {
    private val player = ExoPlayer.Builder(context).build()
    override var listener: VideoEngine.Listener? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    listener?.onReady(player.duration.coerceAtLeast(0), player.isPlaying)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying, player.duration.coerceAtLeast(0))
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onFailure(error.errorCode, error.errorCode in UnsupportedErrorCodes)
            }
        })
    }

    override fun setMedia(uri: Uri) = player.setMediaItem(MediaItem.fromUri(uri))
    override fun prepare() = player.prepare()
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis)
    override fun stopAndClear() { player.stop(); player.clearMediaItems() }
    override fun release() = player.release()
    override fun attachSurface(surfaceView: SurfaceView?) {
        player.clearVideoSurface()
        if (surfaceView != null) player.setVideoSurfaceView(surfaceView)
    }
    override fun setVolume(volume: Float) { player.volume = volume.coerceIn(0f, 1f) }
    override fun setVideoEffects(effects: List<Effect>) = player.setVideoEffects(effects)
    override fun currentPositionMillis(): Long = player.currentPosition.coerceAtLeast(0)

    private companion object {
        val UnsupportedErrorCodes = setOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
