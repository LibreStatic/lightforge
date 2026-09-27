package com.librestatic.lightforge.feature.settings

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.preferences.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PortablePreferencesContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(tag: String) =
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun capture(name: String, extra: JSONObject = JSONObject()) {
        val directory = File(context.filesDir, "portable-preferences-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose
                .onNodeWithTag("portable-preferences-screen")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(directory, "$name.json").writeText(extra.toString(2))
    }

    @Test fun lightSelectionConfirmationAndRealAtomicSettings() = success("light", false, false, 1f)

    @Test
    fun darkLargeTextSelectionConfirmationAndRealAtomicSettings() =
        success("dark", true, false, 1.6f)

    @Test
    fun dynamicLargeTextSelectionConfirmationAndRealAtomicSettings() =
        success("dynamic", false, true, 1.3f)

    private fun success(name: String, dark: Boolean, dynamic: Boolean, scale: Float) {
        val repository = GallerySettingsRepository(context)
        val before = runBlocking { repository.settings.first() }
        val bytes =
            """{"schemaVersion":5,"thumbnails":{"gridColumns":7},"playback":{"loopVideos":true},"gestures":{"pinchZoom":false},"security":{"appLockEnabled":false},"analysis":{"fullAnalysisMinimumBatteryPercent":20},"operations":{"skipAppDeleteConfirmation":true}}"""
                .toByteArray()
        val operation = UUID.randomUUID().toString()
        var result: PortablePreferencesResult? = null
        var back = false
        var pairs = listOf<Pair<Color, Color>>()
        try {
            runBlocking {
                repository.update {
                    GallerySettings(
                        security = SecuritySettings(true, true, 15),
                        analysis = AnalysisSettings(50),
                    )
                }
            }
            val untouched = runBlocking { repository.settings.first() }
            compose.setContent {
                LightforgeTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        pairs =
                            listOf(
                                colors.onPrimaryContainer to colors.primaryContainer,
                                colors.onSecondaryContainer to colors.secondaryContainer,
                                colors.onErrorContainer to colors.errorContainer,
                            )
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, scale)
                    ) {
                        Box(Modifier.width(360.dp)) {
                            PortablePreferencesContent(
                                repository,
                                bytes,
                                operation,
                                onBack = { back = true },
                                onApplied = { result = it },
                            )
                        }
                    }
                }
            }
            waitFor("portable-preferences-select-Presentation")
            compose
                .onNodeWithTag("portable-preferences-review-apply")
                .performScrollTo()
                .assertIsNotEnabled()
            click("portable-preferences-details-Presentation")
            compose
                .onNodeWithText(context.getString(R.string.portable_preferences_field_columns))
                .performScrollTo()
                .assertIsDisplayed()
            click("portable-preferences-select-Presentation")
            click("portable-preferences-review-apply")
            compose.onNodeWithTag("portable-preferences-confirmation").assertIsDisplayed()
            // Confirming is a separate action: no writes on review or selection.
            assertEquals(untouched, runBlocking { repository.settings.first() })
            compose.onNodeWithTag("portable-preferences-confirm").performClick()
            waitFor("portable-preferences-result")
            compose
                .onNodeWithTag("portable-preferences-result")
                .performScrollTo()
                .assertIsDisplayed()
            val after = runBlocking { repository.settings.first() }
            assertEquals(7, after.thumbnails.gridColumns)
            assertEquals(
                untouched.copy(thumbnails = untouched.thumbnails.copy(gridColumns = 7)),
                after,
            )
            assertEquals(setOf(PortablePreferenceGroup.Presentation), result!!.appliedGroups)
            val ratios =
                pairs.map { (fg, bg) ->
                    val a = fg.luminance().toDouble()
                    val b = bg.luminance().toDouble()
                    (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                }
            ratios.forEach { assertTrue("Matched semantic contrast $it", it >= 4.5) }
            capture(
                name,
                JSONObject()
                    .put("status", "PASS")
                    .put("dynamicRequested", dynamic)
                    .put("dynamicSupported", Build.VERSION.SDK_INT >= 31)
                    .put("fontScale", scale)
                    .put("ratios", org.json.JSONArray(ratios))
                    .put("realDataStore", true)
                    .put("onlyPresentationApplied", true),
            )
            click("portable-preferences-back")
            assertTrue(back)
        } catch (failure: Throwable) {
            runCatching { capture("$name-failure") }
            throw failure
        } finally {
            runBlocking { repository.update { before } }
        }
    }

    @Test
    fun concurrentChangeRequiresFreshReviewAndSelectionWhileResetReplayIsVisible() {
        val repository = GallerySettingsRepository(context)
        val before = runBlocking { repository.settings.first() }
        val bytes = """{"playback":{"loopVideos":true}}""".toByteArray()
        val operation = UUID.randomUUID().toString()
        var injectConflict = true
        val port =
            object : PortablePreferencesPort {
                override suspend fun review(bytes: ByteArray, operationId: String) =
                    repository.review(bytes, operationId)

                override suspend fun apply(
                    bytes: ByteArray,
                    review: PortablePreferencesReview,
                    selectedGroups: Set<PortablePreferenceGroup>,
                ): PortablePreferencesResult {
                    if (injectConflict) {
                        injectConflict = false
                        repository.update {
                            it.copy(thumbnails = it.thumbnails.copy(gridColumns = 9))
                        }
                    }
                    return repository.apply(bytes, review, selectedGroups)
                }
            }
        var surface by mutableIntStateOf(0)
        try {
            runBlocking {
                repository.update { it.copy(playback = it.playback.copy(loopVideos = false)) }
            }
            compose.setContent {
                LightforgeTheme(darkTheme = false, dynamicColor = false) {
                    key(surface) { PortablePreferencesContent(port, bytes, operation, onBack = {}) }
                }
            }
            waitFor("portable-preferences-select-Playback")
            click("portable-preferences-select-Playback")
            click("portable-preferences-review-apply")
            compose.onNodeWithTag("portable-preferences-confirm").performClick()
            waitFor("portable-preferences-error")
            assertFalse(runBlocking { repository.settings.first().playback.loopVideos })
            click("portable-preferences-reload")
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag("portable-preferences-error")
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            waitFor("portable-preferences-select-Playback")
            compose
                .onNodeWithTag("portable-preferences-review-apply")
                .performScrollTo()
                .assertIsNotEnabled()
            click("portable-preferences-select-Playback")
            click("portable-preferences-review-apply")
            compose.onNodeWithTag("portable-preferences-confirm").performClick()
            waitFor("portable-preferences-result")
            assertTrue(runBlocking { repository.settings.first().playback.loopVideos })
            runBlocking { repository.reset() }
            compose.runOnIdle { surface++ }
            waitFor("portable-preferences-result")
            compose
                .onNodeWithText(context.getString(R.string.portable_preferences_replayed))
                .performScrollTo()
                .assertIsDisplayed()
            assertFalse(runBlocking { repository.settings.first().playback.loopVideos })
            capture(
                "conflict-reset-replay",
                JSONObject().put("status", "PASS").put("realDataStore", true),
            )
        } catch (failure: Throwable) {
            runCatching { capture("conflict-failure") }
            throw failure
        } finally {
            runBlocking { repository.update { before } }
        }
    }
}
