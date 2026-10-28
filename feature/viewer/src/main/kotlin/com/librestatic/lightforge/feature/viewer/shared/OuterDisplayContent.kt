package com.librestatic.lightforge.feature.viewer.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.viewer.PhotoLoadState
import com.librestatic.lightforge.feature.viewer.VideoViewerController

/**
 * What the outer (cover) screen shows while the presenter browses on the inner one: the current
 * media fitted full screen with the presenter's [zoom], and no chrome.
 */
@Composable
fun OuterDisplayContent(
    media: ViewerMedia,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    zoom: SharedZoom,
    modifier: Modifier = Modifier,
    ownPhoto: PhotoLoadState.Ready? = null,
) {
    // Same black media stage as the viewer.
    Box(modifier.fillMaxSize().background(Color.Black)) {
        SharedMediaPane(
            media = media,
            photoState = photoState,
            videoController = videoController,
            thumbnailLoader = thumbnailLoader,
            zoom = zoom,
            rotationDegrees = 0,
            modifier = Modifier.fillMaxSize(),
            ownPhoto = ownPhoto,
        )
        if (media.kind == MediaKind.Video && videoController != null) {
            val videoState by videoController.state.collectAsState()
            SharedVideoPoster(media, videoState, thumbnailLoader, Modifier.fillMaxSize())
        }
    }
}
