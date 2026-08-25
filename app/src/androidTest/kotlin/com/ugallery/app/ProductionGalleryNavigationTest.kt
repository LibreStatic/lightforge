package com.ugallery.app

import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.designsystem.UGalleryTheme
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionGalleryNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun restoresPhotosSearchAndAlbumViewportAcrossAnimatedViewerReturns() {
        compose.mainClock.autoAdvance = false
        var surface by mutableStateOf(TestSurface.Photos)
        var returnSurface by mutableStateOf<TestSurface?>(null)
        var requests by mutableStateOf<Map<TestSurface, Viewport>>(emptyMap())
        val observed = mutableMapOf<TestSurface, Viewport>()

        fun openViewer(origin: TestSurface) {
            returnSurface = origin
            surface = TestSurface.Viewer
        }

        fun closeViewer() {
            surface = returnSurface ?: TestSurface.Photos
            returnSurface = null
        }

        fun requestViewport(origin: TestSurface, viewport: Viewport) {
            requests = requests + (origin to viewport)
        }

        compose.activity.runOnUiThread {
            compose.activity.setContent {
                UGalleryTheme(darkTheme = false) {
                    BackHandler(enabled = surface == TestSurface.Viewer, onBack = ::closeViewer)
                    val stateHolder = rememberSaveableStateHolder()
                    AnimatedSurfaceBody(
                        key = surface.motionKey(),
                        modifier = Modifier.fillMaxSize(),
                        stateHolder = stateHolder,
                        controls = {},
                        content = { activeKey ->
                            if (activeKey.route == SurfaceRoute.Viewer) {
                                Button(
                                    onClick = ::closeViewer,
                                    modifier = Modifier.testTag(VIEWER_BACK_TAG),
                                ) { Text("Viewer") }
                            } else {
                                val origin = activeKey.testSurface()
                                TestOriginSurface(
                                    origin = origin,
                                    requestedViewport = requests[origin],
                                    observed = observed,
                                    onOpenViewer = { openViewer(origin) },
                                )
                            }
                        },
                    )
                }
            }
        }

        settleTransition()

        requestViewport(TestSurface.Photos, Viewport(index = 23, offset = 17))
        compose.waitForIdle()
        compose.onNodeWithTag(openTag(TestSurface.Photos)).performClick()
        settleTransition()
        compose.onNodeWithTag(VIEWER_BACK_TAG).performClick()
        settleTransition()
        assertViewport(TestSurface.Photos, Viewport(23, 17), observed)

        compose.runOnIdle { surface = TestSurface.Search }
        settleTransition()
        requestViewport(TestSurface.Search, Viewport(index = 31, offset = 29))
        compose.waitForIdle()
        compose.onNodeWithTag(openTag(TestSurface.Search)).performClick()
        settleTransition()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settleTransition()
        assertViewport(TestSurface.Search, Viewport(31, 29), observed)

        compose.runOnIdle { surface = TestSurface.AlbumOne }
        settleTransition()
        requestViewport(TestSurface.AlbumOne, Viewport(index = 11, offset = 13))
        compose.waitForIdle()
        compose.onNodeWithTag(openTag(TestSurface.AlbumOne)).performClick()
        settleTransition()
        compose.onNodeWithTag(VIEWER_BACK_TAG).performClick()
        settleTransition()
        assertViewport(TestSurface.AlbumOne, Viewport(11, 13), observed)

        compose.runOnIdle { surface = TestSurface.AlbumTwo }
        settleTransition()
        assertViewport(TestSurface.AlbumTwo, Viewport(0, 0), observed)
        requestViewport(TestSurface.AlbumTwo, Viewport(index = 37, offset = 41))
        compose.waitForIdle()
        compose.onNodeWithTag(openTag(TestSurface.AlbumTwo)).performClick()
        settleTransition()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settleTransition()
        assertViewport(TestSurface.AlbumTwo, Viewport(37, 41), observed)

        compose.runOnIdle { surface = TestSurface.AlbumOne }
        settleTransition()
        assertViewport(TestSurface.AlbumOne, Viewport(11, 13), observed)
    }

    private fun settleTransition() {
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun assertViewport(
        surface: TestSurface,
        expected: Viewport,
        observed: Map<TestSurface, Viewport>,
    ) {
        compose.runOnIdle { assertEquals(expected, observed[surface]) }
    }

    private enum class TestSurface {
        Photos,
        Search,
        AlbumOne,
        AlbumTwo,
        Viewer,
    }

    private data class Viewport(val index: Int, val offset: Int)

    private fun TestSurface.motionKey() = when (this) {
        TestSurface.Photos -> ScreenMotionKey(SurfaceRoute.Root, RootTab.Photos, "root:photos")
        TestSurface.Search -> ScreenMotionKey(SurfaceRoute.Root, RootTab.Search, "root:search")
        TestSurface.AlbumOne -> ScreenMotionKey(SurfaceRoute.Album, RootTab.Collections, "album:virtual:1")
        TestSurface.AlbumTwo -> ScreenMotionKey(SurfaceRoute.Album, RootTab.Collections, "album:virtual:2")
        TestSurface.Viewer -> ScreenMotionKey(SurfaceRoute.Viewer, RootTab.Photos)
    }

    private fun ScreenMotionKey.testSurface() = when (saveableStateKey) {
        "root:photos" -> TestSurface.Photos
        "root:search" -> TestSurface.Search
        "album:virtual:1" -> TestSurface.AlbumOne
        "album:virtual:2" -> TestSurface.AlbumTwo
        else -> error("Unsupported test surface key: $saveableStateKey")
    }

    @Composable
    private fun TestOriginSurface(
        origin: TestSurface,
        requestedViewport: Viewport?,
        observed: MutableMap<TestSurface, Viewport>,
        onOpenViewer: () -> Unit,
    ) {
        val gridState = rememberLazyGridState()
        LaunchedEffect(requestedViewport) {
            requestedViewport?.let { gridState.scrollToItem(it.index, it.offset) }
        }
        LaunchedEffect(gridState, origin) {
            snapshotFlow { Viewport(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
                .collectLatest { observed[origin] = it }
        }
        Column(Modifier.fillMaxSize()) {
            Button(
                onClick = onOpenViewer,
                modifier = Modifier.fillMaxWidth().testTag(openTag(origin)),
            ) { Text("Open ${origin.name}") }
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                state = gridState,
                modifier = Modifier.fillMaxWidth().height(560.dp).testTag(gridTag(origin)),
            ) {
                items((0 until 100).toList(), key = { it }) { index ->
                    Box(Modifier.padding(2.dp).size(72.dp)) { Text(index.toString()) }
                }
            }
        }
    }

    private fun openTag(surface: TestSurface) = "open_${surface.name.lowercase()}"
    private fun gridTag(surface: TestSurface) = "grid_${surface.name.lowercase()}"

    private companion object {
        const val VIEWER_BACK_TAG = "viewer_back"
    }
}
