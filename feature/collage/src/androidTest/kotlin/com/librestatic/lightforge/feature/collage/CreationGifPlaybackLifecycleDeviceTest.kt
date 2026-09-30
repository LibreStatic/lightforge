package com.librestatic.lightforge.feature.collage

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.IdlingPolicies
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Actual Home/STOP/return on the existing Compose host, without lifecycle injection or recreation. */
class CreationGifPlaybackLifecycleDeviceTest {
    // Cooperative Espresso errors unwind this test and its normal cleanup; no abandoned timeout thread.
    @get:Rule(order = 0) val espressoDeadline = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val master = IdlingPolicies.getMasterIdlingPolicy()
                val resources = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
                try {
                    IdlingPolicies.setMasterPolicyTimeout(15, TimeUnit.SECONDS)
                    IdlingPolicies.setIdlingResourceTimeout(10, TimeUnit.SECONDS)
                    IdlingPolicies.setMasterPolicyTimeoutWhenDebuggerAttached(true)
                    base.evaluate()
                } finally {
                    IdlingPolicies.setMasterPolicyTimeout(master.idleTimeout, master.idleTimeoutUnit)
                    IdlingPolicies.setIdlingResourceTimeout(resources.idleTimeout, resources.idleTimeoutUnit)
                    IdlingPolicies.setMasterPolicyTimeoutWhenDebuggerAttached(master.timeoutIfDebuggerAttached)
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun activePlaybackStopsAtHomeAndOnlyExplicitPlayResumes() {
        val session = UUID.randomUUID().toString()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val files = colors.map { color ->
            File.createTempFile("gif-home-$session-", ".png", context.cacheDir).also { file ->
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }
        val sourceHashes = files.map(::hash)
        val sources = files.map { CreationGifSource(Uri.fromFile(it)) }
        val journal = CreationGifPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "gif-publications"))
        val beforeJournal = journal.listEntries().sortedBy { it.id }
        check(journal.read(session) == null)
        val beforeOutputs = outputs()
        val evidence = File(context.filesDir, "gif-playback-lifecycle-$session").apply { check(mkdir()) }
        val fixture = JSONObject().put("sessionId", session).put("sources", JSONArray().apply {
            files.zip(sourceHashes).forEach { (file, sha) -> put(JSONObject().put("path", file.canonicalPath).put("sha256", sha)) }
        })
        File(evidence, "fixture.json").writeText(fixture.toString(2))
        report("fixture=$session evidence=${evidence.absolutePath} sources=${files.zip(sourceHashes)}")
        var visible by mutableStateOf(true)
        var activity: Activity? = null
        var controller: CreationGifViewModel? = null
        val delivered = mutableListOf<Uri>()
        var passed = false
        var homeStopped = false
        var sameActivityReturned = false
        var stableMillis = 0L
        var explicitPlayAdvanced = false
        var cleaned = false
        try {
            compose.setContent {
                LightforgeTheme {
                    if (visible) {
                        val ownActivity = LocalContext.current.activity()
                        val own: CreationGifViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "creation-gif-$session")
                        SideEffect { activity = ownActivity; controller = own }
                        CreationGifContent(session, "GIF Home $session", sources, {},
                            onExported = delivered::add, onOpen = delivered::add, onShare = delivered::add)
                    }
                }
            }
            awaitTag("creation-gif-preview")
            val host = checkNotNull(activity)
            awaitStage(host, Stage.RESUMED)
            val taskId = host.taskId
            val pid = android.os.Process.myPid()
            click("creation-gif-seconds-1")
            val initial = text("creation-gif-position")
            click("creation-gif-play")
            compose.waitUntil(5_000) { text("creation-gif-play") == context.getString(R.string.creation_gif_pause) }
            compose.waitUntil(5_000) { text("creation-gif-position") != initial }
            // Do not equate this position with the STOP position: the Home transition can span a tick.
            report("active-before-Home position=${text("creation-gif-position")} task=$taskId pid=$pid")
            val shell = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            val homeResult = ParcelFileDescriptor.AutoCloseInputStream(shell).bufferedReader().use { it.readText().trim() }
            assertEquals("", homeResult) // The following observed STOPPED state proves Home, not an invented shell status.
            awaitStage(host, Stage.STOPPED)
            homeStopped = true
            report("Home observed STOPPED sameActivity task=${host.taskId} pid=${android.os.Process.myPid()}")
            // No Compose idle/capture while its real Activity is stopped.
            assertStaysInStage(host, Stage.STOPPED, 1_200)
            resume(host)
            awaitStage(host, Stage.RESUMED)
            assertSame(host, activity)
            assertEquals(taskId, host.taskId)
            assertEquals(pid, android.os.Process.myPid())
            sameActivityReturned = true
            compose.waitUntil(5_000) { text("creation-gif-play") == context.getString(R.string.creation_gif_play) }
            val stoppedPosition = text("creation-gif-position")
            val stoppedIndex = (1..3).single { stoppedPosition == context.getString(R.string.creation_gif_position, it, 3) } - 1
            awaitColor(colors[stoppedIndex])
            val stableStarted = SystemClock.elapsedRealtime()
            assertHolds(2_400, "Returned GIF must stay paused on its frame") {
                text("creation-gif-play") == context.getString(R.string.creation_gif_play) &&
                    text("creation-gif-position") == stoppedPosition
            }
            assertEquals(colors[stoppedIndex], previewColor())
            stableMillis = SystemClock.elapsedRealtime() - stableStarted
            report("return paused position=$stoppedPosition color=${colors[stoppedIndex]} stableMillis=${SystemClock.elapsedRealtime() - stableStarted}")
            click("creation-gif-play")
            compose.waitUntil(5_000) { text("creation-gif-play") == context.getString(R.string.creation_gif_pause) }
            compose.waitUntil(5_000) { text("creation-gif-position") != stoppedPosition }
            click("creation-gif-play")
            compose.waitUntil(5_000) { text("creation-gif-play") == context.getString(R.string.creation_gif_play) }
            val resumedPosition = text("creation-gif-position")
            val resumedIndex = (1..3).single { resumedPosition == context.getString(R.string.creation_gif_position, it, 3) } - 1
            assertNotEquals(stoppedPosition, resumedPosition)
            awaitColor(colors[resumedIndex])
            explicitPlayAdvanced = true
            assertTrue(delivered.isEmpty())
            assertEquals(sourceHashes, files.map(::hash))
            assertEquals(beforeOutputs, outputs())
            assertEquals(beforeJournal, journal.listEntries().sortedBy { it.id })
            assertNull(journal.read(session))
            passed = true
        } finally {
            try {
                // Release only this host's UI/work. A failed source fixture is deliberately retained.
                activity?.takeIf { !it.isDestroyed && !it.isFinishing }?.let { host ->
                    if (!inStage(host, Stage.RESUMED)) { resume(host); awaitStage(host, Stage.RESUMED) }
                }
                instrumentation.runOnMainSync { visible = false }
                compose.waitForIdle()
                runBlocking { withContext(Dispatchers.Main) { controller?.cancelAndWait() } }
                if (passed) {
                    assertTrue(delivered.isEmpty())
                    assertEquals(sourceHashes, files.map(::hash))
                    assertEquals(beforeOutputs, outputs())
                    assertEquals(beforeJournal, journal.listEntries().sortedBy { it.id })
                    assertNull(journal.read(session))
                    files.forEach { check(it.delete() && !it.exists()) }
                    cleaned = true
                    report("PASS fixture=$session originalHashes=$sourceHashes outputsUnchanged=true journalUnchanged=true cleanupComplete=true")
                } else report("FAIL fixture retained=$session sources=${files.zip(sourceHashes)}")
            } finally {
                File(evidence, "result.json").writeText(JSONObject()
                    .put("sessionId", session).put("status", if (passed && cleaned) "PASS" else "FAIL")
                    .put("homeStoppedObserved", homeStopped).put("sameActivityResumed", sameActivityReturned)
                    .put("pausedStableMillis", stableMillis).put("explicitPlayAdvanced", explicitPlayAdvanced)
                    .put("originalsVerified", passed).put("outputsUnchanged", passed).put("journalUnchanged", passed)
                    .put("cleanupComplete", cleaned).toString(2))
            }
        }
    }

    private fun Context.activity(): Activity = generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().first()

    private fun inStage(activity: Activity, stage: Stage): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { it === activity }
        }
        return found
    }

    /** Fails as soon as [condition] turns false within [millis]: a condition wait, not a fixed sleep. */
    private fun assertHolds(millis: Long, message: String, condition: () -> Boolean) {
        val broke = try {
            compose.waitUntil(millis) { !condition() }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
        assertFalse(message, broke)
    }

    /** Fails as soon as [activity] leaves [stage] within [millis]; waits on lifecycle callbacks. */
    private fun assertStaysInStage(activity: Activity, stage: Stage, millis: Long) {
        val left = java.util.concurrent.CountDownLatch(1)
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { changed, newStage ->
            if (changed === activity && newStage != stage) left.countDown()
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            assertTrue(inStage(activity, stage))
            assertFalse("Activity left $stage early", left.await(millis, TimeUnit.MILLISECONDS))
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
    }

    private fun awaitStage(activity: Activity, stage: Stage) {
        val reached = java.util.concurrent.CountDownLatch(1)
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { changed, newStage ->
            if (changed === activity && newStage == stage) reached.countDown()
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            if (!inStage(activity, stage)) reached.await(10, TimeUnit.SECONDS)
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
        assertTrue("Expected real Activity $stage: ${activity.componentName} task=${activity.taskId}", inStage(activity, stage))
    }

    private fun resume(activity: Activity) {
        check(!activity.isDestroyed && !activity.isFinishing)
        context.startActivity(Intent().setComponent(activity.componentName).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
    private fun positionPreview() {
        report("preview-position begin")
        // Preview is the first child of this exact scroll container. One action, not performScrollTo's
        // retry-until-visible loop; awaitColor must never scroll on each capture attempt.
        val container = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("creation-gif-preview")))
        val axis = container.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val offset = axis.value()
        check(offset.isFinite() && offset >= 0f)
        if (offset > 0f) {
            report("preview-position scrollBy=${-offset}")
            container.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                check(action(0f, -offset))
            }
        }
        compose.onNodeWithTag("creation-gif-preview").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("creation-gif-preview").fetchSemanticsNode().boundsInRoot
        check(bounds.width > 0f && bounds.height > 0f)
        report("preview-position complete bounds=$bounds")
    }
    private fun previewColor(): Int {
        report("preview-display-capture begin")
        val target = compose.onNodeWithTag("creation-gif-preview").assertIsDisplayed()
        val before = target.fetchSemanticsNode()
        val size = before.size
        val screenPosition = before.positionOnScreen
        val visibleBounds = before.boundsInRoot
        val centerOffset = Offset(size.width / 2f, size.height / 2f)
        val centerInRoot = before.positionInRoot + centerOffset
        check(visibleBounds.contains(centerInRoot)) { "Preview center is clipped: $visibleBounds" }
        val centerOnScreen = screenPosition + centerOffset
        check(centerOnScreen.x.isFinite() && centerOnScreen.y.isFinite())
        // Capture the actual display through the existing instrumentation connection. No source
        // bitmap, PixelCopy deadline, new accessibility observer or guessed status-bar offset.
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Display screenshot unavailable" }
        return try {
            check(centerOnScreen.x >= 0f && centerOnScreen.x < screenshot.width &&
                centerOnScreen.y >= 0f && centerOnScreen.y < screenshot.height) {
                "Preview center $centerOnScreen outside display ${screenshot.width}x${screenshot.height}"
            }
            val after = target.fetchSemanticsNode()
            check(screenPosition == after.positionOnScreen && size == after.size &&
                visibleBounds == after.boundsInRoot) { "Preview moved during display capture" }
            val color = screenshot.getPixel(centerOnScreen.x.toInt(), centerOnScreen.y.toInt())
            report("preview-display-capture complete display=${screenshot.width}x${screenshot.height} center=$centerOnScreen color=$color")
            color
        } finally { screenshot.recycle() }
    }
    private fun awaitColor(expected: Int) {
        awaitTag("creation-gif-preview")
        positionPreview()
        var observed = previewColor()
        try { compose.waitUntil(5_000) { observed = previewColor(); observed == expected } }
        catch (failure: ComposeTimeoutException) { throw AssertionError("Expected preview color=$expected; observed=$observed", failure) }
    }
    private fun hash(file: File) = file.inputStream().use { CreationGifExporter.sha256(it) }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    private fun outputs(): List<List<String?>> {
        val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path", "mime_type",
            "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "owner_package_name=? AND relative_path=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, "Pictures/Lightforge/GIF/"))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return checkNotNull(context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, query, null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(columns.indices.map { cursor.getString(it) }) }
        }
    }
}
