package com.librestatic.lightforge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutContentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overviewShowsProductCreditAndOpensBundledLicenses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                AboutContent(versionName = "9.8.7", onBack = {})
            }
        }

        compose.onNode(hasText(context.getString(R.string.about_title))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.about_version, "9.8.7"))).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.about_developer))).assertIsDisplayed()
        compose.onNode(hasTestTag("about_licenses")).performClick()
        compose.onNode(hasTestTag("about_license_catalog")).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.about_licenses_title))).assertIsDisplayed()
        compose.onNode(hasText(context.resources.getQuantityString(R.plurals.about_dependencies_count, 225, 225))).assertIsDisplayed()
        compose.onNode(hasTestTag("dependency_androidx.activity:activity")).performClick()
        compose.onNode(hasTestTag("dependency_license_text")).assertIsDisplayed()
        compose.onNode(hasTestTag("dependency_license_body")).assertIsDisplayed()
    }

    @Test
    fun appLicenseOpensBundledVerbatimText() {
        compose.setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                AboutContent(versionName = "9.8.7", onBack = {})
            }
        }

        compose.onNode(hasTestTag("about_app_license")).performClick()
        compose.onNode(hasTestTag("about_app_license_text")).assertIsDisplayed()
        compose.onNode(hasText("Copyright 2026 LibreStatic contributors", substring = true))
            .assertIsDisplayed()
        compose.onNode(hasText("Apache License", substring = true)).assertIsDisplayed()
    }
}
