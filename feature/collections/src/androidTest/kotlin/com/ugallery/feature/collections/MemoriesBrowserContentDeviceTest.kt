package com.ugallery.feature.collections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.data.MomentRepository
import com.ugallery.core.database.*
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MemoriesBrowserContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open() =
        Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .addCallback(MomentDiscoverySchema.Callback)
            .build()

    private suspend fun seed(db: GalleryDatabase) {
        db.libraryDao()
            .upsertMedia(
                listOf(
                    MediaItemEntity(
                        "browser:fixture",
                        1,
                        1,
                        "image/jpeg",
                        "IMG.jpg",
                        1000,
                        4000,
                        3000,
                        0,
                        0,
                        1,
                        1,
                        1,
                        1,
                        1,
                        1,
                        1,
                        "Camera",
                        "DCIM/Camera/",
                        false,
                        false,
                        true,
                        1,
                    )
                )
            )
        repeat(60) { n ->
            val id = "browser-${n.toString().padStart(2,'0')}"
            db.momentDao()
                .upsertMoment(
                    MomentEntity(
                        id,
                        "MANUAL",
                        if (n % 2 == 0) "SAVED" else "SUGGESTED",
                        "fixture",
                        1,
                        1,
                        "Story $n",
                        "USER",
                        true,
                        1,
                        1,
                    )
                )
            db.momentDao()
                .insertMembers(
                    listOf(MomentMemberEntity(id, 0, "browser:fixture", 1, 1, "MANUAL", 1f))
                )
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("memories-browser-list").performScrollToNode(hasTestTag(tag))
    }

    @Test fun compactLightPagesAndFilters() = flow(false, 1f, 360)

    @Test fun compactDarkLargeTextPagesAndFilters() = flow(true, 1.6f, 360)

    @Test fun expandedDynamicPagesAndFilters() = flow(false, 1f, 840)

    @Test fun compactDarkTwoHundredPercentKeepsFiltersOpenAndBackReachable() =
        flow(true, 2f, 360, LayoutDirection.Ltr, criticalActions = true)

    @Test fun compactRtlTwoHundredPercentKeepsFiltersOpenAndBackReachable() =
        flow(false, 2f, 360, LayoutDirection.Rtl, criticalActions = true)

    private fun flow(dark: Boolean, font: Float, width: Int,
        direction: LayoutDirection? = null, criticalActions: Boolean = false) {
        val db = open()
        runBlocking { seed(db) }
        var opened: String? = null
        val ratios = mutableListOf<Double>()
        var visible by mutableStateOf(true)
        var backCalls = 0
        var observedFont = 0f
        var observedDirection: LayoutDirection? = null
        fun activate(tag: String) {
            val node = compose.onNodeWithTag(tag)
            if (criticalActions) {
                node.assertIsDisplayed().assertIsEnabled().assertHasClickAction().performTouchInput { click() }
                compose.waitForIdle()
            } else node.performClick()
        }
        try {
            compose.setContent {
                UGalleryTheme(darkTheme = dark, dynamicColor = width == 840) {
                    val c = MaterialTheme.colorScheme
                    SideEffect {
                        ratios.clear()
                        listOf(
                                c.background to c.onBackground,
                                c.surfaceVariant to c.onSurfaceVariant,
                                c.errorContainer to c.onErrorContainer,
                            )
                            .forEach { (a, b) ->
                                val x = a.luminance().toDouble()
                                val y = b.luminance().toDouble()
                                ratios.add((maxOf(x, y) + .05) / (minOf(x, y) + .05))
                            }
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, font),
                        LocalLayoutDirection provides (direction ?: LocalLayoutDirection.current),
                    ) {
                        val density = LocalDensity.current
                        val actualDirection = LocalLayoutDirection.current
                        SideEffect { observedFont = density.fontScale; observedDirection = actualDirection }
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            if (visible) MemoriesBrowserContent(
                                remember { MomentRepository(db) },
                                null,
                                { backCalls++ },
                                { opened = it.momentId },
                            )
                        }
                    }
                }
            }
            waitFor("memories-open-browser-00")
            if (criticalActions) {
                assertEquals(2f, observedFont, 0f)
                assertEquals(direction, observedDirection)
                compose.onNodeWithTag("memories-filter-all").assertIsDisplayed().assertIsSelected()
                val all = compose.onNodeWithTag("memories-filter-all").fetchSemanticsNode().boundsInRoot
                val saved = compose.onNodeWithTag("memories-filter-saved").fetchSemanticsNode().boundsInRoot
                assertEquals("The first two filters share a row", all.top, saved.top, 1f)
                assertTrue("Filter placement follows the actual layout direction",
                    if (direction == LayoutDirection.Rtl) all.center.x > saved.center.x else all.center.x < saved.center.x)
                // Exercise a real pointer scroll before the existing distant-item paging helper.
                compose.onNodeWithTag("memories-browser-list").performTouchInput { swipeUp() }
                val range = compose.onNodeWithTag("memories-browser-list").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue("The fixture list must actually scroll", range.value() > 0f)
            }
            scroll("memories-open-browser-59")
            activate("memories-open-browser-59")
            assertEquals("browser-59", opened)
            activate("memories-filter-saved")
            if (criticalActions) compose.onNodeWithTag("memories-filter-saved").assertIsSelected()
            waitFor("memories-open-browser-00")
            scroll("memories-open-browser-58")
            activate("memories-open-browser-58")
            assertEquals("browser-58", opened)
            compose.onNodeWithTag("memories-open-browser-59").assertDoesNotExist()
            activate("memories-filter-suggested")
            waitFor("memories-open-browser-01")
            scroll("memories-open-browser-59")
            compose.onNodeWithTag("memories-open-browser-58").assertDoesNotExist()
            compose.onNodeWithTag("memories-filter-suggested").assertIsSelected()
            if (criticalActions) {
                activate("memories-open-browser-59")
                assertEquals("browser-59", opened)
                activate("memories-filter-all")
                compose.onNodeWithTag("memories-filter-all").assertIsSelected()
                waitFor("memories-open-browser-00")
                scroll("memories-open-browser-59")
                scroll("memories-open-browser-00")
                activate("memories-open-browser-00")
                assertEquals("browser-00", opened)
                compose.onNodeWithContentDescription(context.getString(R.string.memories_browser_back))
                    .assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
                compose.waitForIdle()
                assertEquals("Visible Back must deliver its callback once", 1, backCalls)
            }
            assertTrue(ratios.size == 3 && ratios.all { it >= 4.5 })
            val suffix = direction?.let { "-${it.name.lowercase()}" }.orEmpty()
            val folder = File(context.filesDir, "memories-browser-evidence").apply { mkdirs() }
            File(folder, "flow-$dark-$font-$width$suffix.png").outputStream().use { out ->
                assertTrue(
                    compose
                        .onNodeWithTag("memories-browser-screen")
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                )
            }
            File(folder, "flow-$dark-$font-$width$suffix.txt")
                .writeText(
                    "60 stories; saved/suggested filters; selected=$opened; contrast=$ratios; " +
                        "fontScale=$observedFont; direction=$observedDirection; touchActions=$criticalActions; " +
                        "backCalls=$backCalls; no keyboard or TalkBack claim"
                )
        } finally {
            if (criticalActions) {
                compose.runOnIdle { visible = false }
                compose.waitForIdle()
            }
            db.close()
        }
    }

    @Test
    fun savedFilterSurvivesRestorationAndIncludesAllHiddenSavedStory() {
        val db = open()
        val restoration = StateRestorationTester(compose)
        runBlocking {
            db.momentDao()
                .upsertMoment(
                    MomentEntity(
                        "hidden-saved",
                        "MANUAL",
                        "SAVED",
                        "fixture",
                        1,
                        1,
                        "Hidden saved story",
                        "USER",
                        true,
                        1,
                        1,
                    )
                )
        }
        try {
            restoration.setContent {
                UGalleryTheme {
                    MemoriesBrowserContent(remember { MomentRepository(db) }, null, {}, {})
                }
            }
            waitFor("memories-open-hidden-saved")
            compose.onNodeWithTag("memories-filter-saved").performClick()
            waitFor("memories-open-hidden-saved")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("memories-filter-saved").assertIsSelected()
            waitFor("memories-open-hidden-saved")
            compose
                .onNodeWithText(context.getString(R.string.memories_browser_hidden))
                .assertExists()
            compose.onNodeWithTag("memories-filter-suggested").performClick()
            waitFor("memories-browser-empty")
        } finally {
            db.close()
        }
    }
}
