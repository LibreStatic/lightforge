package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.MomentEntity
import com.librestatic.lightforge.core.database.MomentMemberEntity
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MemoryPlaybackDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val moment =
        MomentEntity("live-story", "AUTO", "SAVED", "v1", 1, 2, "Local story", "USER", true, 1, 2)

    private fun photos() =
        listOf(2, 7, 11).map { ordinal ->
            MomentMemberUi(
                MomentMemberEntity(
                    moment.momentId,
                    ordinal,
                    "volume:with:colon",
                    ordinal.toLong(),
                    1,
                    "GENERATED",
                    1f,
                ),
                MediaKey("volume:with:colon", ordinal.toLong()),
            )
        }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("moment-list").performScrollToNode(hasTestTag(tag))
    }

    private fun click(tag: String) {
        scroll(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun position(n: Int, total: Int) {
        scroll("moment-position")
        compose
            .onNodeWithTag("moment-position")
            .assertTextEquals(context.getString(R.string.moment_story_position, n, total))
    }

    @Test fun compactLightPlaybackAndLiveRemoval() = workflow(false, 1f, 360)

    @Test fun compactDarkLargeTextPlaybackAndLiveRemoval() = workflow(true, 1.6f, 360)

    @Test fun expandedDynamicPlaybackAndLiveRemoval() = workflow(false, 1f, 840)

    private fun workflow(dark: Boolean, scale: Float, width: Int) {
        var members by mutableStateOf(photos())
        val initial = members
        val orders = mutableListOf<List<MediaKey>>()
        var cover: Int? = null
        var deleted = 0
        val ratios = mutableListOf<Double>()
        compose.setContent {
            LightforgeTheme(darkTheme = dark, dynamicColor = width == 840) {
                val c = MaterialTheme.colorScheme
                SideEffect {
                    ratios.clear()
                    listOf(
                            c.background to c.onBackground,
                            c.primaryContainer to c.onPrimaryContainer,
                            c.surfaceVariant to c.onSurfaceVariant,
                        )
                        .forEach { (a, b) ->
                            val x = a.luminance().toDouble()
                            val y = b.luminance().toDouble()
                            ratios.add((maxOf(x, y) + .05) / (minOf(x, y) + .05))
                        }
                }
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, scale)
                ) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        MomentContent(
                            moment,
                            members,
                            null,
                            "2026",
                            "Local",
                            {},
                            {},
                            { deleted++ },
                            {},
                            { cover = it },
                            { orders.add(it) },
                        )
                    }
                }
            }
        }
        position(1, 3)
        click("moment-next")
        position(2, 3)
        click("moment-next")
        position(3, 3)
        scroll("moment-next")
        compose.onNodeWithTag("moment-next").assertIsNotEnabled()
        assertTrue(orders.isEmpty()) // Playback must never reorder persisted members.
        click("moment-set-cover")
        assertEquals(11, cover)
        click("moment-move-earlier")
        assertEquals(listOf(initial[0].key, initial[2].key, initial[1].key), orders.single())
        compose.runOnIdle { members = listOf(initial[0], initial[2], initial[1]) }
        position(2, 3) // Same active key, now at its new position.
        compose.runOnIdle { members = listOf(initial[0], initial[1]) }
        position(1, 2) // Active source removed; fallback does not use ordinal as list index.
        compose.runOnIdle { members = emptyList() }
        scroll("moment-empty")
        compose.onNodeWithTag("moment-empty").assertIsDisplayed()
        scroll("moment-save")
        compose.onNodeWithTag("moment-save").assertIsNotEnabled()
        scroll("moment-next")
        compose.onNodeWithTag("moment-next").assertIsNotEnabled()
        compose.runOnIdle { members = initial }
        position(1, 3)
        scroll("moment-next")
        val name = "memory-$dark-$width-$scale"
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue(ratios.all { it >= 4.5 })
        File(context.filesDir, "$name-result.json")
            .writeText(
                """{"status":"PASS","playbackDoesNotReorder":true,"sparseOrdinals":true,"liveEmpty":true,"contrast":${ratios}}"""
            )
        click("moment-delete")
        assertEquals(0, deleted)
        compose.onNodeWithTag("moment-delete-confirm").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun selectedCompositeKeyAndRenameDraftSurviveSavedState() {
        val restoration = StateRestorationTester(compose)
        var title = ""
        restoration.setContent {
            LightforgeTheme {
                MomentContent(
                    moment,
                    photos(),
                    null,
                    "2026",
                    "Local",
                    {},
                    {},
                    {},
                    { title = it },
                    {},
                    {},
                )
            }
        }
        click("moment-next")
        position(2, 3)
        compose.onNodeWithTag("moment-edit").performClick()
        compose.onNodeWithTag("moment-title").performTextReplacement("Unsaved title")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("moment-title").assertTextContains("Unsaved title")
        assertEquals("", title)
        click("moment-title-save")
        assertEquals("Unsaved title", title)
        position(2, 3)
    }

    @Test
    fun routeSwitchNeverRendersPriorMomentRows() {
        var current by mutableStateOf(moment)
        compose.setContent {
            LightforgeTheme {
                MomentContent(current, photos(), null, "2026", "Local", {}, {}, {}, {}, {}, {})
            }
        }
        position(1, 3)
        compose.runOnIdle { current = moment.copy(momentId = "other-story", title = "Other story") }
        scroll("moment-empty")
        compose.onNodeWithTag("moment-empty").assertIsDisplayed()
        compose.onNodeWithTag("moment-slide").assertDoesNotExist()
    }
}
