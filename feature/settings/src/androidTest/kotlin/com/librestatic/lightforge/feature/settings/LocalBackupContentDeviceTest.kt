package com.librestatic.lightforge.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalBackupContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactLightShowsOriginalsOnlyScopeAndRequiresSelection() = exercise(false, 1f)

    @Test fun compactDarkLargeTextKeepsActionsAndBackReachable() = exercise(true, 1.6f)

    private fun exercise(dark: Boolean, scale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var back = false
        compose.setContent {
            LightforgeTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    Box(Modifier.width(360.dp)) { LocalBackupContent { back = true } }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            try {
                compose.onNodeWithTag("local-backup-select").assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        compose.onNodeWithTag("local-backup-screen").assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.local_backup_scope))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("local-backup-select").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("local-backup-create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("local-backup-open").performScrollTo().assertIsEnabled()
        compose
            .onNodeWithText(context.getString(R.string.local_backup_back))
            .performScrollTo()
            .performClick()
        assertTrue(back)
    }
}
