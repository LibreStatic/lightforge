package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reducedMotionShowsOverlayWithoutWaitingForSpatialTransition() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LightforgeTheme(darkTheme = false) {
                GalleryAnimatedVisibility(
                    visible = true,
                    edge = GalleryMotionEdge.End,
                    reducedMotion = true,
                ) {
                    Box(Modifier.fillMaxSize().testTag("motion_overlay"))
                }
            }
        }

        compose.onNodeWithTag("motion_overlay").assertIsDisplayed()
    }

    @Test
    fun contentTransitionSettlesOnTheNewSurface() {
        var surface by mutableStateOf("Photos")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LightforgeTheme(darkTheme = false) {
                GalleryAnimatedContent(
                    targetState = surface,
                    reducedMotion = false,
                ) { target ->
                    Text(target)
                }
            }
        }

        compose.runOnIdle { surface = "Viewer" }
        compose.mainClock.advanceTimeBy(TEST_TRANSITION_SETTLE_MILLIS)
        compose.waitForIdle()
        compose.onNode(hasText("Viewer")).assertIsDisplayed()
    }

    private companion object {
        const val TEST_TRANSITION_SETTLE_MILLIS = 2_000L
    }
}
