package com.ugallery.feature.viewer

import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import com.ugallery.core.model.MediaKind
import com.ugallery.core.designsystem.GalleryIcons
import java.text.DateFormat
import java.util.Date
import com.ugallery.core.model.TimelineMedia

@Composable
fun ViewerContent(
    media: TimelineMedia,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onShareSanitized: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    val dateLabel = remember(media.timelineSortMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.timelineSortMillis))
    }
    Box(
        modifier.fillMaxSize().background(Color.Black),
    ) {
        if (chromeVisible) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.viewer_back), tint = Color.White)
                }
                Text(dateLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(GalleryIcons.More, contentDescription = stringResource(R.string.viewer_more), tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_details)) },
                            onClick = { menuExpanded = false; onDetails() },
                            leadingIcon = { Icon(GalleryIcons.Info, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_share_private)) },
                            onClick = { menuExpanded = false; onShareSanitized() },
                            leadingIcon = { Icon(GalleryIcons.Lock, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_trash)) },
                            onClick = { menuExpanded = false; onTrash() },
                            leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null) },
                        )
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .padding(top = 64.dp, bottom = if (chromeVisible) 92.dp else 0.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { chromeVisible = !chromeVisible }) },
        ) {
            if (media.kind == MediaKind.Video) VideoSurface(videoController) else PhotoSurface(photoState)
        }
        if (chromeVisible) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerAction(onClick = onShare, icon = GalleryIcons.Share, label = stringResource(R.string.viewer_share), modifier = Modifier.weight(1f))
                ViewerAction(onClick = onEdit, icon = GalleryIcons.Edit, label = stringResource(R.string.viewer_edit), modifier = Modifier.weight(1f))
                ViewerAction(onClick = onToggleFavorite, icon = GalleryIcons.Heart, label = stringResource(if (isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite), modifier = Modifier.weight(1f))
                ViewerAction(onClick = onDetails, icon = GalleryIcons.Info, label = stringResource(R.string.viewer_details), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ViewerAction(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
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
