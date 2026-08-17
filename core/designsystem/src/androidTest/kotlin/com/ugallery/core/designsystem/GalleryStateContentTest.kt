package com.ugallery.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            UGalleryTheme(darkTheme = false) {
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
}
