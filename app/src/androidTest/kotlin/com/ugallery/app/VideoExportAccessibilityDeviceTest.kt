package com.ugallery.app

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.editing.video.VideoExportPhase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production card and Android accessibility bridge; no export/store/media side effects. */
@RunWith(AndroidJUnit4::class)
class VideoExportAccessibilityDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun exportPhasesExposePoliteCoarseProgressWithoutLosingExactRangeAndTouchActions() {
        var job by mutableStateOf(VideoExportJob(
            id = "accessibility-in-memory", workId = "not-enqueued", inputUri = "",
            encodedRecipe = "", displayName = "Accessibility fixture", status = VideoExportJobStatus.Queued,
            phase = VideoExportPhase.Preparing, progressPermille = 0,
            createdAtMillis = 0L, updatedAtMillis = 0L,
        ))
        var opened = 0
        var dismissed = 0
        compose.setContent {
            UGalleryTheme(darkTheme = false, dynamicColor = false) {
                VideoExportGlobalStatusCard(job, 1, { opened++ }, { dismissed++ })
            }
        }
        observe(R.string.video_export_waiting, null, null)
        val steps = listOf(
            Triple(VideoExportPhase.Preparing, R.string.video_export_preparing, 0),
            Triple(VideoExportPhase.Rendering, R.string.video_export_rendering, 101),
            Triple(VideoExportPhase.Rendering, R.string.video_export_rendering, 199),
            Triple(VideoExportPhase.Rendering, R.string.video_export_rendering, 200),
            Triple(VideoExportPhase.Publishing, R.string.video_export_publishing, 900),
            Triple(VideoExportPhase.Verifying, R.string.video_export_verifying, 990),
        )
        for ((phase, label, permille) in steps) {
            compose.runOnIdle { job = job.copy(status = VideoExportJobStatus.Running, phase = phase, progressPermille = permille) }
            observe(label, permille / 100 * 10, permille / 1000f)
        }
        compose.onNodeWithText(context.getString(R.string.video_export_details))
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { check(opened == 1 && dismissed == 0) { "Details callback: $opened / $dismissed" } }
        compose.onNodeWithContentDescription(context.getString(R.string.video_export_dismiss))
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { check(opened == 1 && dismissed == 1) { "Dismiss callback: $opened / $dismissed" } }
        println("VIDEO_EXPORT_ACCESSIBILITY callbacks details=1 dismiss=1; no export enqueued; audible speech not measured")
    }

    private fun observe(labelResource: Int, coarsePercent: Int?, exactProgress: Float?) {
        compose.waitForIdle()
        val label = context.getString(labelResource)
        val expectedState = coarsePercent?.let { context.getString(R.string.video_export_progress_percent, it) }
        val phase = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        check(phase.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite)
        check(phase.config.getOrNull(SemanticsProperties.StateDescription) == expectedState)
        val expectedRange = exactProgress?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
        val rangeNodes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, expectedRange),
            useUnmergedTree = true).fetchSemanticsNodes()
        check(rangeNodes.isNotEmpty()) { "Missing exact range $expectedRange" }
        if (exactProgress != null) {
            compose.onNodeWithText(context.getString(R.string.video_export_progress_percent, (exactProgress * 100f).toInt()),
                useUnmergedTree = true).assertIsDisplayed()
        } else {
            compose.onNodeWithText(context.getString(R.string.video_export_progress_percent, 0), useUnmergedTree = true).assertDoesNotExist()
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
                            val polite = node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE &&
                                View.ACCESSIBILITY_LIVE_REGION_POLITE !in inherited
                            val state = node.stateDescription?.toString()
                            observations.put(JSONObject().put("text", node.text.toString())
                                .put("stateDescription", state ?: JSONObject.NULL).put("visible", node.isVisibleToUser)
                                .put("regions", JSONArray(regions)).put("bounds", bounds.toShortString()))
                            if (node.isVisibleToUser && !bounds.isEmpty && polite && state == expectedState) matched = true
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
        println("VIDEO_EXPORT_ACCESSIBILITY " + JSONObject().put("phase", label)
            .put("coarsePercent", coarsePercent ?: JSONObject.NULL).put("exactProgress", exactProgress ?: JSONObject.NULL)
            .put("androidMatches", observations).put("matched", matched))
        check(matched) { "No visible polite Android phase with expected state $label / $expectedState: $observations" }
    }
}
