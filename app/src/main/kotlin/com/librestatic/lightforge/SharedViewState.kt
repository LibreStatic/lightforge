package com.librestatic.lightforge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.librestatic.lightforge.core.designsystem.GalleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.preferences.AppearanceSettings
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.viewer.PhotoLoadState
import com.librestatic.lightforge.feature.viewer.VideoViewerController
import com.librestatic.lightforge.feature.viewer.shared.SharedViewMode
import com.librestatic.lightforge.feature.viewer.shared.SharedZoom

/** The viewer's shared-view wiring: menu callbacks (null when not offered) and the current mode. */
internal class SharedViewState(
    val faceToFace: Boolean,
    val onShowOnOuterDisplay: (() -> Unit)?,
    val onFaceToFace: (() -> Unit)?,
    val outerDisplayActive: Boolean,
    val onStopOuterDisplay: (() -> Unit)?,
    val onPhotoZoomChanged: ((SharedZoom) -> Unit)?,
    val onExitFaceToFace: () -> Unit,
)

@Composable
internal fun rememberSharedViewState(
    media: ViewerMedia,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    appearance: AppearanceSettings,
    trashContext: Boolean,
    adaptiveInfo: GalleryAdaptiveLayoutInfo,
): SharedViewState {
    // Deliberately not keyed by media: the mode survives swiping to another item.
    var mode by rememberSaveable { mutableStateOf(SharedViewMode.Off) }
    val presenter = rememberOuterDisplayPresenter()
    val available = presenter?.available?.collectAsState()?.value ?: false
    val active = presenter?.active?.collectAsState()?.value ?: false
    val holder = presenter?.holder
    SideEffect {
        holder?.let {
            it.media = media
            it.photoState = photoState
            it.videoController = videoController
            it.thumbnailLoader = thumbnailLoader
            it.appearance = appearance
        }
    }
    LaunchedEffect(media.viewerId) { holder?.zoom = SharedZoom() }
    // The session may end on its own (backgrounded, cover closed) and a restored mode has no session.
    LaunchedEffect(active, mode) {
        if (mode == SharedViewMode.OuterDisplay && !active) mode = SharedViewMode.Off
    }
    val offers = sharedViewOffers(trashContext, adaptiveInfo.foldInfo, adaptiveInfo.windowClass, available, active)
    return SharedViewState(
        faceToFace = mode == SharedViewMode.FaceToFace,
        onShowOnOuterDisplay = presenter?.takeIf { offers.outerDisplay }?.let { p ->
            {
                mode = SharedViewMode.OuterDisplay
                p.start()
            }
        },
        onFaceToFace = { mode = SharedViewMode.FaceToFace }.takeIf { offers.faceToFace },
        outerDisplayActive = active,
        onStopOuterDisplay = presenter?.let { it::stop },
        onPhotoZoomChanged = holder?.takeIf { active }?.let { h -> { zoom: SharedZoom -> h.zoom = zoom } },
        onExitFaceToFace = { mode = SharedViewMode.Off },
    )
}
