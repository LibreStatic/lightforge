package com.librestatic.lightforge

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.librestatic.lightforge.core.editing.video.NormalizedPoint
import com.librestatic.lightforge.core.editing.video.VideoAnnotationLayer
import com.librestatic.lightforge.core.editing.video.VideoAnnotationShape
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Revokes real owner-UID READ while the real tracker is held at its first completed-frame progress callback. */
class ExternalVideoTrackingAccessAppDeviceTest {
    @Test fun revokedReadWhileTrackingRejectsBackgroundResultAndPreservesHistory(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == Consumer)
        val device = UiDevice.getInstance(instrumentation)
        val doc = UUID.randomUUID().toString()
        val evidence = File(context.filesDir, "external-video-tracking-$doc").apply { check(mkdir()) }
        var success = false
        var originalHash: String? = null
        var model: GalleryViewModel? = null
        val firstProgress = CountDownLatch(1)
        val releaseTracking = CountDownLatch(1)
        val observedFirst = AtomicBoolean(false)
        val callbackFailure = AtomicReference<Throwable?>(null)
        val observedProgress = AtomicReference<Float?>(null)
        var ownedTrackingJob: Job? = null
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
        fun hash(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }
        fun outputInventory(): List<List<String?>> {
            val columns = arrayOf("_id", "mime_type", "_display_name", "relative_path", "owner_package_name",
                "generation_added", "generation_modified", "_size", "is_pending")
            return checkNotNull(context.contentResolver.query(Uri.parse("content://media/external/file?includePending=1"),
                columns, "owner_package_name=?", arrayOf(Consumer), "_id ASC")).use { cursor ->
                buildList { while (cursor.moveToNext()) add(columns.map { name ->
                    val index = cursor.getColumnIndexOrThrow(name)
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }) }
            }
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
            val jobs = vm.videoExports.value.toList()
            check(jobs.none { it.isActive }) { "Prior export still active; do not mix work with this fixture" }
            val outputs = outputInventory()
            File(evidence, "output-inventory-before.json").writeText(org.json.JSONArray(outputs.map { org.json.JSONArray(it) }).toString(2))
            fun assertPreserved(blocked: Boolean) {
                val current = checkNotNull(vm.videoEditor.value)
                assertEquals(edited.id, current.id); assertEquals(recipe, current.recipe)
                assertEquals(baseline, current.baselineRecipe); assertEquals(blocked, current.externalAccessBlocked)
                assertFalse(current.content.isExporting); assertNull(current.content.annotationTrackingProgress)
                assertEquals(jobs, vm.videoExports.value)
                assertEquals(outputs, outputInventory())
            }
            main {
                vm.startVideoAnnotationTracking(layer.id, start) { progress ->
                    if (observedFirst.compareAndSet(false, true)) {
                        try {
                            check(Looper.myLooper() != Looper.getMainLooper()) { "Tracking observation ran on main thread" }
                            check(progress.isFinite() && progress > 0f && progress < 1f) { "Not an active real tracking progress: $progress" }
                            observedProgress.set(progress)
                            firstProgress.countDown()
                            check(releaseTracking.await(60, TimeUnit.SECONDS)) { "Tracking gate expired; no implicit retry" }
                        } catch (failure: Throwable) {
                            callbackFailure.set(failure)
                            firstProgress.countDown()
                            throw failure
                        }
                    }
                }
                // Capture ownership even if waiting for the first callback later fails.
                val field = GalleryViewModel::class.java.getDeclaredField("videoAnnotationTrackingJob").apply { isAccessible = true }
                ownedTrackingJob = field.get(vm) as? Job
            }
            assertTrue("Real tracker never produced first progress", withContext(Dispatchers.IO) {
                firstProgress.await(25, TimeUnit.SECONDS)
            })
            assertNull("Real progress callback failed", callbackFailure.get())
            main {
                val field = GalleryViewModel::class.java.getDeclaredField("videoAnnotationTrackingJob").apply { isAccessible = true }
                assertSame("A different operation replaced the held tracker", ownedTrackingJob, field.get(vm))
            }
            val tracking = checkNotNull(ownedTrackingJob)
            assertTrue("Real tracking job must still own the held operation", tracking.isActive)
            assertFalse(tracking.isCompleted)
            assertEquals(recipe, checkNotNull(vm.videoEditor.value).recipe)
            File(evidence, "tracking-progress.json").writeText(JSONObject()
                .put("progress", observedProgress.get()).put("realTracker", true).put("ioThread", true)
                .put("sessionId", edited.id).toString(2))
            assertTrue(device.pressHome())
            suspend fun requireStopped() {
                withTimeout(15_000) {
                    var stopped = false
                    while (!stopped) {
                        main { stopped = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.STOPPED).contains(activity) }
                        if (!stopped) delay(50)
                    }
                }
                assertFalse(activity.isDestroyed)
            }
            requireStopped()
            control("revoke")
            var denied = false
            try { context.contentResolver.openFileDescriptor(uri, "r")?.close() }
            catch (_: SecurityException) { denied = true }
            assertTrue("OS must deny unchanged existing source to consumer UID", denied)
            val inspected = control("inspect")
            assertEquals(originalHash, inspected.getString("sha256"))
            assertEquals(prepared.getLong("size"), inspected.getLong("size"))
            requireStopped()
            // Do not trigger onForeground: the running operation must reject its own revoked source.
            assertFalse(checkNotNull(vm.videoEditor.value).externalAccessBlocked)
            releaseTracking.countDown()
            withTimeout(25_000) { tracking.join() }
            assertTrue(tracking.isCompleted)
            assertNull("Tracking gate failed instead of exercising revocation", callbackFailure.get())
            requireStopped()
            assertPreserved(true)
            File(evidence, "output-inventory-after-background.json").writeText(org.json.JSONArray(outputInventory().map { org.json.JSONArray(it) }).toString(2))
            File(evidence, "background-result.json").writeText(JSONObject().put("completed", tracking.isCompleted)
                .put("recipeUnchanged", true).put("baselineUnchanged", true).put("blockedBeforeForeground", true)
                .put("exportsUnchanged", true).put("mediaInventoryUnchanged", true).toString(2))
            resumeApp(); assertEquals(taskId, resumed().taskId)
            awaitTag("external-video-access-blocked"); assertPreserved(true)
            control("grant")
            assertEquals(originalHash, hash(uri))
            resumeApp(); assertEquals(taskId, resumed().taskId)
            awaitTag("external-video-access-blocked"); assertPreserved(true)
            device.findObject(By.res("external-video-access-retry")).click()
            awaitTag("video-editor-screen")
            withTimeout(20_000) { while (vm.videoEditor.value?.externalAccessBlocked != false) delay(50) }
            assertPreserved(false)
            val play = context.getString(com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_play)
            assertTrue("Reopened editor remains paused", device.wait(Until.hasObject(By.desc(play)), 20_000))
            // Annotation history must survive the blocked interval, not merely current recipe equality.
            main { vm.undoVideoAnnotation() }
            assertTrue(checkNotNull(vm.videoEditor.value).recipe.annotations.isEmpty())
            main { vm.redoVideoAnnotation() }
            assertPreserved(false)
            assertEquals(originalHash, hash(uri))
            File(evidence, "result.json").writeText(JSONObject().put("state", "PASS").put("doc", doc)
                .put("uri", uri).put("taskId", taskId).put("sessionId", edited.id).put("sha256", originalHash)
                .put("readDeniedByOs", true).put("explicitRetry", true).put("realTrackingProgress", observedProgress.get())
                .put("trackingCompletedInBackground", tracking.isCompleted).put("noOutputsOrPendingRows", true).toString(2))
            success = true
        } finally {
            // Always release the IO callback before any cancellation/drain or fixture cleanup.
            releaseTracking.countDown()
            if (!success) android.util.Log.e("ExternalVideoTrackingFixture",
                "RETAINED before drain doc=$doc sha256=$originalHash evidence=${evidence.path}")
            withContext(NonCancellable) {
                ownedTrackingJob?.takeIf { !it.isCompleted }?.let { tracking ->
                    main { model?.cancelVideoAnnotationTracking() }
                    withTimeout(15_000) { tracking.join() }
                }
            }
            if (success) {
                // Close our editor before exact hash-guarded owner cleanup; never delete provider files directly.
                main { model?.clearExternal() }
                val cleanup = control("cleanup")
                check(cleanup.getBoolean("deleted"))
                File(evidence, "cleanup.json").writeText(cleanup.toString(2))
            } else {
                android.util.Log.e("ExternalVideoTrackingFixture", "RETAINED doc=$doc sha256=$originalHash evidence=${evidence.path}")
                device.takeScreenshot(File(evidence, "failure.png"))
                device.dumpWindowHierarchy(File(evidence, "failure.xml"))
            }
        }
    }
    private companion object {
        const val Consumer = "com.librestatic.lightforge.pdfacceptance"
        const val Owner = "com.librestatic.lightforge.mediaprovider.fixture"
        const val Authority = "com.librestatic.lightforge.mediaprovider.fixture.documents"
        const val FixtureHash = "ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861"
    }
}
