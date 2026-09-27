package com.librestatic.lightforge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryBottomNavigationDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactNavigationUsesThreeEqualDestinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected by mutableStateOf(RootTab.Photos)

        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                GalleryBottomDock(selected = selected, onSelect = { selected = it })
            }
        }

        compose.onNodeWithText(context.getString(R.string.nav_photos)).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.nav_collections)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nav_search)).assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.nav_create)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nav_ask)).assertDoesNotExist()
    }
}
