package com.ugallery.feature.privatealbum

import android.app.Activity
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLCipher + encrypted source + production Compose, no normal app/credentials/public writes. */
@RunWith(AndroidJUnit4::class)
class PrivateViewerFlowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoZoomAndRevocationNeverExportOrRetainPlaintextCache() = runBlocking {
        val f = Fixture(); var passed = false; var composed = false
        var probe: PrivateViewerSource? = null
        val mounted = mutableStateOf(true); val disposed = AtomicBoolean(false)
        try {
            val image = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 128) image.setPixel(x, y, if (x < 64) Color.RED else Color.BLUE)
            val source = f.createSource("photo.png") { out -> try { check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { image.recycle() } }
            val id = f.import(source, "image/png", "image", 128, 64, 0)
            var exports = 0; var backs = 0; var unlocks = 0
            compose.setContent { if (mounted.value) {
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                UGalleryTheme { PrivateMediaViewerContent(f.repository, id, { backs++ }, { unlocks++ }, { exports++ }) }
            } }
            composed = true
            awaitTag("private-viewer-photo")
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(f.base.getString(R.string.private_viewer_photo)).fetchSemanticsNodes().size == 1 }
            val before = zoom()
            compose.onNodeWithTag("private-viewer-zoom").performTouchInput { click(Offset(width * .25f, center.y)) }
            compose.waitUntil(10_000) { zoom() > before + .5f }
            compose.onNodeWithTag("private-viewer-photo").performTouchInput { swipe(Offset(width * .7f, center.y), Offset(width * .35f, center.y), 250) }
            compose.onNodeWithContentDescription(f.base.getString(R.string.private_viewer_photo)).assertExists()
            val reader = f.repository.openViewerSource(id); probe = reader
            val bytes = ByteArray(8)
            assertEquals(8, reader.readAt(0, bytes, 0, bytes.size))
            assertArrayEquals(source.readBytes().copyOf(8), bytes)
            f.repository.revokeSession()
            assertFalse(reader.valid.value)
            assertTrue(runCatching { reader.readAt(0, bytes, 0, bytes.size) }.isFailure)
            awaitTag("private-viewer-locked")
            compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
            compose.onNodeWithContentDescription(f.base.getString(R.string.private_viewer_photo)).assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, exports); assertEquals(0, backs); assertEquals(0, unlocks) }
            f.record("photo", JSONObject().put("zoomBefore", before).put("zoomChanged", true).put("readerRevoked", true).put("masked", true))
            passed = true
        } finally {
            probe?.close(); f.repository.disposeSession()
            try {
                if (composed) { compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() } }
            } finally { f.finish(passed) }
        }
    }

    @Test fun videoOpensPausedSeeksPlaysAndRevokesWithoutExport() = runBlocking {
        val f = Fixture(); var passed = false; var composed = false
        val mounted = mutableStateOf(true); val disposed = AtomicBoolean(false)
        try {
            val source = f.createSource("video.mp4") { out -> InstrumentationRegistry.getInstrumentation().context.assets.open("motion_fixture.mp4").use { it.copyTo(out) } }
            assertEquals("ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861", sha(source))
            val id = f.import(source, "video/mp4", "video", 320, 240, 3_000)
            var exports = 0
            compose.setContent { if (mounted.value) {
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                UGalleryTheme { PrivateMediaViewerContent(f.repository, id, {}, {}, { exports++ }) }
            } }
            composed = true
            awaitTag("private-viewer-video"); awaitPlayLabel(f.base.getString(R.string.private_viewer_play))
            assertEquals(0L, position())
            compose.onNodeWithTag("private-viewer-seek").performTouchInput { click(Offset(width * .55f, center.y)) }
            compose.waitUntil(10_000) { position() in 500..2_800 }
            awaitPlayLabel(f.base.getString(R.string.private_viewer_play))
            val seeked = position()
            compose.onNodeWithTag("private-viewer-play").performClick()
            awaitPlayLabel(f.base.getString(R.string.private_viewer_pause))
            compose.waitUntil(10_000) { position() > seeked + 150 }
            compose.onNodeWithTag("private-viewer-play").performClick()
            awaitPlayLabel(f.base.getString(R.string.private_viewer_play))
            val advanced = position(); assertTrue(advanced > seeked)
            f.repository.revokeSession()
            awaitTag("private-viewer-locked")
            compose.onNodeWithTag("private-viewer-video").assertDoesNotExist()
            compose.onNodeWithTag("private-viewer-play").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, exports) }
            f.record("video", JSONObject().put("initialPaused", true).put("seekedMillis", seeked).put("advancedMillis", advanced).put("masked", true))
            passed = true
        } finally {
            f.repository.disposeSession()
            try {
                if (composed) { compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() } }
            } finally { f.finish(passed) }
        }
    }

    @Test fun photoHomeLocksAndReturnRequiresExplicitUnlock() = runBlocking {
        val f = Fixture(); var passed = false; var composed = false
        val mounted = mutableStateOf(true); val disposed = AtomicBoolean(false)
        val capturedActivity = AtomicReference<Activity?>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var wasSentHome = false
        try {
            val image = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 128) image.setPixel(x, y, if (x < 64) Color.RED else Color.BLUE)
            val source = f.createSource("photo.png") { out -> try { check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { image.recycle() } }
            val id = f.import(source, "image/png", "image", 128, 64, 0)
            val handlerName = "private-image-$id"
            check(Thread.getAllStackTraces().keys.none { it.isAlive && it.name == handlerName }) { "Preexisting decoder must not be adopted" }
            var exports = 0; var backs = 0; var unlocks = 0
            compose.setContent { if (mounted.value) {
                val activity = requireNotNull(unwrapActivity(LocalContext.current))
                SideEffect { capturedActivity.set(activity) }
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                UGalleryTheme { PrivateMediaViewerContent(f.repository, id, { backs++ }, { unlocks++ }, { exports++ }) }
            } }
            composed = true
            awaitTag("private-viewer-photo")
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(f.base.getString(R.string.private_viewer_photo)).fetchSemanticsNodes().size == 1 }
            val activity = requireNotNull(capturedActivity.get())
            val component = activity.componentName
            val task = activity.taskId
            val pid = Process.myPid()
            check(component.packageName == f.base.packageName && task > 0)
            val handler = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == handlerName }.single()
            val handlerId = handler.id
            fun lifecycleStage(): Stage {
                val stage = AtomicReference<Stage>()
                instrumentation.runOnMainSync { stage.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)) }
                return requireNotNull(stage.get())
            }
            fun awaitCondition(label: String, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { label }; SystemClock.sleep(25) }
            }
            check(lifecycleStage() == Stage.RESUMED)
            f.record("home", JSONObject().put("component", component.flattenToString()).put("task", task).put("pid", pid)
                .put("handlerName", handlerName).put("handlerId", handlerId).put("phase", "beforeHome"))
            check(UiDevice.getInstance(instrumentation).pressHome())
            wasSentHome = true
            awaitCondition("Actual Activity never STOPPED") { lifecycleStage() == Stage.STOPPED }
            f.record("home", JSONObject().put("component", component.flattenToString()).put("task", task).put("pid", pid)
                .put("handlerName", handlerName).put("handlerId", handlerId).put("phase", "stopped"))
            // Thread identity was captured from this one decoded image, not a global count after return.
            awaitCondition("Owned private image handler survived STOP") { !handler.isAlive }
            f.base.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            awaitCondition("Same Activity did not resume") { lifecycleStage() == Stage.RESUMED }
            wasSentHome = false
            check(capturedActivity.get() === activity && !activity.isDestroyed && activity.taskId == task && Process.myPid() == pid)
            awaitTag("private-viewer-locked")
            compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
            compose.onNodeWithContentDescription(f.base.getString(R.string.private_viewer_photo)).assertDoesNotExist()
            compose.onNodeWithTag("private-viewer-export").assertDoesNotExist()
            val unlock = compose.onNodeWithText(f.base.getString(R.string.private_viewer_unlock))
            unlock.assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, unlocks); assertEquals(0, backs); assertEquals(0, exports) }
            check(!handler.isAlive && Thread.getAllStackTraces().keys.none { it.isAlive && it.name == handlerName })
            unlock.performClick()
            compose.runOnIdle { assertEquals(1, unlocks); assertEquals(0, backs); assertEquals(0, exports) }
            // Callback notification is not OS authentication: no automatic reopening is claimed.
            compose.onNodeWithTag("private-viewer-locked").assertExists()
            compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
            f.record("home", JSONObject().put("component", component.flattenToString()).put("task", task).put("pid", pid)
                .put("handlerName", handlerName).put("handlerId", handlerId).put("phase", "returnedLocked")
                .put("stopped", true).put("sameActivity", true).put("handlerClosedBeforeReturn", true)
                .put("unlockCallbacks", unlocks).put("exports", exports).put("automaticReopen", false))
            passed = true
        } finally {
            f.repository.disposeSession()
            try {
                if (wasSentHome) capturedActivity.get()?.let { activity ->
                    f.base.startActivity(Intent().setComponent(activity.componentName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                if (composed) { compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() } }
            } finally { f.finish(passed) }
        }
    }

    /** Local presentation only: real encrypted PNG, 200%/RTL and two static Material themes.
     * No global settings, screenshot, export, simulated authentication or dynamic/video claim.
     */
    @Test fun photoViewerAt200PercentRtlKeepsControlsReachableAndInsetSafe() = runBlocking {
        val f = Fixture(); var passed = false; var composed = false
        val mounted = mutableStateOf(true); val disposed = AtomicBoolean(false)
        val dark = mutableStateOf(false)
        val activityRef = AtomicReference<Activity?>()
        var initialSecure: Boolean? = null
        var density = 0f; var observedScale = 0f; var observedDirection = LayoutDirection.Ltr
        var background = ComposeColor.Unspecified
        var primary = ComposeColor.Unspecified; var onPrimary = ComposeColor.Unspecified
        var exports = 0; var backs = 0; var unlocks = 0
        try {
            val image = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 128) image.setPixel(x, y, if (x < 64) Color.RED else Color.BLUE)
            val source = f.createSource("photo.png") { out -> try { check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { image.recycle() } }
            val id = f.import(source, "image/png", "image", 128, 64, 0)
            compose.setContent { if (mounted.value) {
                val activity = requireNotNull(unwrapActivity(LocalContext.current))
                // Capture before the viewer's DisposableEffect adds FLAG_SECURE.
                remember(activity) {
                    initialSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    activityRef.set(activity)
                    Unit
                }
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                val localDensity = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(localDensity, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    UGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                        val currentDensity = LocalDensity.current
                        val currentDirection = LocalLayoutDirection.current
                        val colors = MaterialTheme.colorScheme
                        SideEffect {
                            density = currentDensity.density; observedScale = currentDensity.fontScale
                            observedDirection = currentDirection
                            background = colors.surface; primary = colors.primary; onPrimary = colors.onPrimary
                        }
                        Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds().testTag("private-viewer-test-viewport")) {
                            PrivateMediaViewerContent(f.repository, id, { backs++ }, { unlocks++ }, { exports++ })
                        }
                    }
                }
            } }
            composed = true
            awaitTag("private-viewer-photo")
            val photoLabel = f.base.getString(R.string.private_viewer_photo)
            val resetLabel = f.base.getString(R.string.private_viewer_reset)
            val exportLabel = f.base.getString(R.string.private_viewer_export)
            val unlockLabel = f.base.getString(R.string.private_viewer_unlock)
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(photoLabel).fetchSemanticsNodes().size == 1 }
            val activity = requireNotNull(activityRef.get())
            fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
            fun usableBounds(): Rect {
                val result = AtomicReference<Rect>()
                compose.runOnIdle {
                    assertEquals(2f, observedScale, 0f)
                    assertEquals(LayoutDirection.Rtl, observedDirection)
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    val decor = activity.window.decorView
                    val offset = IntArray(2); decor.getLocationInWindow(offset)
                    val insets = requireNotNull(decor.rootWindowInsets)
                        .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    result.set(Rect((offset[0] + insets.left).toFloat(), (offset[1] + insets.top).toFloat(),
                        (offset[0] + decor.width - insets.right).toFloat(), (offset[1] + decor.height - insets.bottom).toFloat()))
                }
                val viewport = compose.onNodeWithTag("private-viewer-test-viewport").fetchSemanticsNode().boundsInWindow
                assertEquals("Viewport must actually be 360dp, not silently clipped", 360f * density, viewport.width, 1f)
                assertEquals("Viewport must actually be 640dp, not silently clipped", 640f * density, viewport.height, 1f)
                val safe = result.get()
                return Rect(maxOf(viewport.left, safe.left), maxOf(viewport.top, safe.top),
                    minOf(viewport.right, safe.right), minOf(viewport.bottom, safe.bottom))
            }
            fun reachable(node: SemanticsNodeInteraction, safe: Rect, label: String): Rect {
                node.assertIsDisplayed().assertIsEnabled()
                val bounds = node.fetchSemanticsNode().boundsInWindow
                assertTrue("$label needs positive bounds: $bounds", bounds.width > 0 && bounds.height > 0)
                assertTrue("$label clipped or behind a system inset: $bounds vs $safe",
                    bounds.left >= safe.left - 1f && bounds.top >= safe.top - 1f &&
                        bounds.right <= safe.right + 1f && bounds.bottom <= safe.bottom + 1f)
                return bounds
            }
            fun observedTextContrast(label: String, expectedForeground: ComposeColor, container: ComposeColor): Double {
                val text = compose.onNodeWithText(label, useUnmergedTree = true)
                text.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> check(action(layouts)) }
                val layout = layouts.single()
                val bounds = text.fetchSemanticsNode().boundsInWindow
                f.record("adaptiveText-" + label, JSONObject().put("dark", dark.value)
                    .put("bounds", rectJson(bounds)).put("sizeWidth", layout.size.width).put("sizeHeight", layout.size.height)
                    .put("overflowWidth", layout.didOverflowWidth).put("overflowHeight", layout.didOverflowHeight)
                    .put("paragraphWidth", layout.multiParagraph.width).put("paragraphHeight", layout.multiParagraph.height)
                    .put("constraints", layout.layoutInput.constraints.toString()).put("lines", layout.lineCount)
                    .put("fontScale", layout.layoutInput.density.fontScale).put("style", layout.layoutInput.style.toString())
                    .put("lineBounds", JSONArray((0 until layout.lineCount).map { line -> JSONArray(listOf(
                        layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))) })))
                // GetTextLayoutResult's aggregate width flag can include paragraph rounding
                // beyond the visible glyph extents. Assert actual complete laid-out content.
                assertTrue("Control text is clipped: $label", bounds.width + 1f >= layout.size.width && bounds.height + 1f >= layout.size.height)
                assertTrue(layout.lineCount > 0)
                assertEquals("Control text must be complete: $label", label.length, layout.getLineEnd(layout.lineCount - 1))
                fun contained(rect: Rect) = rect.left >= -1f && rect.top >= -1f &&
                    rect.right <= layout.size.width + 1f && rect.bottom <= layout.size.height + 1f &&
                    rect.right <= bounds.width + 1f && rect.bottom <= bounds.height + 1f
                for (line in 0 until layout.lineCount) {
                    assertFalse("Control text is ellipsized: $label", layout.isLineEllipsized(line))
                    assertTrue("Control line clipped: $label", contained(Rect(layout.getLineLeft(line), layout.getLineTop(line),
                        layout.getLineRight(line), layout.getLineBottom(line))))
                }
                for (offset in label.indices) assertTrue("Control glyph clipped: $label at $offset", contained(layout.getBoundingBox(offset)))
                val foreground = layout.layoutInput.style.color
                assertEquals("Effective text must use its matching Material role: $label", expectedForeground, foreground)
                assertEquals(1f, foreground.alpha, 0f); assertEquals(1f, container.alpha, 0f)
                val a = foreground.luminance().toDouble(); val b = container.luminance().toDouble()
                val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                assertTrue("Observed $label contrast $ratio is below 4.5", ratio >= 4.5)
                return ratio
            }
            val observations = JSONArray()
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = isDark }
                val safe = usableBounds()
                val back = compose.onNodeWithContentDescription(f.base.getString(R.string.private_viewer_back))
                reachable(back, safe, "Back")
                val slider = compose.onNodeWithTag("private-viewer-zoom")
                reachable(slider, safe, "Zoom")
                val reset = compose.onNodeWithText(resetLabel)
                reachable(reset, safe, "Reset")
                reachable(compose.onNodeWithTag("private-viewer-export"), safe, "Export")
                val photo = reachable(compose.onNodeWithTag("private-viewer-photo"), safe, "Photo")
                compose.onNodeWithContentDescription(photoLabel).assertIsDisplayed()
                val resetContrast = observedTextContrast(resetLabel, primary, background)
                val exportContrast = observedTextContrast(exportLabel, primary, background)
                assertEquals(1f, zoom(), .01f)
                // In RTL the right side is the low end. A middle tap changes the real Slider.
                slider.performTouchInput { click(center) }
                compose.waitUntil(10_000) { zoom() > 1.5f }
                val changedZoom = zoom()
                reset.performTouchInput { click(center) }
                compose.waitUntil(10_000) { kotlin.math.abs(zoom() - 1f) < .01f }
                compose.runOnIdle { assertEquals(0, exports); assertEquals(0, backs); assertEquals(0, unlocks) }
                observations.put(JSONObject().put("dark", isDark).put("safeWindowBounds", rectJson(safe))
                    .put("photoWindowBounds", rectJson(photo)).put("zoomChanged", changedZoom).put("resetZoom", zoom())
                    .put("resetContrast", resetContrast).put("exportContrast", exportContrast))
                f.record("adaptive", JSONObject().put("fontScale", observedScale).put("layoutDirection", "Rtl")
                    .put("widthDp", 360).put("heightDp", 640).put("active", observations))
            }
            f.repository.revokeSession()
            awaitTag("private-viewer-locked")
            val lockedObservations = JSONArray()
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = isDark }
                val safe = usableBounds()
                compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
                compose.onNodeWithContentDescription(photoLabel).assertDoesNotExist()
                compose.onNodeWithTag("private-viewer-export").assertDoesNotExist()
                reachable(compose.onNodeWithTag("private-viewer-locked"), safe, "Locked message")
                val unlock = compose.onNodeWithText(unlockLabel)
                reachable(unlock, safe, "Unlock")
                val contrast = observedTextContrast(unlockLabel, onPrimary, primary)
                lockedObservations.put(JSONObject().put("dark", isDark).put("unlockContrast", contrast))
            }
            compose.onNodeWithText(unlockLabel).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(1, unlocks); assertEquals(0, backs); assertEquals(0, exports) }
            compose.onNodeWithTag("private-viewer-locked").assertExists()
            compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
            compose.runOnIdle { mounted.value = false }
            compose.waitUntil(10_000) { disposed.get() }
            compose.runOnIdle {
                assertEquals(requireNotNull(initialSecure), activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            f.record("adaptive", JSONObject().put("fontScale", observedScale).put("layoutDirection", "Rtl")
                .put("widthDp", 360).put("heightDp", 640).put("active", observations).put("locked", lockedObservations)
                .put("unlockCallbacks", unlocks).put("exports", exports).put("secureFlagRestored", true))
            passed = true
        } finally {
            f.repository.disposeSession()
            try {
                if (composed && !disposed.get()) { compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() } }
            } finally { f.finish(passed) }
        }
    }

    /** One real 3s asset in a local compact RTL/200% viewport; no global settings or export. */
    @Test fun videoViewerAt200PercentRtlKeepsPlaybackControlsReachableAndInsetSafe() = runBlocking {
        val f = Fixture(); var passed = false; var composed = false
        val mounted = mutableStateOf(true); val disposed = AtomicBoolean(false)
        val dark = mutableStateOf(false)
        val activityRef = AtomicReference<Activity?>()
        var initialSecure: Boolean? = null
        var density = 0f; var observedScale = 0f; var observedDirection = LayoutDirection.Ltr
        var surface = ComposeColor.Unspecified
        var primary = ComposeColor.Unspecified; var onPrimary = ComposeColor.Unspecified
        var exports = 0; var backs = 0; var unlocks = 0
        val observations = JSONArray(); val textObservations = JSONArray()
        try {
            val source = f.createSource("video.mp4") { out ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("motion_fixture.mp4").use { it.copyTo(out) }
            }
            assertEquals("ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861", sha(source))
            val id = f.import(source, "video/mp4", "video", 320, 240, 3_000)
            compose.setContent { if (mounted.value) {
                val activity = requireNotNull(unwrapActivity(LocalContext.current))
                remember(activity) {
                    initialSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    activityRef.set(activity); Unit
                }
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                val localDensity = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(localDensity, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl) {
                    UGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                        val currentDensity = LocalDensity.current
                        val direction = LocalLayoutDirection.current
                        val colors = MaterialTheme.colorScheme
                        SideEffect {
                            density = currentDensity.density; observedScale = currentDensity.fontScale
                            observedDirection = direction; surface = colors.surface
                            primary = colors.primary; onPrimary = colors.onPrimary
                        }
                        Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds().testTag("private-video-test-viewport")) {
                            PrivateMediaViewerContent(f.repository, id, { backs++ }, { unlocks++ }, { exports++ })
                        }
                    }
                }
            } }
            composed = true
            val playLabel = f.base.getString(R.string.private_viewer_play)
            val pauseLabel = f.base.getString(R.string.private_viewer_pause)
            val unlockLabel = f.base.getString(R.string.private_viewer_unlock)
            awaitTag("private-viewer-video"); awaitPlayLabel(playLabel)
            assertEquals("Opening must not start playback", 0L, position())
            val activity = requireNotNull(activityRef.get())
            fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
            fun record() = f.record("adaptiveVideo", JSONObject().put("fontScale", observedScale)
                .put("layoutDirection", observedDirection.name).put("widthDp", 360).put("heightDp", 640)
                .put("phases", observations).put("texts", textObservations).put("exports", exports).put("unlocks", unlocks))
            fun safeBounds(): Rect {
                val result = AtomicReference<Rect>()
                compose.runOnIdle {
                    assertEquals(2f, observedScale, 0f); assertEquals(LayoutDirection.Rtl, observedDirection)
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    val decor = activity.window.decorView; val offset = IntArray(2); decor.getLocationInWindow(offset)
                    val inset = requireNotNull(decor.rootWindowInsets)
                        .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    result.set(Rect((offset[0] + inset.left).toFloat(), (offset[1] + inset.top).toFloat(),
                        (offset[0] + decor.width - inset.right).toFloat(), (offset[1] + decor.height - inset.bottom).toFloat()))
                }
                val viewport = compose.onNodeWithTag("private-video-test-viewport").fetchSemanticsNode().boundsInWindow
                assertEquals("Actual viewport width", 360f * density, viewport.width, 1f)
                assertEquals("Actual viewport height", 640f * density, viewport.height, 1f)
                val safe = result.get()
                return Rect(maxOf(viewport.left, safe.left), maxOf(viewport.top, safe.top),
                    minOf(viewport.right, safe.right), minOf(viewport.bottom, safe.bottom))
            }
            fun reachable(node: SemanticsNodeInteraction, safe: Rect, label: String): Rect {
                node.assertIsDisplayed().assertIsEnabled()
                val bounds = node.fetchSemanticsNode().boundsInWindow
                observations.put(JSONObject().put("dark", dark.value).put("control", label)
                    .put("bounds", rectJson(bounds)).put("safe", rectJson(safe))); record()
                assertTrue("$label needs a positive visible area: $bounds", bounds.width > 0 && bounds.height > 0)
                assertTrue("$label is clipped or behind insets: $bounds vs $safe", bounds.left >= safe.left - 1f &&
                    bounds.top >= safe.top - 1f && bounds.right <= safe.right + 1f && bounds.bottom <= safe.bottom + 1f)
                return bounds
            }
            fun textContrast(label: String, foregroundRole: ComposeColor, container: ComposeColor): Double {
                val node = compose.onNodeWithText(label, useUnmergedTree = true)
                node.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> check(action(layouts)) }
                val layout = layouts.single(); val bounds = node.fetchSemanticsNode().boundsInWindow
                val lines = JSONArray()
                for (line in 0 until layout.lineCount) lines.put(JSONObject().put("index", line)
                    .put("left", layout.getLineLeft(line)).put("right", layout.getLineRight(line))
                    .put("top", layout.getLineTop(line)).put("bottom", layout.getLineBottom(line))
                    .put("start", layout.getLineStart(line)).put("end", layout.getLineEnd(line, visibleEnd = true))
                    .put("ellipsized", layout.isLineEllipsized(line)))
                val characterBoxes = JSONArray()
                for (index in label.indices) characterBoxes.put(rectJson(layout.getBoundingBox(index)))
                // Persist raw measurements BEFORE direct clipping assertions; aggregate overflow is diagnostic.
                textObservations.put(JSONObject().put("dark", dark.value).put("label", label)
                    .put("bounds", rectJson(bounds)).put("layoutWidth", layout.size.width).put("layoutHeight", layout.size.height)
                    .put("constraints", layout.layoutInput.constraints.toString()).put("fontScale", layout.layoutInput.density.fontScale)
                    .put("hasVisualOverflow", layout.hasVisualOverflow).put("didOverflowWidth", layout.didOverflowWidth)
                    .put("didOverflowHeight", layout.didOverflowHeight).put("multiParagraphWidth", layout.multiParagraph.width)
                    .put("multiParagraphHeight", layout.multiParagraph.height).put("lines", lines).put("characterBoxes", characterBoxes)); record()
                assertEquals(label, layout.layoutInput.text.text)
                assertTrue("Text has no lines: $label", layout.lineCount > 0)
                assertEquals(0, layout.getLineStart(0))
                assertEquals("The final visible character must be present: $label", label.length,
                    layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                assertTrue("Text node is clipped: $label", bounds.width + 1f >= layout.size.width && bounds.height + 1f >= layout.size.height)
                val textBounds = Rect(0f, 0f, minOf(bounds.width, layout.size.width.toFloat()),
                    minOf(bounds.height, layout.size.height.toFloat()))
                fun fits(rect: Rect): Boolean = rect.left >= -1f && rect.top >= -1f &&
                    rect.right <= textBounds.right + 1f && rect.bottom <= textBounds.bottom + 1f
                for (line in 0 until layout.lineCount) {
                    assertFalse("Ellipsized control: $label line $line", layout.isLineEllipsized(line))
                    assertTrue("Line outside node/layout: $label line $line", fits(Rect(layout.getLineLeft(line),
                        layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))))
                }
                for (index in label.indices) assertTrue("Character outside node/layout: $label offset $index",
                    fits(layout.getBoundingBox(index)))
                val color = layout.layoutInput.style.color
                assertEquals("Actual foreground role: $label", foregroundRole, color)
                assertEquals(1f, color.alpha, 0f); assertEquals(1f, container.alpha, 0f)
                val a = color.luminance().toDouble(); val b = container.luminance().toDouble()
                val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                assertTrue("Observed text contrast $ratio: $label", ratio >= 4.5)
                return ratio
            }
            // One semantics fetch observes the actual position and Play/Pause together while playing.
            fun playbackSample(): Pair<Long, String> {
                val nodes = compose.onAllNodes(hasTestTag("private-viewer-position") or hasTestTag("private-viewer-play")).fetchSemanticsNodes()
                val time = nodes.single { it.config[SemanticsProperties.TestTag] == "private-viewer-position" }
                    .config[SemanticsProperties.Text].joinToString { it.text }.substringBefore(" / ")
                val match = requireNotNull(Regex("([0-9]+):([0-9]{2})\\.([0-9]{3})").matchEntire(time))
                val millis = match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1_000 + match.groupValues[3].toLong()
                val label = nodes.single { it.config[SemanticsProperties.TestTag] == "private-viewer-play" }
                    .config[SemanticsProperties.Text].joinToString { it.text }
                return millis to label
            }
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = isDark }
                awaitPlayLabel(playLabel)
                val safe = safeBounds()
                reachable(compose.onNodeWithContentDescription(f.base.getString(R.string.private_viewer_back)), safe, "Back")
                reachable(compose.onNodeWithTag("private-viewer-video"), safe, "Video surface")
                reachable(compose.onNodeWithTag("private-viewer-position"), safe, "Position")
                val seek = compose.onNodeWithTag("private-viewer-seek")
                val play = compose.onNodeWithTag("private-viewer-play")
                reachable(seek, safe, "Seek"); reachable(play, safe, "Play/Pause")
                reachable(compose.onNodeWithTag("private-viewer-export"), safe, "Export")
                val playContrast = textContrast(playLabel, onPrimary, primary)
                val exportContrast = textContrast(f.base.getString(R.string.private_viewer_export), primary, surface)
                // RTL maps the right quarter to an early real position, leaving the 3s asset headroom.
                seek.performTouchInput { click(Offset(width * .75f, center.y)) }
                compose.waitUntil(10_000) { position() in 100..1_800 }
                awaitPlayLabel(playLabel)
                val seeked = position()
                play.performTouchInput { click(center) }
                var advanced = -1L
                compose.waitUntil(10_000) {
                    val sample = playbackSample()
                    (sample.second == pauseLabel && sample.first > seeked + 150).also { if (it) advanced = sample.first }
                }
                play.performTouchInput { click(center) }
                awaitPlayLabel(playLabel)
                // The production position label refreshes every 100ms independently of isPlaying.
                SystemClock.sleep(250)
                val paused = position()
                SystemClock.sleep(300)
                val stillPaused = playbackSample()
                observations.put(JSONObject().put("dark", isDark).put("seekedMillis", seeked)
                    .put("playingMillis", advanced).put("pausedMillis", paused).put("laterPausedMillis", stillPaused.first)
                    .put("laterPlayLabel", stillPaused.second).put("playContrast", playContrast)
                    .put("exportContrast", exportContrast)); record()
                assertEquals(playLabel, stillPaused.second)
                assertEquals("Explicit Pause must stop advancement", paused, stillPaused.first)
                assertTrue(advanced > seeked + 150)
                assertTrue("Pause must remain intermediate, not silently accept the 3s natural end: $paused", paused in advanced..2_900L)
                compose.runOnIdle { assertEquals(0, exports); assertEquals(0, backs); assertEquals(0, unlocks) }
            }
            f.repository.revokeSession(); awaitTag("private-viewer-locked")
            compose.onNodeWithTag("private-viewer-video").assertDoesNotExist()
            compose.onNodeWithTag("private-viewer-play").assertDoesNotExist()
            compose.onNodeWithTag("private-viewer-seek").assertDoesNotExist()
            compose.onNodeWithTag("private-viewer-export").assertDoesNotExist()
            val safe = safeBounds()
            reachable(compose.onNodeWithTag("private-viewer-locked"), safe, "Locked message")
            val unlock = compose.onNodeWithText(unlockLabel)
            reachable(unlock, safe, "Unlock")
            val unlockContrast = textContrast(unlockLabel, onPrimary, primary)
            unlock.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(1, unlocks); assertEquals(0, backs); assertEquals(0, exports) }
            compose.onNodeWithTag("private-viewer-locked").assertExists()
            compose.onNodeWithTag("private-viewer-video").assertDoesNotExist()
            observations.put(JSONObject().put("masked", true).put("unlockContrast", unlockContrast)); record()
            compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() }
            compose.runOnIdle {
                assertEquals(requireNotNull(initialSecure), activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            passed = true
        } finally {
            f.repository.disposeSession()
            try {
                if (composed && !disposed.get()) { compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() } }
            } finally { f.finish(passed) }
        }
    }

    /** Actual platform dynamic light/dark palettes; retained compact RTL/200% encrypted-photo flow.
     * Only the observed text-role pairs are measured. No wallpaper/global settings or video matrix.
     */
    @androidx.annotation.RequiresApi(31)
    @Test fun photoViewerDynamicPaletteAt200PercentRtlKeepsControlsReachableAndInsetSafe() = runBlocking {
        // This case must exercise actual Android dynamic resources, not the API30 fallback.
        check(android.os.Build.VERSION.SDK_INT >= 31) { "Dynamic palette fixture requires API31+" }
        val f = Fixture(); var passed = false; var composed = false
        val mounted = mutableStateOf(true); val disposed = AtomicBoolean(false)
        val dark = mutableStateOf(false)
        val activityRef = AtomicReference<Activity?>()
        var initialSecure: Boolean? = null
        var density = 0f; var observedScale = 0f; var observedDirection = LayoutDirection.Ltr
        var background = ComposeColor.Unspecified
        var primary = ComposeColor.Unspecified; var onPrimary = ComposeColor.Unspecified
        var exports = 0; var backs = 0; var unlocks = 0
        val paletteObservations = JSONArray(); val textObservations = JSONArray()
        try {
            val image = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 128) image.setPixel(x, y, if (x < 64) Color.RED else Color.BLUE)
            val source = f.createSource("photo.png") { out -> try { check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { image.recycle() } }
            val id = f.import(source, "image/png", "image", 128, 64, 0)
            compose.setContent { if (mounted.value) {
                val activity = requireNotNull(unwrapActivity(LocalContext.current))
                // Capture before the viewer's DisposableEffect adds FLAG_SECURE.
                remember(activity) {
                    initialSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    activityRef.set(activity)
                    Unit
                }
                DisposableEffect(Unit) { onDispose { disposed.set(true) } }
                val localDensity = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(localDensity, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    UGalleryTheme(darkTheme = dark.value, dynamicColor = true) {
                        val currentDensity = LocalDensity.current
                        val currentDirection = LocalLayoutDirection.current
                        val colors = MaterialTheme.colorScheme
                        SideEffect {
                            density = currentDensity.density; observedScale = currentDensity.fontScale
                            observedDirection = currentDirection
                            background = colors.surface; primary = colors.primary; onPrimary = colors.onPrimary
                        }
                        Box(Modifier.requiredSize(360.dp, 640.dp).clipToBounds().testTag("private-dynamic-test-viewport")) {
                            PrivateMediaViewerContent(f.repository, id, { backs++ }, { unlocks++ }, { exports++ })
                        }
                    }
                }
            } }
            composed = true
            awaitTag("private-viewer-photo")
            val photoLabel = f.base.getString(R.string.private_viewer_photo)
            val resetLabel = f.base.getString(R.string.private_viewer_reset)
            val exportLabel = f.base.getString(R.string.private_viewer_export)
            val unlockLabel = f.base.getString(R.string.private_viewer_unlock)
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(photoLabel).fetchSemanticsNodes().size == 1 }
            val activity = requireNotNull(activityRef.get())
            fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
            fun usableBounds(): Rect {
                val result = AtomicReference<Rect>()
                var expectedSurface = ComposeColor.Unspecified
                var expectedPrimary = ComposeColor.Unspecified
                var expectedOnPrimary = ComposeColor.Unspecified
                compose.runOnIdle {
                    val platform = if (dark.value) androidx.compose.material3.dynamicDarkColorScheme(activity)
                        else androidx.compose.material3.dynamicLightColorScheme(activity)
                    expectedSurface = platform.surface; expectedPrimary = platform.primary; expectedOnPrimary = platform.onPrimary
                    assertEquals(2f, observedScale, 0f)
                    assertEquals(LayoutDirection.Rtl, observedDirection)
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    val decor = activity.window.decorView
                    val offset = IntArray(2); decor.getLocationInWindow(offset)
                    val insets = requireNotNull(decor.rootWindowInsets)
                        .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    result.set(Rect((offset[0] + insets.left).toFloat(), (offset[1] + insets.top).toFloat(),
                        (offset[0] + decor.width - insets.right).toFloat(), (offset[1] + decor.height - insets.bottom).toFloat()))
                }
                paletteObservations.put(JSONObject().put("dark", dark.value).put("api", android.os.Build.VERSION.SDK_INT)
                    .put("surface", background.toString()).put("primary", primary.toString()).put("onPrimary", onPrimary.toString())
                    .put("platformSurface", expectedSurface.toString()).put("platformPrimary", expectedPrimary.toString())
                    .put("platformOnPrimary", expectedOnPrimary.toString()))
                f.record("dynamicPalettes", JSONObject().put("dynamicRequested", true).put("samples", paletteObservations))
                assertEquals("Actual surface must come from the platform dynamic palette", expectedSurface, background)
                assertEquals("Actual primary must come from the platform dynamic palette", expectedPrimary, primary)
                assertEquals("Actual onPrimary must come from the platform dynamic palette", expectedOnPrimary, onPrimary)
                val viewport = compose.onNodeWithTag("private-dynamic-test-viewport").fetchSemanticsNode().boundsInWindow
                assertEquals("Viewport must actually be 360dp, not silently clipped", 360f * density, viewport.width, 1f)
                assertEquals("Viewport must actually be 640dp, not silently clipped", 640f * density, viewport.height, 1f)
                val safe = result.get()
                return Rect(maxOf(viewport.left, safe.left), maxOf(viewport.top, safe.top),
                    minOf(viewport.right, safe.right), minOf(viewport.bottom, safe.bottom))
            }
            fun reachable(node: SemanticsNodeInteraction, safe: Rect, label: String): Rect {
                node.assertIsDisplayed().assertIsEnabled()
                val bounds = node.fetchSemanticsNode().boundsInWindow
                assertTrue("$label needs positive bounds: $bounds", bounds.width > 0 && bounds.height > 0)
                assertTrue("$label clipped or behind a system inset: $bounds vs $safe",
                    bounds.left >= safe.left - 1f && bounds.top >= safe.top - 1f &&
                        bounds.right <= safe.right + 1f && bounds.bottom <= safe.bottom + 1f)
                return bounds
            }
            fun observedTextContrast(label: String, expectedForeground: ComposeColor, container: ComposeColor): Double {
                val text = compose.onNodeWithText(label, useUnmergedTree = true)
                text.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> check(action(layouts)) }
                val layout = layouts.single()
                val bounds = text.fetchSemanticsNode().boundsInWindow
                val measurement = JSONObject().put("label", label).put("dark", dark.value)
                    .put("effectiveForeground", layout.layoutInput.style.color.toString())
                    .put("expectedForeground", expectedForeground.toString()).put("container", container.toString())
                    .put("bounds", rectJson(bounds)).put("sizeWidth", layout.size.width).put("sizeHeight", layout.size.height)
                    .put("overflowWidth", layout.didOverflowWidth).put("overflowHeight", layout.didOverflowHeight)
                    .put("paragraphWidth", layout.multiParagraph.width).put("paragraphHeight", layout.multiParagraph.height)
                    .put("constraints", layout.layoutInput.constraints.toString()).put("lines", layout.lineCount)
                    .put("fontScale", layout.layoutInput.density.fontScale).put("style", layout.layoutInput.style.toString())
                    .put("lineBounds", JSONArray((0 until layout.lineCount).map { line -> JSONArray(listOf(
                        layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))) }))
                textObservations.put(measurement)
                f.record("dynamicText", JSONObject().put("measurements", textObservations))
                // GetTextLayoutResult's aggregate width flag can include paragraph rounding
                // beyond the visible glyph extents. Assert actual complete laid-out content.
                assertTrue("Control text is clipped: $label", bounds.width + 1f >= layout.size.width && bounds.height + 1f >= layout.size.height)
                assertTrue(layout.lineCount > 0)
                assertEquals("Control text must be complete: $label", label.length, layout.getLineEnd(layout.lineCount - 1))
                fun contained(rect: Rect) = rect.left >= -1f && rect.top >= -1f &&
                    rect.right <= layout.size.width + 1f && rect.bottom <= layout.size.height + 1f &&
                    rect.right <= bounds.width + 1f && rect.bottom <= bounds.height + 1f
                for (line in 0 until layout.lineCount) {
                    assertFalse("Control text is ellipsized: $label", layout.isLineEllipsized(line))
                    assertTrue("Control line clipped: $label", contained(Rect(layout.getLineLeft(line), layout.getLineTop(line),
                        layout.getLineRight(line), layout.getLineBottom(line))))
                }
                for (offset in label.indices) assertTrue("Control glyph clipped: $label at $offset", contained(layout.getBoundingBox(offset)))
                val foreground = layout.layoutInput.style.color
                assertEquals("Effective text must use its matching Material role: $label", expectedForeground, foreground)
                assertEquals(1f, foreground.alpha, 0f); assertEquals(1f, container.alpha, 0f)
                val a = foreground.luminance().toDouble(); val b = container.luminance().toDouble()
                val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                measurement.put("foregroundLuminance", a).put("containerLuminance", b).put("contrast", ratio)
                f.record("dynamicText", JSONObject().put("measurements", textObservations))
                assertTrue("Observed $label contrast $ratio is below 4.5", ratio >= 4.5)
                return ratio
            }
            val observations = JSONArray()
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = isDark }
                val safe = usableBounds()
                val back = compose.onNodeWithContentDescription(f.base.getString(R.string.private_viewer_back))
                reachable(back, safe, "Back")
                val slider = compose.onNodeWithTag("private-viewer-zoom")
                reachable(slider, safe, "Zoom")
                val reset = compose.onNodeWithText(resetLabel)
                reachable(reset, safe, "Reset")
                reachable(compose.onNodeWithTag("private-viewer-export"), safe, "Export")
                val photo = reachable(compose.onNodeWithTag("private-viewer-photo"), safe, "Photo")
                compose.onNodeWithContentDescription(photoLabel).assertIsDisplayed()
                val resetContrast = observedTextContrast(resetLabel, primary, background)
                val exportContrast = observedTextContrast(exportLabel, primary, background)
                assertEquals(1f, zoom(), .01f)
                // In RTL the right side is the low end. A middle tap changes the real Slider.
                slider.performTouchInput { click(center) }
                compose.waitUntil(10_000) { zoom() > 1.5f }
                val changedZoom = zoom()
                reset.performTouchInput { click(center) }
                compose.waitUntil(10_000) { kotlin.math.abs(zoom() - 1f) < .01f }
                compose.runOnIdle { assertEquals(0, exports); assertEquals(0, backs); assertEquals(0, unlocks) }
                observations.put(JSONObject().put("dark", isDark).put("safeWindowBounds", rectJson(safe))
                    .put("photoWindowBounds", rectJson(photo)).put("zoomChanged", changedZoom).put("resetZoom", zoom())
                    .put("resetContrast", resetContrast).put("exportContrast", exportContrast))
                f.record("dynamicAdaptive", JSONObject().put("fontScale", observedScale).put("layoutDirection", "Rtl")
                    .put("widthDp", 360).put("heightDp", 640).put("dynamicRequested", true).put("active", observations))
            }
            f.repository.revokeSession()
            awaitTag("private-viewer-locked")
            val lockedObservations = JSONArray()
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark.value = isDark }
                val safe = usableBounds()
                compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
                compose.onNodeWithContentDescription(photoLabel).assertDoesNotExist()
                compose.onNodeWithTag("private-viewer-export").assertDoesNotExist()
                reachable(compose.onNodeWithTag("private-viewer-locked"), safe, "Locked message")
                val unlock = compose.onNodeWithText(unlockLabel)
                reachable(unlock, safe, "Unlock")
                val contrast = observedTextContrast(unlockLabel, onPrimary, primary)
                lockedObservations.put(JSONObject().put("dark", isDark).put("unlockContrast", contrast))
            }
            compose.onNodeWithText(unlockLabel).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(1, unlocks); assertEquals(0, backs); assertEquals(0, exports) }
            compose.onNodeWithTag("private-viewer-locked").assertExists()
            compose.onNodeWithTag("private-viewer-photo").assertDoesNotExist()
            compose.runOnIdle { mounted.value = false }
            compose.waitUntil(10_000) { disposed.get() }
            compose.runOnIdle {
                assertEquals(requireNotNull(initialSecure), activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            f.record("dynamicAdaptive", JSONObject().put("fontScale", observedScale).put("layoutDirection", "Rtl")
                .put("widthDp", 360).put("heightDp", 640).put("dynamicRequested", true).put("active", observations).put("locked", lockedObservations)
                .put("unlockCallbacks", unlocks).put("exports", exports).put("secureFlagRestored", true))
            passed = true
        } finally {
            f.repository.disposeSession()
            try {
                if (composed && !disposed.get()) { compose.runOnIdle { mounted.value = false }; compose.waitUntil(10_000) { disposed.get() } }
            } finally { f.finish(passed) }
        }
    }

    @Test fun cleanupOwnedViewerFixture() { cleanup(ApplicationProvider.getApplicationContext(), fixtureId()) }

    private fun awaitTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun zoom() = compose.onNodeWithTag("private-viewer-zoom").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun awaitPlayLabel(label: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("private-viewer-play") and hasAnyDescendant(hasText(label))).fetchSemanticsNodes().size == 1 ||
            compose.onAllNodes(hasTestTag("private-viewer-play") and hasText(label)).fetchSemanticsNodes().size == 1
    }
    private fun position(): Long {
        val text = compose.onNodeWithTag("private-viewer-position").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.substringBefore(" / ")
        val match = requireNotNull(Regex("([0-9]+):([0-9]{2})\\.([0-9]{3})").matchEntire(text))
        return match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1000 + match.groupValues[3].toLong()
    }

    private class Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = fixtureId()
        val root = File(base.cacheDir.canonicalFile, "private-viewer-flow-$id")
        val receipt = File(base.filesDir.canonicalFile, "private-viewer-flow-$id.json")
        val name = "private-viewer-flow-$id.db"
        val masterAlias = "ugallery.privatealbum.fixture.viewer.$id"
        val indexAlias = "ugallery.privatealbum.index.v1." + digest(name.toByteArray())
        val context: Context
        val database: PrivateAlbumDatabase
        val repository: PrivateAlbumRepository
        val originals = linkedMapOf<File, String>()
        val publicBefore: List<String>
        init {
            check(base.packageName == "com.ugallery.feature.privatealbum.test")
            check(!present(root) && listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).none(::present))
            check(!keys().containsAlias(masterAlias) && !keys().containsAlias(indexAlias))
            publicBefore = outputs(base)
            check(root.mkdir())
            write(receipt, JSONObject().put("fixture", id).put("root", root.path).put("masterAlias", masterAlias).put("indexAlias", indexAlias).put("status", "STARTED"))
            context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdir()) }
                override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
                override fun getNoBackupFilesDir() = File(root, "no-backup").apply { check(isDirectory || mkdir()) }
                override fun getDatabasePath(name: String) = File(File(root, "databases").apply { check(isDirectory || mkdir()) }, name)
            }
            database = PrivateAlbumDatabase.open(context, name)
            repository = PrivateAlbumRepository(context, database)
            val master = PrivateAlbumCrypto.getOrCreateMasterKey(masterAlias)
            runBlocking { repository.setup(master, masterAlias) }
        }
        fun createSource(name: String, create: (FileOutputStream) -> Unit): File = File(root, name).also {
            check(it.createNewFile()); FileOutputStream(it).use { out -> create(out); out.fd.sync() }
            originals[it] = sha(it)
            PrivatePortableJournal.syncDirectory(root)
            record("source", JSONObject().put("name", name).put("sha256", originals[it]))
        }
        suspend fun import(source: File, mime: String, kind: String, width: Int, height: Int, duration: Long): Long {
            val key = repository.requireMasterKeyBinding()
            val imported = repository.importFromUri(Uri.fromFile(source), source.name, mime, kind, width, height, duration, key.secretKey)
            assertTrue(imported.error, imported.success)
            val id = requireNotNull(imported.mediaId)
            val item = requireNotNull(database.privateMediaDao().getById(id))
            val cipher = File(item.containerPath); originals[cipher] = sha(cipher)
            record("import", JSONObject().put("mediaId", id).put("container", cipher.relativeTo(root).path).put("cipherSha256", originals[cipher]))
            return id
        }
        fun record(phase: String, value: JSONObject) {
            val json = read(receipt); json.put(phase, value); write(receipt, json)
        }
        fun finish(passed: Boolean) {
            var verified = passed
            try {
                originals.forEach { (file, hash) -> check(sha(file) == hash) }
                check(database.privateExportDao().let { runBlocking { it.list() } }.isEmpty())
                check(outputs(base) == publicBefore)
                check(!context.cacheDir.walkTopDown().any { it.isFile }) { "Plaintext cache was created" }
            } catch (failure: Throwable) {
                verified = false; record("failure", JSONObject().put("message", failure.stackTraceToString().take(8192))); throw failure
            } finally {
                database.close()
                val json = read(receipt)
                check(!json.has("inventory"))
                json.put("inventory", inventory(root)).put("databaseClosed", true).put("status", if (verified) "PASS" else "FAIL")
                    .put("sourcesIntact", originals.all { (file, hash) -> runCatching { sha(file) == hash }.getOrDefault(false) })
                    .put("outputsUnchanged", outputs(base) == publicBefore)
                write(receipt, json)
                if (verified) cleanup(base, id)
                else println("PRIVATE_VIEWER_RETAINED fixture=$id receipt=$receipt")
            }
        }
    }

    companion object {
        private tailrec fun unwrapActivity(context: Context): Activity? = when (context) {
            is Activity -> context
            is ContextWrapper -> if (context.baseContext !== context) unwrapActivity(context.baseContext) else null
            else -> null
        }
        private fun fixtureId(): String = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also { require(UUID.fromString(it).toString() == it) }
        private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha(file: File): String { regular(file); return file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; if (n > 0) md.update(buf, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        } }
        private fun present(file: File): Boolean = try { Os.lstat(file.path); true } catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) false else throw e }
        private fun regular(file: File) { check(file.canonicalFile == file.absoluteFile && OsConstants.S_ISREG(Os.lstat(file.path).st_mode)) }
        private fun write(file: File, json: JSONObject) {
            val atomic = AtomicFile(file); val out = atomic.startWrite()
            try { out.write(json.toString().toByteArray()); out.fd.sync(); atomic.finishWrite(out); PrivatePortableJournal.syncDirectory(requireNotNull(file.parentFile)) }
            catch (e: Throwable) { atomic.failWrite(out); throw e }
        }
        private fun read(file: File): JSONObject { regular(file); return JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }
        private fun inventory(root: File): JSONArray = JSONArray().also { result ->
            if (present(root)) root.walkTopDown().sortedBy { it.relativeTo(root).path }.forEach { file ->
                check(file.canonicalFile == file.absoluteFile)
                val stat = Os.lstat(file.path); val dir = OsConstants.S_ISDIR(stat.st_mode)
                check(dir || OsConstants.S_ISREG(stat.st_mode))
                result.put(JSONObject().put("path", file.relativeTo(root).invariantSeparatorsPath).put("directory", dir)
                    .put("size", if (dir) 0 else file.length()).put("sha256", if (dir) JSONObject.NULL else sha(file)))
            }
        }
        private fun outputs(context: Context): List<String> {
            val result = mutableListOf<String>()
            for (uri in listOf(MediaStore.Images.Media.getContentUri("external_primary"), MediaStore.Video.Media.getContentUri("external_primary"))) {
                val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED)
                val args = Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE); putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                requireNotNull(context.contentResolver.query(uri, columns, args, null)).use { c ->
                    while (c.moveToNext()) result.add(uri.toString() + (columns.indices).joinToString(":") { if (c.isNull(it)) "NULL" else c.getString(it) })
                }
            }
            return result.sorted()
        }
        private fun cleanup(base: Context, id: String) {
            check(base.packageName == "com.ugallery.feature.privatealbum.test")
            val root = File(base.cacheDir.canonicalFile, "private-viewer-flow-$id")
            val file = File(base.filesDir.canonicalFile, "private-viewer-flow-$id.json")
            val json = read(file)
            check(json.getString("fixture") == id && json.getString("root") == root.path && json.getBoolean("databaseClosed") && !json.optBoolean("cleanupComplete"))
            val master = "ugallery.privatealbum.fixture.viewer.$id"
            val index = "ugallery.privatealbum.index.v1." + digest("private-viewer-flow-$id.db".toByteArray())
            check(json.getString("masterAlias") == master && json.getString("indexAlias") == index)
            if (json.has("source")) {
                val source = json.getJSONObject("source")
                val leaf = source.getString("name"); check(leaf == "photo.png" || leaf == "video.mp4")
                check(sha(File(root, leaf)) == source.getString("sha256"))
            }
            if (json.has("import")) {
                val imported = json.getJSONObject("import")
                val relative = imported.getString("container")
                check(!File(relative).isAbsolute && relative.startsWith("files/private-album/") && relative.split('/').none { it == ".." || it == "." })
                check(sha(File(root, relative)) == imported.getString("cipherSha256"))
            }
            val recorded = json.getJSONArray("inventory"); val actual = inventory(root)
            check(recorded.toString() == actual.toString()) { "Changed fixture retained" }
            val entries = (0 until recorded.length()).map { recorded.getJSONObject(it) }
            entries.sortedByDescending { it.getString("path").length }.forEach { entry ->
                val relative = entry.getString("path")
                check(!File(relative).isAbsolute && relative.split('/').none { it == ".." || it == "." })
                val target = if (relative.isEmpty()) root else File(root, relative)
                check(target.canonicalFile == target.absoluteFile)
                if (!entry.getBoolean("directory")) check(target.length() == entry.getLong("size") && sha(target) == entry.getString("sha256"))
                else check(requireNotNull(target.listFiles()).isEmpty())
                check(target.delete()); PrivatePortableJournal.syncDirectory(requireNotNull(target.parentFile))
            }
            check(!present(root)); keys().deleteEntry(master); keys().deleteEntry(index)
            check(!keys().containsAlias(master) && !keys().containsAlias(index))
            json.put("cleanupComplete", true); write(file, json)
            println("PRIVATE_VIEWER_CLEANUP $json")
        }
    }
}
