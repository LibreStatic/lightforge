package com.librestatic.lightforge.feature.viewer.shared

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.viewer.MediaThumbnail
import com.librestatic.lightforge.feature.viewer.PhotoLoadState
import com.librestatic.lightforge.feature.viewer.VideoViewerController
import com.librestatic.lightforge.feature.viewer.VideoViewerState
import com.librestatic.lightforge.feature.viewer.detectViewerTransformGestures

/**
 * One media item fitted into a pane, turned clockwise by [rotationDegrees] and zoomed by [zoom].
 *
 * A photo draws in its own [ImageView] over its own copy of the drawable, so it never competes
 * with the main viewer for the drawable's callback; [ownPhoto] stands in when the drawable cannot
 * be copied (animated images), and without it the thumbnail is shown. There are no deep-zoom tiles.
 * Pass [onZoomChange] to let pinch and pan inside this pane report a new shared zoom.
 *
 * A video draws on a [SurfaceView] that Compose cannot turn, so it is sized to the pane and its
 * surface is handed to [videoController] as a mirror output together with [rotationDegrees]; the
 * renderer applies the turn and the fit. It ignores [zoom].
 */
@Composable
fun SharedMediaPane(
    media: ViewerMedia,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    zoom: SharedZoom,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
    ownPhoto: PhotoLoadState.Ready? = null,
    onZoomChange: ((SharedZoom) -> Unit)? = null,
) {
    if (media.kind == MediaKind.Video) {
        if (videoController != null) {
            MirroredVideoSurface(videoController, rotationDegrees, modifier)
        } else Box(modifier)
    } else {
        RotatedPane(rotationDegrees, modifier) {
            SharedPhoto(media, photoState, ownPhoto, thumbnailLoader, zoom, onZoomChange)
        }
    }
}

/** The thumbnail shown over a video's pane until its first frame is rendered. */
@Composable
fun SharedVideoPoster(
    media: ViewerMedia,
    videoState: VideoViewerState?,
    thumbnailLoader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = videoState !is VideoViewerState.Failure &&
            (videoState as? VideoViewerState.Ready)?.firstFrameRendered != true,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier,
    ) {
        MediaThumbnail(
            media = media,
            thumbnailLoader = thumbnailLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            backgroundColor = Color.Black,
        )
    }
}

@Composable
private fun MirroredVideoSurface(
    controller: VideoViewerController,
    rotationDegrees: Int,
    modifier: Modifier,
) {
    val mirrorKey = remember(controller) { Any() }
    val latestRotation by rememberUpdatedState(rotationDegrees)
    // The view outlives the controller (a new one per item): the callback must reach the current one.
    val latestController by rememberUpdatedState(controller)
    val latestKey by rememberUpdatedState(mirrorKey)
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        latestController.attachMirrorOutput(latestKey, holder.surface, width, height, latestRotation)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        latestController.detachMirrorOutput(latestKey)
                    }
                })
            }
        },
        // A new turn re-attaches the same key, which replaces the output.
        update = { view ->
            if (view.holder.surface?.isValid == true) {
                controller.attachMirrorOutput(mirrorKey, view.holder.surface, view.width, view.height, rotationDegrees)
            }
        },
        modifier = modifier,
    )
    DisposableEffect(controller) { onDispose { controller.detachMirrorOutput(mirrorKey) } }
}

@Composable
private fun SharedPhoto(
    media: ViewerMedia,
    state: PhotoLoadState?,
    ownPhoto: PhotoLoadState.Ready?,
    thumbnailLoader: ThumbnailLoader?,
    zoom: SharedZoom,
    onZoomChange: ((SharedZoom) -> Unit)?,
) {
    val resources = LocalContext.current.resources
    val drawable: Drawable? = remember(state, ownPhoto) {
        when (state) {
            is PhotoLoadState.Thumbnail -> BitmapDrawable(resources, state.bitmap)
            is PhotoLoadState.Ready -> ownPhoto?.drawable
                ?: state.drawable.takeUnless { it is AnimatedImageDrawable }?.constantState?.newDrawable(resources)
            else -> null
        }
    }
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    if (drawable == null) {
        MediaThumbnail(
            media = media,
            thumbnailLoader = thumbnailLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            backgroundColor = Color.Black,
        )
        return
    }
    var container by remember { mutableStateOf(IntSize.Zero) }
    val containerSize = Size(container.width.toFloat(), container.height.toFloat())
    val contentWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: media.width
    val contentHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: media.height
    val fitted = fittedSize(containerSize, contentWidth.toFloat(), contentHeight.toFloat())
    val latestZoom by rememberUpdatedState(zoom)
    val latestFitted by rememberUpdatedState(fitted)
    val latestContainer by rememberUpdatedState(containerSize)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { container = it }
            // Gestures sit outside the layer so pan and centroid are in pane pixels.
            .then(
                if (onZoomChange == null) Modifier else Modifier.pointerInput(Unit) {
                    detectViewerTransformGestures(
                        isZoomed = { latestZoom.isZoomed },
                        onGesture = { centroid, pan, factor ->
                            val center = Offset(latestContainer.width / 2f, latestContainer.height / 2f)
                            latestOnZoomChange?.invoke(
                                latestZoom.afterGesture(centroid - center, pan, factor, latestContainer, latestFitted),
                            )
                        },
                    )
                },
            ),
    ) {
        AndroidView(
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { it.setImageDrawable(drawable) },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val translation = zoom.translation(containerSize, fitted)
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = translation.x
                translationY = translation.y
            },
        )
    }
}
