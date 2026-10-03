package com.librestatic.lightforge

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.album.AlbumWithSidePanel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure Compose test of the album side panel: no MediaStore or database access. */
@RunWith(AndroidJUnit4::class)
class AlbumSidePanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val trip = AlbumSummary(AlbumKey.Virtual(1), "Trip", 3, null, null, AlbumAvailability.Available)
    private val camera = AlbumSummary(AlbumKey.Virtual(2), "Camera", 14, null, null, AlbumAvailability.Available)
    private val folder = AlbumSummary(AlbumKey.Physical("external_primary", 10), "DCIM", 20, null, null, AlbumAvailability.Available)
    private val loader = ThumbnailLoader({ _, _ -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }, 1_000_000)

    private var selected by mutableStateOf(trip)
    private var open by mutableStateOf(false)
    private val openChanges = mutableListOf<Boolean>()

    private fun launch(windowClass: GalleryWindowClass = GalleryWindowClass.Compact) {
        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                val virtual = remember { flowOf(PagingData.from(listOf(trip, camera))) }.collectAsLazyPagingItems()
                val physical = remember { flowOf(PagingData.from(listOf(folder))) }.collectAsLazyPagingItems()
                AlbumWithSidePanel(
                    selectedKey = selected.key,
                    virtualAlbums = virtual,
                    physicalAlbums = physical,
                    thumbnails = loader,
                    windowClass = windowClass,
                    open = open,
                    onOpenChange = { openChanges += it; open = it },
                    onAlbumSelect = { selected = it },
                    modifier = Modifier.size(400.dp, 700.dp),
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize().testTag("album-grid")) { Text(selected.name.orEmpty()) }
                }
            }
        }
    }

    @Test fun swipeRightOpensAndSwipeLeftCloses() {
        launch()
        compose.onNodeWithTag("album-side-panel").assertDoesNotExist()
        compose.onNodeWithTag("album-grid").performTouchInput { swipeRight(startX = 20f, endX = 380f) }
        compose.waitForIdle()
        compose.onNodeWithTag("album-side-panel").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(true), openChanges) }
        compose.onNodeWithTag("album-grid").performTouchInput { swipeLeft(startX = right - 10f, endX = 10f) }
        compose.waitForIdle()
        compose.onNodeWithTag("album-side-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(true, false), openChanges) }
    }

    @Test fun tappingAnotherAlbumSwapsContentAndMarksItSelected() {
        open = true
        launch(GalleryWindowClass.Expanded)
        compose.onNodeWithTag("album-side-panel-item-${trip.key}").assertIsSelected()
        compose.onNodeWithTag("album-side-panel-item-${camera.key}").assertIsNotSelected().performClick()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithTag("album-side-panel-item-${camera.key}").assertIsSelected()
        compose.onNodeWithTag("album-side-panel-item-${trip.key}").assertIsNotSelected()
        compose.onNodeWithTag("album-side-panel").assertIsDisplayed()
    }

    @Test fun albumsAndFoldersAreNeverListedTogether() {
        open = true
        launch(GalleryWindowClass.Expanded)
        compose.onNodeWithTag("album-side-panel-item-${trip.key}").assertIsDisplayed()
        compose.onNodeWithTag("album-side-panel-item-${folder.key}").assertDoesNotExist()
        compose.runOnIdle { selected = folder }
        compose.waitForIdle()
        compose.onNodeWithTag("album-side-panel-item-${folder.key}").assertIsSelected()
        compose.onNodeWithTag("album-side-panel-item-${trip.key}").assertDoesNotExist()
    }

    @Test fun externalToggleHidesThePanelOnLargeScreens() {
        open = true
        launch(GalleryWindowClass.Expanded)
        compose.onNodeWithTag("album-side-panel").assertIsDisplayed()
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.onNodeWithTag("album-side-panel").assertDoesNotExist()
    }
}
