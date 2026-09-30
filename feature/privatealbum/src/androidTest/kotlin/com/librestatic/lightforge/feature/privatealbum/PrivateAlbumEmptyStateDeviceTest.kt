package com.librestatic.lightforge.feature.privatealbum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateAlbumEmptyStateDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateExplainsPrivacyAndStartsImport() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var clicked = false
        compose.setContent {
            LightforgeTheme(darkTheme = false) {
                PrivateAlbumEmptyState(onAddRequest = { clicked = true })
            }
        }

        compose.onNodeWithContentDescription(resources.getString(R.string.private_empty_illustration))
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.private_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.private_choose_media))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
}
