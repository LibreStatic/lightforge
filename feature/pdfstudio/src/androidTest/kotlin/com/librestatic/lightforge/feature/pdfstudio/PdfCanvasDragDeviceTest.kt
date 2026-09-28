package com.librestatic.lightforge.feature.pdfstudio

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Real-touch device regression test for the drag/pan/fit bug fixes in PdfCanvas.kt (user report:
 * dragging an image on a real folded device (physical foldable, expanded three-pane layout) also
 * panned the whole page, desynced the dragged element from its own outline, sometimes moved the
 * OTHER element too, and the page wasn't fit inside the canvas at rest). Unlike
 * [PdfMultiSelectTest] (VM-level calls only) this drives the actual Compose gesture detectors
 * (`detectDragGestures`/`detectTransformGestures` in PdfCanvas.kt) with genuine [MotionEvent]s
 * injected through [android.app.UiAutomation] — the same mechanism [PdfKeyboardPointerDeviceTest]
 * uses for its stylus resize, but with [MotionEvent.TOOL_TYPE_FINGER]/
 * [InputDevice.SOURCE_TOUCHSCREEN] (a plain one-finger touch drag, matching the reported bug).
 *
 * Reuses [PdfUiProbeActivity] (the same activity `tools/verify_pdf_adaptive_ui.py --drag`
 * exercises over adb, proven to reliably populate its probe state) with its `multi` fixture (one
 * image + one text) at an 860dp width — the same expanded three-pane width the bug was filed
 * against — rather than [PdfInputProbeActivity] (a fixed 840x640 activity built for the keyboard/
 * stylus test), since driving a hand-built fixture through that activity in this test hung
 * indefinitely for reasons unrelated to the fix under test (probably to do with its always-shown
 * software keyboard on this emulator image) and this activity is already known-good.
 *
 * Screen coordinates come from [PdfCanvasProbe] (the androidTest-only observation seam added in
 * PdfCanvas.kt alongside these fixes) rather than recomputed from the viewport's own nominal
 * width/height, precisely because a wrong page-box rect for the current layout IS one of the bugs
 * under test (item 4 below) — recomputing it here would just re-encode the same assumption the
 * production code might get wrong and could mask a regression. Model-space geometry (element
 * rects, viewport) comes straight from PdfUiProbeActivity's own `pdf-ui-state.json`, read directly
 * off disk in-process (same app, same filesDir — no adb needed).
 */
class PdfCanvasDragDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val stateFile = File(context.filesDir, "pdf-ui-state.json")
    private val projectIdFile = File(context.filesDir, "pdf-ui-project")

    private fun state(): JSONObject {
        // The probe activity's own write loop (~150ms cadence) can catch the file mid-write; a
        // truncated/partial read is transient, so retry rather than fail on it.
        var lastError: Exception? = null
        repeat(20) {
            try {
                return JSONObject(stateFile.readText())
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(20)
            }
        }
        throw requireNotNull(lastError)
    }

    private fun rect(obj: JSONObject, key: String): JSONObject? = obj.optJSONObject(key)
    private fun geometry(images: org.json.JSONArray, index: Int) = images.getJSONObject(index)
    private fun sameGeometry(a: JSONObject, b: JSONObject) =
        a.getDouble("x") == b.getDouble("x") && a.getDouble("y") == b.getDouble("y") &&
            a.getDouble("width") == b.getDouble("width") && a.getDouble("height") == b.getDouble("height")

    @Test
    fun dragMovesOnlyTheDraggedElementLeavesTheViewportAloneAndIsOneUndoStep(): Unit = runBlocking {
        check(context.packageName == "com.librestatic.lightforge.feature.pdfstudio.test")
        stateFile.delete()
        val configFile = File(context.filesDir, "pdf-ui-config.json")
        configFile.writeText(JSONObject().put("width", 860).put("font", 1).put("locale", "en").toString())

        var scenario: ActivityScenario<PdfUiProbeActivity>? = null
        var projectId: String? = null
        try {
            val launched = ActivityScenario.launch<PdfUiProbeActivity>(
                Intent(context, PdfUiProbeActivity::class.java)
                    .putExtra("width", 860).putExtra("height", 640).putExtra("font", 1f)
                    .putExtra("locale", "en").putExtra("dark", false).putExtra("rtl", false)
                    .putExtra("dynamic", false).putExtra("multi", true),
            )
            scenario = launched
            waitFor { stateFile.exists() && !state().getBoolean("busy") && state().has("project") && !state().isNull("project") }
            waitFor { PdfCanvasProbe.pageBoxCoordinates?.isAttached == true && PdfCanvasProbe.canvasCoordinates?.isAttached == true }
            waitFor { state().optJSONObject("pageBoxRectPx") != null && state().optJSONObject("canvasRectPx") != null }
            projectId = state().getString("project")

            val undoLabel = context.getString(R.string.pdf_undo)
            val resizeLabel = context.getString(R.string.pdf_resize_label, 1)
            suspend fun tapUndo() {
                val undoBounds = findBounds(undoLabel)
                touch(undoBounds.centerXf(), undoBounds.centerYf(), undoBounds.centerXf(), undoBounds.centerYf())
            }

            // --- (a) Fit at rest: the page box lies fully inside the canvas pane. ---
            val baseline = state()
            val canvasRect = requireNotNull(PdfCanvasProbe.canvasCoordinates).boundsInWindow()
            val pageRect = requireNotNull(PdfCanvasProbe.pageBoxCoordinates).boundsInWindow()
            assertTrue(
                "page box $pageRect not fit inside canvas $canvasRect",
                pageRect.left >= canvasRect.left - 1f && pageRect.top >= canvasRect.top - 1f &&
                    pageRect.right <= canvasRect.right + 1f && pageRect.bottom <= canvasRect.bottom + 1f,
            )
            assertEquals(1.0, baseline.getDouble("zoom"), 0.001)
            assertEquals(0.0, baseline.getDouble("panX"), 0.001)
            assertEquals(0.0, baseline.getDouble("panY"), 0.001)

            val elements = baseline.getJSONObject("elementsMm")
            val pageWidthMm = elements.getDouble("pageWidthMm")
            val img0 = geometry(elements.getJSONArray("images"), 0)
            val txt0 = geometry(elements.getJSONArray("texts"), 0)
            val pxPerMm = ((pageRect.right - pageRect.left) / pageWidthMm)
            val startX = pageRect.left + ((img0.getDouble("x") + img0.getDouble("width") / 2) * pxPerMm).toFloat()
            val startY = pageRect.top + ((img0.getDouble("y") + img0.getDouble("height") / 2) * pxPerMm).toFloat()
            // 60mm, not 20mm, and a wide (12mm) tolerance below: Compose's detectDragGestures
            // reports the accumulated onDrag delta as the RAW pointer travel MINUS the touch-slop
            // distance the gesture had to clear before it was even recognized as a drag
            // (viewConfiguration.touchSlop, ~8dp) — a real, expected, well-documented Compose
            // behavior, not a bug (confirmed by direct on-device measurement: a 20mm intended
            // delta measurably landed ~10-14mm). A larger requested delta makes that fixed
            // deduction a small fraction of the total.
            val deltaMm = 60.0
            val endX = startX + (deltaMm * pxPerMm).toFloat()
            val endY = startY + (deltaMm * pxPerMm).toFloat()

            // --- (b) Real one-finger touch drag: only A moves, B is untouched, and the viewport
            // (zoom/pan) never changes — the gesture-arbitration fix under test. ---
            touch(startX, startY, endX, endY)
            waitFor {
                val img = geometry(state().getJSONObject("elementsMm").getJSONArray("images"), 0)
                img.getDouble("x") > img0.getDouble("x") + 30.0
            }
            val afterDrag = state()
            val draggedImage = geometry(afterDrag.getJSONObject("elementsMm").getJSONArray("images"), 0)
            val untouchedText = geometry(afterDrag.getJSONObject("elementsMm").getJSONArray("texts"), 0)
            assertEquals(img0.getDouble("x") + deltaMm, draggedImage.getDouble("x"), 12.0)
            assertEquals(img0.getDouble("y") + deltaMm, draggedImage.getDouble("y"), 12.0)
            assertTrue("the OTHER (non-dragged) text moved: $txt0 -> $untouchedText", sameGeometry(txt0, untouchedText))
            assertEquals("viewport zoom changed during an element drag", baseline.getDouble("zoom"), afterDrag.getDouble("zoom"), 0.001)
            assertEquals("viewport panX changed during an element drag", baseline.getDouble("panX"), afterDrag.getDouble("panX"), 0.001)
            assertEquals("viewport panY changed during an element drag", baseline.getDouble("panY"), afterDrag.getDouble("panY"), 0.001)

            // --- (c) One committed Undo step exactly restores the pre-drag position. ---
            tapUndo()
            waitFor {
                val img = geometry(state().getJSONObject("elementsMm").getJSONArray("images"), 0)
                img.getDouble("x") == img0.getDouble("x") && img.getDouble("y") == img0.getDouble("y")
            }

            // --- (d) Corner-handle resize: only A's size changes, B stays untouched. ---
            val handle = findBounds(resizeLabel)
            touch(handle.centerXf(), handle.centerYf(), handle.centerXf() + 120f, handle.centerYf() + 120f)
            waitFor {
                val img = geometry(state().getJSONObject("elementsMm").getJSONArray("images"), 0)
                img.getDouble("width") > img0.getDouble("width") + 5.0 || img.getDouble("height") > img0.getDouble("height") + 5.0
            }
            val resized = state()
            val resizedImage = geometry(resized.getJSONObject("elementsMm").getJSONArray("images"), 0)
            val textAfterResize = geometry(resized.getJSONObject("elementsMm").getJSONArray("texts"), 0)
            assertTrue(resizedImage.getDouble("width") > img0.getDouble("width") || resizedImage.getDouble("height") > img0.getDouble("height"))
            assertEquals("corner resize moved the element", img0.getDouble("x"), resizedImage.getDouble("x"), 0.001)
            assertEquals("corner resize moved the element", img0.getDouble("y"), resizedImage.getDouble("y"), 0.001)
            assertTrue("corner resize on A affected B", sameGeometry(txt0, textAfterResize))
            tapUndo()
        } finally {
            var cleanupFailure: Throwable? = null
            try { scenario?.close() } catch (error: Throwable) { cleanupFailure = error }
            try {
                val repo = PdfProjectRepository(context)
                val id = projectId ?: projectIdFile.takeIf { it.exists() }?.readText()
                if (id != null) repo.delete(id, emptySet())
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            stateFile.delete(); configFile.delete(); projectIdFile.delete()
            cleanupFailure?.let { throw it }
        }
    }

    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(20_000) {
        while (!predicate()) delay(50)
    }
    private suspend fun findBounds(label: String): android.graphics.Rect {
        var found: android.graphics.Rect? = null
        waitFor {
            found = windowNodes().firstOrNull { it.contentDescription?.toString() == label && it.isVisibleToUser }
                ?.let { android.graphics.Rect().also(it::getBoundsInScreen) }?.takeIf { !it.isEmpty }
            found != null
        }
        return requireNotNull(found)
    }
    private fun nodes(root: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun windowNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return nodes(automation.rootInActiveWindow)
    }
    private fun android.graphics.Rect.centerXf() = (left + right) / 2f
    private fun android.graphics.Rect.centerYf() = (top + bottom) / 2f

    /** A real one-finger touch drag (DOWN, several MOVEs with real delays so touch-slop and
     * gesture recognition behave as they would from an actual finger, then UP) via
     * [android.app.UiAutomation.injectInputEvent] — genuine [MotionEvent]s dispatched through the
     * real input pipeline, not a Compose test-rule synthetic gesture, so this exercises the exact
     * same `detectDragGestures`/`detectTransformGestures` arbitration a real device does. */
    private fun touch(x: Float, y: Float, endX: Float, endY: Float) {
        val down = SystemClock.uptimeMillis()
        var lastX = x
        var lastY = y
        fun send(action: Int, px: Float, py: Float) {
            val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = px; this.y = py
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            }
            val event = MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action, 1,
                arrayOf(property), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            lastX = px; lastY = py
        }
        try {
            send(MotionEvent.ACTION_DOWN, x, y)
            val steps = 16
            for (step in 1..steps) {
                SystemClock.sleep(18)
                send(MotionEvent.ACTION_MOVE, x + (endX - x) * step / steps, y + (endY - y) * step / steps)
            }
        } finally { send(MotionEvent.ACTION_UP, lastX, lastY) }
    }
}
