package com.ugallery.app

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ugallery.core.editing.video.NormalizedPoint
import com.ugallery.core.editing.video.VideoAnnotationLayer
import com.ugallery.core.editing.video.VideoAnnotationShape
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** VM→real WorkManager render, then owner-UID READ revocation before publication. No mocked render or direct worker execution. */
class ExternalVideoExportAccessAppDeviceTest {
    @Test fun revokedReadAfterRealRenderPreventsPublicationAndPreservesDraft(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == Consumer)
        val device = UiDevice.getInstance(instrumentation)
        val doc = UUID.randomUUID().toString()
        val evidence = File(context.filesDir, "external-video-export-$doc").apply { check(mkdir()) }
        var success = false
        var originalHash: String? = null
        var model: GalleryViewModel? = null
        val renderReady = CompletableDeferred<Triple<VideoExportJob, File, String>>()
        val releasePublication = CompletableDeferred<Unit>()
        val callbackSeen = AtomicBoolean(false)
        val callbackFailure = AtomicReference<Throwable?>(null)
        var observer: AutoCloseable? = null
        var createdJob: VideoExportJob? = null
        val workManager = WorkManager.getInstance(context)
        suspend fun workInfo(job: VideoExportJob): WorkInfo = withContext(Dispatchers.IO) {
            checkNotNull(workManager.getWorkInfoById(UUID.fromString(job.workId)).get(10, TimeUnit.SECONDS))
        }
        suspend fun awaitWorkFinished(job: VideoExportJob): WorkInfo = withTimeout(45_000) {
            var info = workInfo(job)
            while (!info.state.isFinished) { delay(100); info = workInfo(job) }
            info
        }
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
            var expectedFailedJob: VideoExportJob? = null
            File(evidence, "output-inventory-before.json").writeText(org.json.JSONArray(outputs.map { org.json.JSONArray(it) }).toString(2))
            fun assertPreserved(blocked: Boolean? = null) {
                val current = checkNotNull(vm.videoEditor.value)
                assertEquals(edited.id, current.id); assertEquals(recipe, current.recipe)
                assertEquals(baseline, current.baselineRecipe)
                if (blocked != null) assertEquals(blocked, current.externalAccessBlocked)
                assertFalse(current.content.isExporting); assertNull(current.content.annotationTrackingProgress)
                assertNull(current.pendingExportRecipe)
                assertEquals(outputs, outputInventory())
                assertEquals(jobs, vm.videoExports.value.filter { it.id != checkNotNull(createdJob).id })
                assertEquals(jobs.size + 1, vm.videoExports.value.size)
                assertEquals(checkNotNull(expectedFailedJob), VideoExportStore.get(context).get(checkNotNull(createdJob).id))
            }
            observer = VideoExportWorker.observeBeforePublication(uri.toString()) { job, file ->
                try {
                    check(callbackSeen.compareAndSet(false, true)) { "Publication observer invoked more than once" }
                    check(job.inputUri == uri.toString())
                    val expected = File(context.cacheDir, "video-export-${job.id}.mp4")
                    check(file.canonicalFile == expected.canonicalFile && file.isFile && file.length() in 1L..33_554_432L)
                    val renderHash = withContext(Dispatchers.IO) {
                        file.inputStream().use { input ->
                            val bytes = input.readBytes()
                            check(bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp")
                            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
                        }
                    }
                    File(evidence, "real-render.json").writeText(JSONObject().put("jobId", job.id).put("workId", job.workId)
                        .put("sourceUri", uri).put("path", file.path).put("bytes", file.length()).put("sha256", renderHash)
                        .put("renderIsReal", true).toString(2))
                    renderReady.complete(Triple(job, file, renderHash))
                    withTimeout(60_000) { releasePublication.await() }
                } catch (failure: Throwable) {
                    callbackFailure.set(failure)
                    renderReady.completeExceptionally(failure)
                    throw failure
                }
            }
            main {
                vm.saveVideoEditorCopy()
                val id = checkNotNull(vm.videoEditor.value?.exportJobId)
                createdJob = vm.videoExports.value.single { it.id == id && it.inputUri == uri.toString() }
            }
            val job = checkNotNull(createdJob)
            assertFalse(jobs.any { it.id == job.id })
            val rendered = withTimeout(60_000) { renderReady.await() }
            assertEquals(job.id, rendered.first.id); assertEquals(job.workId, rendered.first.workId)
            assertEquals(WorkInfo.State.RUNNING, workInfo(job).state)
            assertNull("Render observation failed", callbackFailure.get())
            assertNull(VideoExportStore.get(context).get(job.id)?.outputUri)
            assertNull(VideoExportStore.get(context).get(job.id)?.pendingUri)
            assertEquals(outputs, outputInventory())
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
            // No foreground revalidation/cancel: the real worker itself must reject revoked input before publication.
            assertFalse(checkNotNull(vm.videoEditor.value).externalAccessBlocked)
            releasePublication.complete(Unit)
            val terminal = awaitWorkFinished(job)
            assertTrue(terminal.state.isFinished)
            assertNull("Observer failed instead of exercising revoked-access preflight", callbackFailure.get())
            val failed = checkNotNull(VideoExportStore.get(context).get(job.id))
            assertEquals(VideoExportJobStatus.Failed, failed.status)
            expectedFailedJob = failed
            assertEquals(job.inputUri, failed.inputUri); assertEquals(job.encodedRecipe, failed.encodedRecipe)
            assertEquals(job.workId, failed.workId)
            assertNull(failed.outputUri); assertNull(failed.pendingUri)
            assertFalse("Rendered staging must be removed after terminal failure", rendered.second.exists())
            requireStopped()
            withTimeout(10_000) { while (vm.videoEditor.value?.content?.isExporting == true) delay(50) }
            assertPreserved()
            File(evidence, "output-inventory-after-background.json").writeText(org.json.JSONArray(outputInventory().map { org.json.JSONArray(it) }).toString(2))
            val marker = File(context.filesDir, "video-export-jobs/${job.id}.json")
            check(marker.isFile)
            File(evidence, "failed-job.json").writeBytes(marker.readBytes())
            File(evidence, "background-result.json").writeText(JSONObject().put("workInfoState", terminal.state.name)
                .put("jobId", failed.id).put("jobStatus", failed.status.name).put("error", failed.error)
                .put("recipeUnchanged", true).put("baselineUnchanged", true).put("stagingAbsent", true)
                .put("mediaInventoryUnchanged", true).put("failedMarkerRetained", marker.path).toString(2))
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
            val play = context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_play)
            assertTrue("Reopened editor remains paused", device.wait(Until.hasObject(By.desc(play)), 20_000))
            // Annotation history must survive the blocked interval, not merely current recipe equality.
            main { vm.undoVideoAnnotation() }
            assertTrue(checkNotNull(vm.videoEditor.value).recipe.annotations.isEmpty())
            main { vm.redoVideoAnnotation() }
            assertPreserved(false)
            assertEquals(originalHash, hash(uri))
            File(evidence, "result.json").writeText(JSONObject().put("state", "PASS").put("doc", doc)
                .put("uri", uri).put("taskId", taskId).put("sessionId", edited.id).put("sha256", originalHash)
                .put("readDeniedByOs", true).put("explicitRetry", true).put("realRenderSha256", rendered.third)
                .put("workInfoState", terminal.state.name).put("jobId", failed.id).put("noOutputsOrPendingRows", true).toString(2))
            success = true
        } finally {
            // A failed test must not release a still-readable source into publication. Cancel only our exact intent,
            // then always release and unregister even if cancellation bookkeeping fails.
            try {
                if (!success) createdJob?.let { owned ->
                    VideoExportStore.get(context).get(owned.id)?.takeIf { it.isActive }?.let { current ->
                        check(current.workId == owned.workId && current.inputUri == owned.inputUri && current.encodedRecipe == owned.encodedRecipe)
                        VideoExportStore.get(context).cancel(current)
                    }
                }
            } finally {
                releasePublication.complete(Unit)
                observer?.close()
            }
            if (!success) android.util.Log.e("ExternalVideoExportFixture",
                "RETAINED before drain doc=$doc job=${createdJob?.id} sha256=$originalHash evidence=${evidence.path}")
            withContext(NonCancellable) {
                createdJob?.let { job ->
                    if (!workInfo(job).state.isFinished) {
                        VideoExportStore.get(context).get(job.id)?.takeIf { it.isActive }?.let { current ->
                            check(current.workId == job.workId && current.inputUri == job.inputUri && current.encodedRecipe == job.encodedRecipe)
                            VideoExportStore.get(context).cancel(current)
                        }
                        awaitWorkFinished(job)
                    }
                }
            }
            if (success) {
                // Close our editor before exact hash-guarded owner cleanup; never delete provider files directly.
                main { model?.clearExternal() }
                val cleanup = control("cleanup")
                check(cleanup.getBoolean("deleted"))
                File(evidence, "cleanup.json").writeText(cleanup.toString(2))
            } else {
                android.util.Log.e("ExternalVideoExportFixture", "RETAINED doc=$doc sha256=$originalHash evidence=${evidence.path}")
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
