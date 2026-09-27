package com.librestatic.lightforge.feature.photos

import android.app.UiAutomation
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Component acceptance only: never starts indexing or reads a real media library. */
@RunWith(AndroidJUnit4::class)
class LibraryIndexStatusDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun compactLargeRtlLightStatusesRemainVisibleAndPolite() = exercise(false)
    @Test fun compactLargeRtlDarkStatusesRemainVisibleAndPolite() = exercise(true)

    @Test fun compactLargeRtlDynamicLightStatusesRemainVisibleAndPolite() = exercise(false, true)
    @Test fun compactLargeRtlDynamicDarkStatusesRemainVisibleAndPolite() = exercise(true, true)

    private fun exercise(dark: Boolean, dynamic: Boolean = false) {
        var contrast = 0.0
        val expected = if (dynamic) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
        var state by mutableStateOf(LibraryUiState.Ready)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                LightforgeTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        val foreground = colors.onSurface.luminance().toDouble()
                        val background = colors.surfaceContainer.luminance().toDouble()
                        contrast = (maxOf(foreground, background) + 0.05) / (minOf(foreground, background) + 0.05)
                        if (expected != null) {
                            check(colors.onSurface == expected.onSurface && colors.surfaceContainer == expected.surfaceContainer)
                        }
                    }
                    Box(Modifier.requiredSize(360.dp, 640.dp).testTag("status-viewport")) {
                        LibraryIndexStatus(state)
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.library_ready_status), useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true).assertCountEquals(0)
        val steps = listOf(
            LibraryUiState.Starting to R.string.library_loading_title,
            LibraryUiState.Indexing to R.string.library_loading_title,
            LibraryUiState.Ready to R.string.library_ready_status,
            LibraryUiState.Error to R.string.library_error_title,
            LibraryUiState.PermissionRequired to R.string.permission_title,
            LibraryUiState.Ready to R.string.library_ready_status,
        )
        for ((next, resource) in steps) {
            compose.runOnIdle { state = next }
            check(contrast >= 4.5) { "Insufficient status contrast: $contrast" }
            observe(resource, (if (dynamic) "dynamic-" else "static-") + if (dark) "dark" else "light", contrast)
        }
    }

    private fun observe(resource: Int, theme: String, contrast: Double) {
        compose.waitForIdle()
        val label = context.getString(resource)
        val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val semantics = node.fetchSemanticsNode()
        check(semantics.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite)
        check(semantics.config.getOrNull(SemanticsProperties.StateDescription) == null) { "Invented progress state" }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).assertCountEquals(0)
        val bounds = semantics.boundsInWindow
        val viewport = compose.onNodeWithTag("status-viewport").fetchSemanticsNode().boundsInWindow
        check(bounds.width > 0f && bounds.height > 0f && bounds.left >= viewport.left - 1f &&
            bounds.right <= viewport.right + 1f && bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        val layout = layouts.single()
        check(layout.layoutInput.density.fontScale == 2f && layout.layoutInput.layoutDirection == LayoutDirection.Rtl)
        check(layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == label.length)
        fun fits(left: Float, top: Float, right: Float, bottom: Float) = left >= -1f && top >= -1f &&
            right <= minOf(bounds.width, layout.size.width.toFloat()) + 1f && bottom <= minOf(bounds.height, layout.size.height.toFloat()) + 1f
        for (line in 0 until layout.lineCount) {
            check(!layout.isLineEllipsized(line))
            check(fits(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line)))
        }
        for (index in label.indices) {
            val glyph = layout.getBoundingBox(index)
            check(fits(glyph.left, glyph.top, glyph.right, glyph.bottom))
        }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline = SystemClock.uptimeMillis() + 3_000
        var matched = false
        var observations = JSONArray()
        do {
            observations = JSONArray()
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    var visited = 0
                    fun visit(node: AccessibilityNodeInfo, inherited: List<Int>, depth: Int) {
                        check(depth <= 32 && ++visited <= 2_000) { "Accessibility observer tree limit" }
                        val regions = inherited + node.liveRegion
                        if (node.packageName?.toString() == context.packageName && node.text?.toString() == label) {
                            val bounds = Rect(); node.getBoundsInScreen(bounds)
                            val polite = View.ACCESSIBILITY_LIVE_REGION_POLITE in regions
                            val state = node.stateDescription?.toString()
                            observations.put(JSONObject().put("text", node.text.toString())
                                .put("stateDescription", state ?: JSONObject.NULL).put("visible", node.isVisibleToUser)
                                .put("regions", JSONArray(regions)).put("bounds", bounds.toShortString()))
                            if (node.isVisibleToUser && !bounds.isEmpty && polite && state == null) matched = true
                        }
                        for (index in 0 until node.childCount) {
                            val child = node.getChild(index) ?: continue
                            try { visit(child, regions, depth + 1) }
                            finally { @Suppress("DEPRECATION") child.recycle() }
                        }
                    }
                    visit(root, emptyList(), 0)
                } finally { @Suppress("DEPRECATION") root.recycle() }
            }
            if (!matched) { compose.waitForIdle(); SystemClock.sleep(50) }
        } while (!matched && SystemClock.uptimeMillis() < deadline)
        println("LIBRARY_INDEX_ACCESSIBILITY " + JSONObject().put("phase", label)
            .put("theme", theme).put("contrast", contrast).put("fontScale", 2).put("layoutDirection", "RTL")
            .put("androidMatches", observations).put("matched", matched))
        check(matched) { "No visible polite Android phase with expected state $label: $observations" }
    }
}
