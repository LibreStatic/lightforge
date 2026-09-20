package com.ugallery.app

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ugallery.core.editing.video.NormalizedPoint
import com.ugallery.core.editing.video.VideoAnnotationLayer
import com.ugallery.core.editing.video.VideoAnnotationShape
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** One real owner-UID READ revocation, not source disappearance or process restoration. */
class ExternalVideoAccessAppDeviceTest {
    @Test fun revokedExternalVideoRetainsDraftUntilExplicitRetry(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == Consumer)
        val device = UiDevice.getInstance(instrumentation)
        val doc = UUID.randomUUID().toString()
        val evidence = File(context.filesDir, "external-video-access-$doc").apply { check(mkdir()) }
        var success = false
        var originalHash: String? = null
        var model: GalleryViewModel? = null
        fun shell(command: String): String {
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            File(evidence, "commands.txt").appendText("COMMAND $command\nOUTPUT $output\n")
            return output
        }
        suspend fun control(action: String): JSONObject {
            val nonce = UUID.randomUUID().toString()
            val expected = if (action == "cleanup") " --es expectedSha256 ${checkNotNull(originalHash)}" else ""
            shell("am start -f 0x18000000 -n $Owner/.ControlActivity --es action $action --es doc $doc --es nonce $nonce$expected")
            val receipt = withTimeout(15_000) {
                var value: JSONObject? = null
                while (value == null) {
                    val raw = shell("run-as $Owner cat files/control-$nonce.json")
                    value = runCatching { JSONObject(raw) }.getOrNull()
                    if (value == null) delay(100)
                }
                value
            }
            assertEquals(action, receipt.getString("action")); assertEquals(doc, receipt.getString("doc"))
            assertEquals(nonce, receipt.getString("nonce")); assertEquals(Consumer, receipt.getString("targetPackage"))
            assertEquals(context.applicationInfo.uid, receipt.getInt("targetUid"))
            assertNotEquals(context.applicationInfo.uid, receipt.getInt("uid"))
            File(evidence, "$action-$nonce.json").writeText(receipt.toString(2))
            return receipt
        }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun resumeApp(editUri: Uri? = null) {
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                action = if (editUri == null) Intent.ACTION_MAIN else Intent.ACTION_EDIT
                if (editUri != null) setDataAndType(editUri, "video/mp4")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // No READ flag: this consumer cannot grant itself access after owner revocation.
            })
        }
        suspend fun resumed(): MainActivity = withTimeout(20_000) {
            var activity: MainActivity? = null
            while (activity == null) {
                main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull() }
                if (activity == null) delay(50)
            }
            checkNotNull(activity)
        }
        fun awaitTag(tag: String) { assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 20_000)) }
        val drawLabel = context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_draw)
        val rectangleLabel = context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_annotation_rectangle)
        val blueLabel = context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_annotation_color_blue)
        val tabs = listOf(
            com.ugallery.feature.videoeditor.R.string.video_editor_speed,
            com.ugallery.feature.videoeditor.R.string.video_editor_audio,
            com.ugallery.feature.videoeditor.R.string.video_editor_music,
            com.ugallery.feature.videoeditor.R.string.video_editor_color,
            com.ugallery.feature.videoeditor.R.string.video_editor_transform,
            com.ugallery.feature.videoeditor.R.string.video_editor_draw,
            com.ugallery.feature.videoeditor.R.string.video_editor_export,
        ).map(context::getString)
        val shapes = listOf(
            com.ugallery.feature.videoeditor.R.string.video_editor_annotation_freehand,
            com.ugallery.feature.videoeditor.R.string.video_editor_annotation_line,
            com.ugallery.feature.videoeditor.R.string.video_editor_annotation_arrow,
            com.ugallery.feature.videoeditor.R.string.video_editor_annotation_rectangle,
        ).map(context::getString)
        fun actionNode(label: UiObject2): UiObject2 = generateSequence(label) { it.parent }
            .takeWhile { !it.isScrollable }.firstOrNull { it.isClickable || it.isCheckable }
            ?: error("Label has no actionable control: ${label.text ?: label.contentDescription}")
        fun usable(node: UiObject2): Boolean = node.isEnabled &&
            node.visibleBounds.width() >= (32 * context.resources.displayMetrics.density).toInt() &&
            node.visibleBounds.height() > 0
        fun horizontalChoice(label: String, groupLabels: List<String>): UiObject2 {
            repeat(6) {
                device.findObject(By.text(label))?.let { text ->
                    val action = actionNode(text)
                    if (usable(action) && !text.visibleBounds.isEmpty) return action
                }
                // Scroll only the real horizontal row containing known labels of this group.
                val row = groupLabels.asSequence().mapNotNull { device.findObject(By.text(it)) }
                    .mapNotNull { text -> generateSequence(text.parent) { it.parent }.firstOrNull { it.isScrollable } }
                    .firstOrNull { !it.visibleBounds.isEmpty }
                    ?: error("No visible choice row for $label")
                row.scroll(Direction.RIGHT, .6f)
                device.waitForIdle()
            }
            error("Choice not visible after bounded horizontal scrolling: $label")
        }
        fun blueSwatch(): UiObject2 {
            repeat(5) {
                device.wait(Until.findObject(By.desc(blueLabel)), 2_000)?.let { label ->
                    val action = actionNode(label)
                    if (usable(action)) return action
                    val scroll = generateSequence(label.parent) { it.parent }.firstOrNull { it.isScrollable && !it.visibleBounds.isEmpty }
                        ?: error("No visible annotation scroll container for $blueLabel")
                    scroll.scroll(Direction.DOWN, .5f)
                    device.waitForIdle()
                } ?: run {
                    // If the off-screen swatch is omitted, reacquire its outer vertical controls
                    // container from a known shape chip, beyond that chip's horizontal row.
                    val shape = shapes.asSequence().mapNotNull { device.findObject(By.text(it)) }.firstOrNull()
                        ?: error("No annotation shape anchor for $blueLabel")
                    val row = generateSequence(shape.parent) { it.parent }.firstOrNull { it.isScrollable }
                        ?: error("No shape row for $blueLabel")
                    val controls = generateSequence(row.parent) { it.parent }.firstOrNull { it.isScrollable && !it.visibleBounds.isEmpty }
                        ?: error("No annotation controls container for $blueLabel")
                    controls.scroll(Direction.DOWN, .5f)
                    device.waitForIdle()
                }
            }
            error("Annotation color remains clipped: $blueLabel")
        }
        fun assertSelected(node: UiObject2, label: String) {
            assertTrue("Expected selected UI control: $label", node.isSelected || (node.isCheckable && node.isChecked))
        }
        fun assertToolSelection(phase: String) {
            assertSelected(horizontalChoice(drawLabel, tabs), drawLabel)
            assertSelected(horizontalChoice(rectangleLabel, shapes), rectangleLabel)
            assertSelected(blueSwatch(), blueLabel)
            File(evidence, "tools-$phase.json").writeText(JSONObject()
                .put("doc", doc).put("phase", phase).put("tab", drawLabel)
                .put("shape", rectangleLabel).put("color", blueLabel).put("allSelected", true).toString(2))
        }
        fun hash(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }
        try {
            val prepared = control("prepare")
            originalHash = prepared.getString("sha256")
            assertEquals(FixtureHash, originalHash)
            val uri = Uri.parse(prepared.getString("uri"))
            assertEquals("content://$Authority/document/$doc", uri.toString())
            // The receiver exists before owner grants; do not restart instrumentation after granting.
            resumeApp(); resumed()
            control("edit")
            val activity = resumed()
            val taskId = activity.taskId
            lateinit var vm: GalleryViewModel
            main { vm = ViewModelProvider(activity)[GalleryViewModel::class.java] }
            model = vm
            withTimeout(30_000) { while (vm.videoEditor.value?.source?.uri != uri) delay(50) }
            awaitTag("video-editor-screen")
            assertEquals(originalHash, hash(uri))
            val duration = checkNotNull(vm.videoEditor.value).content.durationMillis
            assertTrue(duration >= 2_500)
            val start = duration / 5
            val end = duration * 4 / 5
            val layer = VideoAnnotationLayer(shape = VideoAnnotationShape.Rectangle,
                points = listOf(NormalizedPoint(.2f, .2f), NormalizedPoint(.7f, .7f)), startMillis = start, endMillis = end)
            main { vm.setVideoTrim(start, end); vm.addVideoAnnotation(layer) }
            val edited = checkNotNull(vm.videoEditor.value)
            val recipe = edited.recipe
            val baseline = edited.baselineRecipe
            assertEquals(start, recipe.startMillis); assertEquals(end, recipe.endMillis)
            assertEquals(listOf(layer), recipe.annotations)
            val jobs = vm.videoExports.value.map { it.id }.toSet()
            // These are editor-local UI settings, distinct from the already-created recipe layer.
            horizontalChoice(drawLabel, tabs).click(); device.waitForIdle()
            horizontalChoice(rectangleLabel, shapes).click(); device.waitForIdle()
            blueSwatch().click(); device.waitForIdle()
            assertToolSelection("before-home")
            assertEquals(recipe, checkNotNull(vm.videoEditor.value).recipe)
            assertTrue(device.pressHome())
            control("revoke")
            var denied = false
            try { context.contentResolver.openFileDescriptor(uri, "r")?.close() }
            catch (_: SecurityException) { denied = true }
            assertTrue("OS must deny unchanged existing source to consumer UID", denied)
            val inspected = control("inspect")
            assertEquals(originalHash, inspected.getString("sha256"))
            assertEquals(prepared.getLong("size"), inspected.getLong("size"))
            resumeApp(); assertEquals(taskId, resumed().taskId)
            awaitTag("external-video-access-blocked")
            fun assertPreserved(blocked: Boolean) {
                val current = checkNotNull(vm.videoEditor.value)
                assertEquals(edited.id, current.id); assertEquals(recipe, current.recipe)
                assertEquals(baseline, current.baselineRecipe); assertEquals(blocked, current.externalAccessBlocked)
                assertFalse(current.content.isExporting); assertNull(current.content.annotationTrackingProgress)
                assertEquals(jobs, vm.videoExports.value.map { it.id }.toSet())
            }
            assertPreserved(true)
            // Guard methods directly on the real Activity VM; unavailable UI cannot invoke these controls.
            main { vm.saveVideoEditorCopy(); vm.startVideoAnnotationTracking(layer.id, start) }
            instrumentation.waitForIdleSync()
            assertPreserved(true)
            resumeApp(uri); assertEquals(taskId, resumed().taskId)
            awaitTag("external-video-access-blocked"); assertPreserved(true)
            control("grant")
            assertEquals(originalHash, hash(uri))
            resumeApp(); assertEquals(taskId, resumed().taskId)
            awaitTag("external-video-access-blocked"); assertPreserved(true)
            device.findObject(By.res("external-video-access-retry")).click()
            awaitTag("video-editor-screen")
            withTimeout(20_000) { while (vm.videoEditor.value?.externalAccessBlocked != false) delay(50) }
            assertPreserved(false)
            val play = context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_play)
            assertTrue("Reopened editor remains paused", device.wait(Until.hasObject(By.desc(play)), 20_000))
            // Inspection may scroll but must not reselect a tab/tool to conceal lost saved state.
            assertToolSelection("after-retry")
            assertPreserved(false)
            assertTrue("Inspecting restored tools must not start playback", device.hasObject(By.desc(play)))
            // Annotation history must survive the blocked interval, not merely current recipe equality.
            main { vm.undoVideoAnnotation() }
            assertTrue(checkNotNull(vm.videoEditor.value).recipe.annotations.isEmpty())
            main { vm.redoVideoAnnotation() }
            assertPreserved(false)
            assertEquals(originalHash, hash(uri))
            File(evidence, "result.json").writeText(JSONObject().put("state", "PASS").put("doc", doc)
                .put("uri", uri).put("taskId", taskId).put("sessionId", edited.id).put("sha256", originalHash)
                .put("readDeniedByOs", true).put("explicitRetry", true)
                .put("annotationTabRetained", true).put("rectangleToolRetained", true).put("blueToolRetained", true).toString(2))
            success = true
        } finally {
            if (success) {
                // Close our editor before exact hash-guarded owner cleanup; never delete provider files directly.
                main { model?.clearExternal() }
                val cleanup = control("cleanup")
                check(cleanup.getBoolean("deleted"))
                File(evidence, "cleanup.json").writeText(cleanup.toString(2))
            } else {
                android.util.Log.e("ExternalVideoAccessFixture", "RETAINED doc=$doc sha256=$originalHash evidence=${evidence.path}")
                device.takeScreenshot(File(evidence, "failure.png"))
                device.dumpWindowHierarchy(File(evidence, "failure.xml"))
            }
        }
    }
    private companion object {
        const val Consumer = "com.ugallery.app.pdfacceptance"
        const val Owner = "com.ugallery.mediaprovider.fixture"
        const val Authority = "com.ugallery.mediaprovider.fixture.documents"
        const val FixtureHash = "ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861"
    }
}
