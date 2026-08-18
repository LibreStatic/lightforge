package com.ugallery.feature.viewer

import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia

@Composable
fun ViewerContent(
    media: TimelineMedia,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onShareSanitized: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (media.kind == MediaKind.Video) {
                VideoSurface(videoController)
            } else {
                PhotoSurface(photoState)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (media.kind == MediaKind.Video && videoController != null) {
                val videoState by videoController.state.collectAsState()
                val playing = (videoState as? VideoViewerState.Ready)?.isPlaying == true
                Button(onClick = { if (playing) videoController.pause() else videoController.play() }) {
                    Text(stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play))
                }
            }
            Button(onClick = onToggleFavorite) {
                Text(stringResource(if (isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite))
            }
            Button(onClick = onShare) { Text(stringResource(R.string.viewer_share)) }
            Button(onClick = onShareSanitized) { Text(stringResource(R.string.viewer_share_private)) }
            Button(onClick = onDetails) { Text(stringResource(R.string.viewer_details)) }
            Button(onClick = onEdit) { Text(stringResource(R.string.viewer_edit)) }
            Button(onClick = onTrash) { Text(stringResource(R.string.viewer_trash)) }
        }
    }
}

@Composable
private fun PhotoSurface(state: PhotoLoadState?) {
    when (state) {
        is PhotoLoadState.Ready -> {
            var scale by remember(state.drawable) { mutableFloatStateOf(1f) }
            val description = stringResource(R.string.viewer_photo_description)
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(state.drawable) },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale)
                    .pointerInput(state.drawable) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f) }
                    }.semantics { contentDescription = description },
            )
            DisposableEffect(state.drawable) {
                (state.drawable as? AnimatedImageDrawable)?.start()
                onDispose { (state.drawable as? AnimatedImageDrawable)?.stop() }
            }
        }
        is PhotoLoadState.Error -> Text(
            stringResource(R.string.viewer_unsupported),
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.error,
        )
        else -> CircularProgressIndicator(Modifier.padding(24.dp))
    }
}

@Composable
private fun VideoSurface(controller: VideoViewerController?) {
    if (controller == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val description = stringResource(R.string.viewer_video_description)
    AndroidView(
        factory = { context -> android.view.SurfaceView(context).also(controller::attachSurface) },
        modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
    )
    DisposableEffect(controller) { onDispose { controller.attachSurface(null) } }
}
