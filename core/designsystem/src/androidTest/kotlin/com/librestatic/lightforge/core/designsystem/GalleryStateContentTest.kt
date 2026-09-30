package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryStateContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stateHasVisibleCopyIllustrationSemanticsAndRenderablePixels() {
        compose.setContent {
            LightforgeTheme(darkTheme = false) {
                GalleryStateContent(
                    title = "Empty library",
                    body = "Media will appear here",
                    illustrationDescription = "Empty library illustration",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }
        }

        compose.onNode(hasText("Empty library")).assertIsDisplayed()
        compose.onNode(hasText("Media will appear here")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Empty library illustration")).assertIsDisplayed()
        compose.onRoot().captureToImage().also { image ->
            assertTrue(image.width > 0)
            assertTrue(image.height > 0)
        }
    }

    @Test
    fun illustrationInheritsPairedContainerContentColorInLightTheme() {
        assertIllustrationUsesPairedContainerContentColor(darkTheme = false)
    }

    @Test
    fun illustrationInheritsPairedContainerContentColorInDarkTheme() {
        assertIllustrationUsesPairedContainerContentColor(darkTheme = true)
    }

    private fun assertIllustrationUsesPairedContainerContentColor(darkTheme: Boolean) {
        var inheritedColor: Color? = null
        var expectedColor: Color? = null

        compose.setContent {
            LightforgeTheme(darkTheme = darkTheme, dynamicColor = false) {
                val pairedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                SideEffect { expectedColor = pairedContentColor }
                GalleryStateContent(
                    title = "Empty library",
                    body = "Media will appear here",
                    illustrationDescription = "Empty library illustration",
                    modifier = Modifier.fillMaxSize(),
                    illustration = {
                        val contentColor = LocalContentColor.current
                        SideEffect { inheritedColor = contentColor }
                    },
                )
            }
        }

        compose.waitForIdle()
        assertEquals(expectedColor, inheritedColor)
    }
}
