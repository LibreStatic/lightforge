package com.librestatic.lightforge.feature.privatealbum

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivatePortableContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightPasswordConfirmationAndRecreationNeverPersistSecret() = workflow(false, false, 1f)
    @Test fun darkLargeTextPasswordConfirmationAndRecreationNeverPersistSecret() = workflow(true, false, 1.6f)
    @Test fun dynamicPasswordConfirmationAndRecreationNeverPersistSecret() = workflow(false, true, 1f)
    private fun workflow(dark: Boolean, dynamic: Boolean, font: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build()
        val repository = PrivateAlbumRepository(context, database)
        val restoration = StateRestorationTester(compose)
        var closed = false
        try {
            restoration.setContent {
                LightforgeTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val colors = MaterialTheme.colorScheme
                    listOf(colors.onSurface to colors.surfaceContainerHigh, colors.onErrorContainer to colors.errorContainer).forEach { (fg, bg) ->
                        assertTrue((maxOf(fg.luminance(), bg.luminance()) + .05f) / (minOf(fg.luminance(), bg.luminance()) + .05f) >= 4.5f)
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                        PrivatePortableContent(repository, hasItems = true) { closed = true }
                    }
                }
            }
            compose.onNodeWithTag("private-portable-export").performScrollTo().performClick()
            compose.onNodeWithTag("private-portable-prepare").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("private-portable-password").performScrollTo().performTextInput("Owned secret password")
            compose.onNodeWithTag("private-portable-confirm-password").performScrollTo().performTextInput("Different secret password")
            compose.onNodeWithTag("private-portable-prepare").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("private-portable-confirm-password").performScrollTo().performTextReplacement("Owned secret password")
            compose.onNodeWithTag("private-portable-prepare").performScrollTo().assertIsEnabled()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("private-portable-password").assertDoesNotExist()
            compose.onNodeWithTag("private-portable-export").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("private-portable-close").performClick()
            compose.runOnIdle { assertTrue(closed) }
        } finally { database.close() }
    }
}
