package com.librestatic.lightforge

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.remotestorage.*
import com.librestatic.lightforge.feature.ownsync.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Settings/picker/worker flow. The coordinator kills only the acceptance app between phases.
 */
class OwnSyncAppDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val device
        get() = UiDevice.getInstance(instrumentation)

    private val record
        get() = File(context.filesDir, "own-sync-process-probe.json")

    private val evidence
        get() = File(context.filesDir, "own-sync-process-evidence").apply { mkdirs() }

    private fun guard(phase: String) {
        assertEquals("com.librestatic.lightforge.pdfacceptance", context.packageName)
        assertEquals("lightforge-wave34", args.getString("sftpFixture"))
        assertEquals(phase, args.getString("syncProbePhase"))
    }

    private fun capture(name: String) {
        device.takeScreenshot(File(evidence, "$name.png"))
        device.dumpWindowHierarchy(File(evidence, "$name.xml"))
    }

    private fun find(selector: BySelector, timeout: Long = 20000): UiObject2 {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            try {
                device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
            } catch (_: StaleObjectException) {}
            try {
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
                    ?.scroll(Direction.DOWN, .55f)
            } catch (_: StaleObjectException) {}
            device.waitForIdle()
        }
        capture("missing-control")
        error("Missing own-sync control $selector")
    }

    private fun click(selector: BySelector) {
        find(selector.enabled(true)).click()
        device.waitForIdle()
    }

    private fun type(tag: String, value: String) {
        val node = find(By.res(tag))
        val edit =
            if (node.className == "android.widget.EditText") node
            else node.findObject(By.clazz("android.widget.EditText")) ?: node
        edit.text = value
        device.waitForIdle()
    }

    private fun hash(input: InputStream): RemoteDigest =
        input.use {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var size = 0L
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                size += n
                require(size <= 16L * 1024 * 1024)
                md.update(buffer, 0, n)
            }
            RemoteDigest(size, md.digest().joinToString("") { b -> "%02x".format(b) })
        }

    private suspend fun await(
        description: String,
        timeout: Long = 120000,
        predicate: () -> Boolean,
    ) {
        withTimeout(timeout) {
            while (!predicate()) {
                delay(100)
            }
        }
    }

    private fun sourceFolder(name: String) {
        capture("picker-entry")
        val breadcrumb = Pattern.compile(".*documentsui:id/breadcrumb_text")
        // Activity transition can report idle before DocumentsUI populates its restored directory.
        // Decide the navigation branch only after the real picker hierarchy is present.
        assertTrue(device.wait(Until.hasObject(By.res(breadcrumb)), 15000))
        capture("picker-ready")
        if (device.hasObject(By.res(breadcrumb).text(name))) return
        if (device.hasObject(By.res(breadcrumb).text("Download")))
            click(By.res(breadcrumb).text("Download"))
        else if (device.wait(Until.hasObject(By.res("android:id/title").text("Download")), 1500))
            click(By.res("android:id/title").text("Download"))
        else {
            val root =
                if (device.wait(Until.hasObject(By.desc("Show roots")), 3000)) By.desc("Show roots")
                else By.desc("Navigate up")
            click(root)
            click(By.res("android:id/title").text("Downloads"))
        }
        click(By.res("android:id/title").text(name))
        assertTrue(device.hasObject(By.text(name)))
    }

    private fun openSyncRoute() {
        device
            .findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))
            ?.click()
        click(By.desc(context.getString(com.librestatic.lightforge.feature.photos.R.string.open_settings)))
        click(By.text(context.getString(com.librestatic.lightforge.feature.settings.R.string.settings_backup)))
        click(By.text(context.getString(com.librestatic.lightforge.feature.settings.R.string.own_sync_entry)))
        assertTrue(device.wait(Until.hasObject(By.res("own-sync-screen")), 20000))
    }

    private fun profile(id: String, name: String, port: Int) =
        RemoteProfile(
            id = id,
            name = name,
            protocol = RemoteProtocol.SFTP,
            host = "10.0.2.2",
            port = port,
            username = "lightforgefixture",
            root = "/data/archive",
            trustedHostKey = requireNotNull(args.getString("sftpPin")),
        )

    @Test
    fun prepareRealSafSyncForProcessDeath() =
        runBlocking<Unit> {
            guard("prepare")
            val name = "ug-sync-" + UUID.randomUUID().toString().take(8)
            val resolver = context.contentResolver
            val source =
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "original.png")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$name/Photos/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            val bitmap = Bitmap.createBitmap(1700, 1700, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(1700 * 1700)
            val random = java.util.Random(353535)
            for (i in pixels.indices) pixels[i] = random.nextInt() or 0xff000000.toInt()
            bitmap.setPixels(pixels, 0, 1700, 0, 0, 1700, 1700)
            resolver.openOutputStream(source)!!.use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            pixels.fill(0)
            assertEquals(
                1,
                resolver.update(
                    source,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ),
            )
            val original = hash(resolver.openInputStream(source)!!)
            assertTrue(original.size in 8L * 1024 * 1024..12L * 1024 * 1024)
            val profile = profile(UUID.randomUUID().toString(), name, 22237)
            val database = GalleryDatabaseFactory.open(context)
            val profiles = GalleryRemoteBackupWorker.controller(context, database)
            try {
                profiles.saveProfile(
                    profile,
                    RemoteCredentials.Password("lightforge-fixture-password".toCharArray()),
                )
            } finally {
                profiles.close()
                database.close()
            }
            val scenario =
                ActivityScenario.launch<MainActivity>(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            try {
                openSyncRoute()
                click(By.res("own-sync-create"))
                type("own-sync-name", name)
                if (
                    device.hasObject(By.pkg("com.google.android.inputmethod.latin")) ||
                        device.hasObject(By.pkg("com.android.inputmethod.latin"))
                )
                    device.pressBack()
                click(By.res("own-sync-source"))
                sourceFolder(name)
                click(By.text(Pattern.compile("USE THIS FOLDER", Pattern.CASE_INSENSITIVE)))
                val allow =
                    By.res("android:id/button1")
                        .text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE))
                assertTrue(device.wait(Until.hasObject(allow), 10000))
                click(allow)
                click(By.res("own-sync-profile-${profile.id}"))
                assertTrue(find(By.res("own-sync-profile-${profile.id}")).isChecked)
                click(By.res("own-sync-save"))
                val store = OwnSyncStore(context)
                await("own sync job created") { store.jobs().any { it.name == name } }
                val job = store.jobs().single { it.name == name }
                assertEquals(profile.id, job.profile.id)
                assertTrue(
                    resolver.persistedUriPermissions.any {
                        it.uri.toString() == job.tree && it.isReadPermission
                    }
                )
                val id = store.runs().single { it.jobId == job.id }.id
                await("real source scan awaiting review") {
                    val run = store.run(id)!!
                    assertTrue(
                        run.status in
                            listOf(
                                OwnSyncStatus.Queued,
                                OwnSyncStatus.Running,
                                OwnSyncStatus.AwaitingReview,
                            )
                    )
                    run.status == OwnSyncStatus.AwaitingReview
                }
                val preview = store.run(id)!!
                assertTrue(preview.snapshot!!.complete)
                assertEquals(1, preview.plan.count { it.action == OwnSyncAction.Add })
                assertEquals(
                    listOf("Photos", "original.png"),
                    preview.snapshot!!.entries.single().path,
                )
                assertEquals(original, preview.snapshot!!.entries.single().digest)
                click(By.res("own-sync-review-$id"))
                capture("review-real-tree")
                click(By.res("own-sync-confirm"))
                val saved =
                    JSONObject()
                        .put("run", id)
                        .put("job", job.id)
                        .put("namespace", job.namespace)
                        .put("pid", Process.myPid())
                        .put("profile", profile.id)
                        .put("name", name)
                        .put("tree", job.tree)
                        .put("source", source.toString())
                        .put("sourceBytes", original.size)
                        .put("sourceSha", original.sha256)
                record.writeText(saved.toString())
                await("real upload staging") {
                    val run = store.run(id)!!
                    assertTrue(run.status in listOf(OwnSyncStatus.Queued, OwnSyncStatus.Running))
                    val entry = run.plan.single { it.action == OwnSyncAction.Add }
                    if (entry.staging != null) {
                        saved.put(
                            "oldStage",
                            entry.path.dropLast(1).plus(entry.staging!!).joinToString("/"),
                        )
                        saved.put("target", entry.path.joinToString("/"))
                        record.writeText(saved.toString())
                        true
                    } else false
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "OWN_SYNC_PROCESS_READY run=$id job=${job.id} pid=${Process.myPid()} staging=${saved.getString("oldStage")}\n",
                        )
                    },
                )
                withTimeout(180000) { while (true) delay(1000) }
            } catch (error: Throwable) {
                runCatching { capture("prepare-failure") }
                throw error
            } finally {
                scenario.close()
            }
        }

    @Test
    fun verifyRealSafSyncAfterProcessDeathAndRepeatWithoutWrites() =
        runBlocking<Unit> {
            guard("verify")
            val saved = JSONObject(record.readText())
            assertNotEquals(saved.getInt("pid"), Process.myPid())
            assertTrue(
                context.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == saved.getString("tree") && it.isReadPermission
                }
            )
            val id = saved.getString("run")
            val store = OwnSyncStore(context)
            val scenario =
                ActivityScenario.launch<MainActivity>(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            val controller = GalleryOwnSyncWorker.controller(context)
            try {
                controller.reconcile()
                await("same own sync run completed", 150000) {
                    val run = store.run(id)!!
                    assertTrue(
                        run.status in
                            listOf(
                                OwnSyncStatus.Queued,
                                OwnSyncStatus.Running,
                                OwnSyncStatus.Completed,
                            )
                    )
                    run.status == OwnSyncStatus.Completed
                }
                val original =
                    hash(
                        context.contentResolver.openInputStream(
                            Uri.parse(saved.getString("source"))
                        )!!
                    )
                assertEquals(saved.getLong("sourceBytes"), original.size)
                assertEquals(saved.getString("sourceSha"), original.sha256)
                val completed = store.run(id)!!
                val entry = completed.plan.single { it.action == OwnSyncAction.Add }
                assertTrue(entry.done)
                val newStage = entry.path.dropLast(1).plus(entry.staging!!).joinToString("/")
                assertNotEquals(saved.getString("oldStage"), newStage)
                assertTrue(saved.getString("oldStage") in completed.residuals)
                val profile = profile(saved.getString("profile"), saved.getString("name"), 22234)
                fun <T> remote(block: (RemoteManagedConnection) -> T): T =
                    RemoteCredentials.Password("lightforge-fixture-password".toCharArray()).use {
                        secret ->
                        OwnStorageConnectionFactory()
                            .connect(profile, secret, RemoteCancellation())
                            .use { block(it as RemoteManagedConnection) }
                    }
                fun readPath(parent: RemoteManagedConnection, path: String): RemoteDigest {
                    val segments = path.split('/')
                    return parent.directory(segments.dropLast(1), false).use { dir ->
                        hash(dir.openRead(segments.last()))
                    }
                }
                val before = remote { parent ->
                    val old = readPath(parent, saved.getString("oldStage"))
                    assertEquals(args.getString("syncOldStageSha"), old.sha256)
                    assertEquals(args.getString("syncOldStageBytes")!!.toLong(), old.size)
                    assertTrue(old.size > 0 && old.size < original.size)
                    assertEquals(original, readPath(parent, saved.getString("target")))
                    parent.directory(listOf(saved.getString("namespace"), "Photos"), false).use {
                        dir ->
                        dir.list(1000).associate { it.name to hash(dir.openRead(it.name)) }
                    }
                }
                // Repeat through the actual visible route, not only the controller API.
                openSyncRoute()
                click(By.res("own-sync-rerun-${saved.getString("job")}"))
                await("second scan") {
                    store.runs().any {
                        it.jobId == saved.getString("job") &&
                            it.id != id &&
                            it.status == OwnSyncStatus.AwaitingReview
                    }
                }
                val repeat =
                    store.runs().single { it.jobId == saved.getString("job") && it.id != id }
                assertTrue(repeat.plan.all { it.action == OwnSyncAction.Verified })
                click(By.res("own-sync-review-${repeat.id}"))
                click(By.res("own-sync-confirm"))
                await("verified repeat completed") {
                    store.run(repeat.id)!!.status == OwnSyncStatus.Completed
                }
                val after = remote { parent ->
                    parent.directory(listOf(saved.getString("namespace"), "Photos"), false).use {
                        dir ->
                        dir.list(1000).associate { it.name to hash(dir.openRead(it.name)) }
                    }
                }
                assertEquals(before, after)
                capture("completed-after-real-death-and-repeat")
                saved
                    .put("newPid", Process.myPid())
                    .put("newStage", newStage)
                    .put("repeatRun", repeat.id)
                    .put("repeatZeroWrites", true)
                    .put("persistedGrantVerified", true)
                    .put("result", "PASS")
                File(evidence, "result.json").writeText(saved.toString())
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "OWN_SYNC_PROCESS_RECOVERY_PASS run=$id oldPid=${saved.getInt("pid")} newPid=${Process.myPid()} sourceSha=${original.sha256} oldStage=${saved.getString("oldStage")} newStage=$newStage repeat=${repeat.id} zeroWrites=true persistedGrant=true\n",
                        )
                    },
                )
            } catch (error: Throwable) {
                runCatching { capture("verify-failure") }
                throw error
            } finally {
                controller.close()
                scenario.close()
            }
        }
}
