package com.ugallery.feature.pdfstudio

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses Android accessibility actions, not injected taps or a replacement Compose screen. */
class PdfAccessibleAdjustTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList()
        else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }

    private fun windowNodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return nodes(automation.rootInActiveWindow)
    }

    private suspend fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        withTimeout(15_000) {
            while (true) {
                windowNodes().firstOrNull(predicate)?.let {
                    return@withTimeout it
                }
                delay(50)
            }
            error("unreachable")
        }

    private suspend fun widthField(): AccessibilityNodeInfo =
        withTimeout(20_000) {
            var step = 0
            var expanded = false
            while (true) {
                capture("width-search-${step++}")
                val visible = windowNodes()
                visible
                    .firstOrNull { node ->
                        node.isEditable &&
                            nodes(node).any { child ->
                                child.text?.toString()?.contains("Width") == true ||
                                    child.hintText?.toString()?.contains("Width") == true
                            }
                    }
                    ?.let {
                        return@withTimeout it
                    }
                // Short landscape windows start with a partially expanded sheet. Use the
                // same native actions available to accessibility services, never coordinates.
                val expand = AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id
                val owner = visible.firstOrNull { node -> node.actionList.any { it.id == expand } }
                if (owner != null && !expanded) {
                    expanded = owner.performAction(expand)
                    automation.waitForIdle(300, 10_000)
                } else {
                    val scroll =
                        visible.firstOrNull { node ->
                            node.className == "android.widget.ScrollView" &&
                                node.actionList.any {
                                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                }
                        }
                    if (scroll != null) {
                        // A node can become stale during the sheet animation. The next
                        // iteration refreshes the tree; success still requires the real field.
                        scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        automation.waitForIdle(300, 10_000)
                    }
                }
                delay(500)
            }
            error("unreachable")
        }

    private fun actionOwner(
        label: AccessibilityNodeInfo,
        actionLabel: String,
    ): AccessibilityNodeInfo {
        val bounds = android.graphics.Rect().also(label::getBoundsInScreen)
        var candidate: AccessibilityNodeInfo? = label
        while (candidate != null) {
            // Android 15 may expand the action owner to its minimum touch target.
            // Keep the label inside that ancestor and require the exact action label.
            if (!android.graphics.Rect().also(candidate::getBoundsInScreen).contains(bounds)) break
            if (candidate.actionList.any { it.label?.toString() == actionLabel }) return candidate
            candidate = candidate.parent
        }
        throw AssertionError("No action owner for $actionLabel")
    }

    private fun capture(name: String) {
        val target = File(context.filesDir, "pdf-accessible-adjust").apply { mkdirs() }
        File(target, "$name.txt")
            .writeText(
                windowNodes().joinToString("\n") {
                    "${it.className} bounds=${android.graphics.Rect().also(it::getBoundsInScreen)} text=${it.text} desc=${it.contentDescription} hint=${it.hintText} clickable=${it.isClickable} checked=${it.isChecked} selected=${it.isSelected} actions=${it.actionList}"
                }
            )
    }

    @Test
    fun resizeAndImageActionsOpenNumericControlsOnCompactAndExpandedLayouts(): Unit = runBlocking {
        val marker = File(context.filesDir, "pdf-ui-project")
        check(!marker.exists()) { "Use a fresh module fixture" }
        File(context.filesDir, "pdf-accessible-adjust").deleteRecursively()
        val config = File(context.filesDir, "pdf-ui-config.json")
        val repo = PdfProjectRepository(context)
        for (width in listOf(360, 840)) {
            config.writeText(
                JSONObject().put("width", width).put("font", 1).put("locale", "en").toString()
            )
            val intent =
                Intent(context, PdfUiProbeActivity::class.java)
                    .putExtra("width", width)
                    .putExtra("height", 640)
                    .putExtra("dark", width == 840)
            try {
                ActivityScenario.launch<PdfUiProbeActivity>(intent).use {
                    val handleLabel = find { it.contentDescription?.toString() == "Resize image 1" }
                    capture("$width-before")
                    val handle = actionOwner(handleLabel, "Resize image 1")
                    assertTrue(
                        "Resize handle has no accessibility click action",
                        handle.isClickable,
                    )
                    assertTrue(handle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    automation.waitForIdle(300, 10_000)
                    capture("$width-opened")
                    val field = widthField()
                    capture("$width-adjust")
                    assertTrue(
                        "$width focus Width",
                        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS),
                    )
                    automation.waitForIdle(300, 10_000)
                    assertTrue(field.refresh())
                    assertTrue(
                        "$width set Width",
                        field.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "50",
                                )
                            },
                        ),
                    )
                    capture("$width-set-text")
                    val edited = find { it.isEditable && it.text?.toString() == "50" }
                    assertTrue(
                        edited.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    )
                    capture("$width-ime-commit")
                    val id = marker.readText()
                    withTimeout(15_000) {
                        while (repo.load(id)?.pages?.first()?.images?.first()?.width != 50.0) delay(
                            50
                        )
                    }
                    capture("$width-persisted")
                    // A fully expanded sheet can have no visible scrim. Dismiss the
                    // sheet through its labeled native action, independent of IME visibility.
                    val dismiss = AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id
                    val close = find { node ->
                        node.actionList.any {
                            it.id == dismiss && it.label?.toString()?.contains("Dismiss bottom sheet") == true
                        }
                    }
                    assertTrue(close.performAction(dismiss))
                    automation.waitForIdle(300, 10_000)
                    capture("$width-closed")
                    val image = find { it.contentDescription?.toString() == "Image 1" }
                    val owner = actionOwner(image, "Adjust")
                    val action = owner.actionList.single { it.label?.toString() == "Adjust" }
                    assertTrue(owner.performAction(action.id))
                    widthField()
                    capture("$width-custom-action")
                    File(context.filesDir, "pdf-accessible-adjust/$width-result.json")
                        .writeText(
                            """{"status":"PASS","width":$width,"imageWidth":50,"resizeClick":true,"customAdjust":true}"""
                        )
                }
            } catch (error: Throwable) {
                capture("$width-failure")
                throw error
            } finally {
                if (marker.exists()) {
                    repo.delete(marker.readText())
                    marker.delete()
                }
                config.delete()
            }
        }
    }
}
