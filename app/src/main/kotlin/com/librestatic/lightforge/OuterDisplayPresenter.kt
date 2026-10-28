@file:OptIn(androidx.window.core.ExperimentalWindowApi::class)

package com.librestatic.lightforge

import android.os.Binder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.area.WindowAreaPresentationSessionCallback
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.preferences.AppearanceSettings
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.viewer.PhotoLoadState
import com.librestatic.lightforge.feature.viewer.VideoViewerController
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.feature.viewer.shared.OuterDisplayContent
import com.librestatic.lightforge.feature.viewer.shared.SharedZoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the outer screen mirrors; ViewerRoute writes it on every composition. */
class OuterDisplayHolder {
    var media by mutableStateOf<ViewerMedia?>(null)
    var photoState by mutableStateOf<PhotoLoadState?>(null)
    var videoController by mutableStateOf<VideoViewerController?>(null)
    var thumbnailLoader by mutableStateOf<ThumbnailLoader?>(null)
    var zoom by mutableStateOf(SharedZoom())
    var appearance by mutableStateOf<AppearanceSettings?>(null)
}

/**
 * A window area can host the outer display when it faces the rear and its present-on-area operation
 * is available (or already running).
 */
internal fun canPresentOnArea(
    isRearFacing: Boolean,
    status: WindowAreaCapability.Status?,
): Boolean = isRearFacing && (
    status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
        status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE
    )

/** Presents the viewer's current media on the cover screen of a foldable through WindowManager. */
class OuterDisplayPresenter(
    private val activity: ComponentActivity,
    val holder: OuterDisplayHolder,
) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    // True from start() until the session ends, so the UI never shows a gap between the two.
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var token: Binder? = null
    private var session: WindowAreaSessionPresenter? = null
    private var view: ComposeView? = null

    /** Collects the window areas while the activity is started; never throws without extensions. */
    suspend fun observe() {
        val controller = runCatching { WindowAreaController.getOrCreate() }.getOrNull() ?: return
        try {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.windowAreaInfos.collect { infos ->
                    val area = infos.firstOrNull(::isUsable)
                    token = area?.token
                    _available.value = area != null
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Window areas unavailable", t)
        } finally {
            _available.value = false
            token = null
        }
    }

    private fun isUsable(info: WindowAreaInfo): Boolean = canPresentOnArea(
        isRearFacing = info.type == WindowAreaInfo.Type.TYPE_REAR_FACING,
        status = info.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status,
    )

    fun start() {
        val target = token ?: return
        if (_active.value) return
        val controller = runCatching { WindowAreaController.getOrCreate() }.getOrNull() ?: return
        _active.value = true
        try {
            controller.presentContentOnWindowArea(
                target,
                activity,
                ContextCompat.getMainExecutor(activity),
                callback,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "presentContentOnWindowArea failed", t)
            failed()
        }
    }

    fun stop() {
        if (!_active.value) return
        _active.value = false
        val current = session
        if (current != null) runCatching { current.close() } else release()
    }

    private fun failed() {
        release()
        _active.value = false
        Toast.makeText(activity, R.string.outer_display_failed, Toast.LENGTH_SHORT).show()
    }

    private fun release() {
        view?.disposeComposition()
        view = null
        session = null
    }

    private val callback = object : WindowAreaPresentationSessionCallback {
        override fun onSessionStarted(session: WindowAreaSessionPresenter) {
            if (!_active.value) {
                // stop() was requested before the session came up.
                runCatching { session.close() }
                return
            }
            this@OuterDisplayPresenter.session = session
            val composeView = ComposeView(session.context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setViewTreeLifecycleOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setContent { OuterDisplayStage(holder) }
            }
            view = composeView
            session.setContentView(composeView as View)
        }

        override fun onSessionEnded(t: Throwable?) {
            val wasRunning = session != null
            release()
            val wasActive = _active.value
            _active.value = false
            if (t != null) Log.w(TAG, "Outer display session ended with error", t)
            if (t != null && !wasRunning && wasActive) {
                Toast.makeText(activity, R.string.outer_display_failed, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onContainerVisibilityChanged(isVisible: Boolean) = Unit
    }

    private companion object {
        const val TAG = "OuterDisplayPresenter"
    }
}

@Composable
private fun OuterDisplayStage(holder: OuterDisplayHolder) {
    val content: @Composable () -> Unit = {
        val media = holder.media
        if (media != null) {
            OuterDisplayContent(
                media = media,
                photoState = holder.photoState,
                videoController = holder.videoController,
                thumbnailLoader = holder.thumbnailLoader,
                zoom = holder.zoom,
            )
        }
    }
    val appearance = holder.appearance
    if (appearance != null) LightforgeTheme(appearance) { content() } else content()
}

/** Creates the presenter for the host activity, observes window areas while started, and ends sharing on dispose. */
@Composable
fun rememberOuterDisplayPresenter(): OuterDisplayPresenter? {
    val activity = LocalContext.current.findComponentActivity() ?: return null
    val presenter = remember(activity) { OuterDisplayPresenter(activity, OuterDisplayHolder()) }
    LaunchedEffect(presenter) { presenter.observe() }
    DisposableEffect(presenter) { onDispose { presenter.stop() } }
    return presenter
}

private tailrec fun android.content.Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
