package com.librestatic.lightforge

import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.mediastore.*
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

import androidx.compose.ui.test.*
/** Host orchestrates target force-stop between the two phases; no persistable-grant claim. */
class MoveCopyProcessRecoveryDeviceTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val receiptDirectory get() = File(context.noBackupFilesDir.canonicalFile, "move-copy-process-fixture")
    private val receipt get() = File(receiptDirectory, "active.json")
    private fun persist(json: org.json.JSONObject) {
        check(receiptDirectory.isDirectory || receiptDirectory.mkdir())
        val atomic = android.util.AtomicFile(receipt)
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        val fd = android.system.Os.open(receiptDirectory.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
        try { check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)); android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    private fun load(): org.json.JSONObject = org.json.JSONObject(receipt.readText()).also {
        check(it.getInt("schema") == 1 && it.getString("uuid") == Fixture.requiredUuid())
    }
    @Test fun preparePartialCopy(): Unit = runBlocking {
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        check(!receiptDirectory.exists() || receiptDirectory.listFiles()!!.isEmpty())
        val f = Fixture()
        check(!f.journal.exists() || f.journal.listFiles()!!.isEmpty())
        check(ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read() == null)
        for (name in listOf("collage-publications", "gif-publications", "motion-publications"))
            check(File(context.noBackupFilesDir.canonicalFile, name).walkTopDown().none { it.isFile })
        val settings = com.librestatic.lightforge.core.preferences.GallerySettingsRepository(context)
        check(!settings.settings.first().security.destructiveActionLockEnabled)
        val prefs = settings.exportJson().toString()
        try {
            f.setup()
            val manifest = org.json.JSONObject().put("schema", 1).put("uuid", f.uuid).put("preparePid", android.os.Process.myPid())
                .put("sourceUri", f.source.toString()).put("sourceSHA", f.sourceHash).put("name", f.currentName)
                .put("relativePath", "Pictures/move-controller-${f.uuid}/").put("treeUri", f.tree.toString())
                .put("providerAuthority", f.authority).put("settingsJson", prefs).put("phase", "before-copy")
            persist(manifest)
            val controller = f.controller(); f.ready(controller)
            check(runCatching { f.copy(controller) }.isFailure); f.ready(controller)
            withTimeout(10_000) { while (!f.status().getBoolean("writeFaultFinished")) delay(25) }
            check(f.status().getBoolean("partialFsynced"))
            val draft = requireNotNull(controller.state.value.copyDraft)
            check(f.status().getLong("destinationSize") in 1 until draft.bytes)
            check(f.hash(f.source) == f.sourceHash && f.requests.get() == 0)
            manifest.put("destinationUri", requireNotNull(draft.destinationUri)).put("draftId", draft.id)
                .put("draftSHA", draft.sha256).put("phase", "prepared")
            persist(manifest)
            println("MOVE_COPY_PROCESS prepared UUID=${f.uuid} PID=${android.os.Process.myPid()} manifest=${receipt.path}; intentional owned partial/journal retained")
        } catch (failure: Throwable) {
            f.closeScope()
            if (!receipt.exists()) f.cleanup()
            throw failure
        } finally { f.closeScope() }
    }
    @Test fun recoverAfterProcessStop(): Unit = runBlocking {
        val manifest = load(); check(manifest.getString("phase") == "prepared")
        check(android.os.Process.myPid() != manifest.getInt("preparePid"))
        val journal = VerifiedMoveJournal(File(context.noBackupFilesDir.canonicalFile, "verified-move"))
        val draft = requireNotNull(journal.readCopyDraft())
        check(draft.id == manifest.getString("draftId") && draft.destinationUri == manifest.getString("destinationUri"))
        check(draft.sourceUri == manifest.getString("sourceUri") && draft.treeUri == manifest.getString("treeUri") && draft.sha256 == manifest.getString("sourceSHA"))
        // force-stop revokes the fixture receiver's non-persistable grant. Restore only
        // that test transport permission, never setup/reset the retained provider files.
        // This accepts journal/partial recovery, not persisted-SAF-grant recovery.
        val retainedFixture = restoredFixture(manifest)
        retainedFixture.requestProviderAccess()
        check(retainedFixture.status().getBoolean("partialFsynced"))
        check(retainedFixture.status().getLong("destinationSize") == 4096L)
        check(retainedFixture.hash(retainedFixture.source) == manifest.getString("sourceSHA"))
        println("MOVE_COPY_PROCESS fixture-only non-persistable access explicitly restored after process stop; same 4096-byte partial retained")
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        try {
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            lateinit var controller: VerifiedMoveController
            scenario.onActivity { activity -> controller = androidx.lifecycle.ViewModelProvider(activity)[GalleryViewModel::class.java].verifiedMove }
            withTimeout(10_000) { controller.state.first { !it.busy } }
            check(controller.state.value.authorization == null && controller.state.value.copyDraft == draft)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("move-copy-review").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("move-copy-review").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("move-copy-retry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("move-copy-retry").assertIsEnabled().performTouchInput { click() }
            // Retry first copies asynchronously, then requests a SECOND authorization.
            // Keep driving Compose until that authorization reaches the OS request.
            compose.waitUntil(20_000) {
                compose.waitForIdle()
                journal.readActive()?.phase == VerifiedMovePhase.AwaitingSystem &&
                    controller.state.value.authorization == null
            }
            compose.waitForIdle()
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val cancel = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android", "button2")), 15_000)
                ?: error("Actual Android Delete Cancel absent")
            check(cancel.applicationPackage in setOf("com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media"))
            cancel.click()
            withTimeout(10_000) { while (journal.readActive()?.phase != VerifiedMovePhase.Cancelled) delay(25) }
            val proof = requireNotNull(journal.readActive()).proof
            check(proof.destinationUri == draft.destinationUri && proof.sourceUri == draft.sourceUri && proof.sha256 == draft.sha256)
            val f = restoredFixture(manifest)
            check(f.hash(f.source) == manifest.getString("sourceSHA"))
            check(f.hash(Uri.parse(proof.destinationUri)) == manifest.getString("sourceSHA"))
            manifest.put("phase", "recovered").put("recoverPid", android.os.Process.myPid()); persist(manifest)
            println("MOVE_COPY_PROCESS actual MainActivity reviewed/retried same URI and Android Cancel; PID ${manifest.getInt("preparePid")}→${android.os.Process.myPid()}; host proves process-stop")
        } catch (failure: Throwable) {
            scenario?.onActivity { println("MOVE_COPY_PROCESS failureState=${androidx.lifecycle.ViewModelProvider(it)[GalleryViewModel::class.java].verifiedMove.state.value}") }
            println("MOVE_COPY_PROCESS failureJournal=${runCatching { journal.readActive() }}")
            val hierarchy = java.io.ByteArrayOutputStream()
            runCatching { androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).dumpWindowHierarchy(hierarchy) }
            println("MOVE_COPY_PROCESS failureHierarchy=${hierarchy.toString("UTF-8")}")
            throw failure
        } finally { scenario?.close() }
        cleanupPreparedFixture()
    }
    private fun restoredFixture(manifest: org.json.JSONObject): Fixture = Fixture().also { f ->
        check(manifest.getString("providerAuthority") == f.authority && manifest.getString("treeUri") == f.tree.toString())
        val uri = Uri.parse(manifest.getString("sourceUri"))
        check(uri.authority == "media" && uri.path?.startsWith("/external_primary/images/media/") == true)
        f.source = uri; f.sourceHash = manifest.getString("sourceSHA"); f.currentName = manifest.getString("name")
        check(f.currentName == "${f.uuid}.png" && manifest.getString("relativePath") == "Pictures/move-controller-${f.uuid}/")
        f.providerReady = true
    }
    @Test fun cleanupPreparedFixture(): Unit = runBlocking {
        val manifest = load(); val f = restoredFixture(manifest)
        f.requestProviderAccess() // Standalone instrumentation also replaces the prior target process.
        val journal = VerifiedMoveJournal(f.journal)
        val draft = journal.readCopyDraft()
        if (draft != null) {
            check(draft.sourceUri == f.source.toString() && draft.treeUri == f.tree.toString())
            check(draft.sha256 == f.sourceHash)
            if (manifest.has("draftId")) check(draft.id == manifest.getString("draftId") && draft.destinationUri == manifest.getString("destinationUri"))
            check(journal.forgetCopy(draft))
        } else {
            val active = journal.readActive()
            if (active != null) { check(active.proof.sourceUri == f.source.toString() && active.proof.treeUri == f.tree.toString() && active.proof.sha256 == f.sourceHash); if (manifest.has("draftId")) check(active.proof.id == manifest.getString("draftId") && active.proof.destinationUri == manifest.getString("destinationUri")); check(journal.forget(active)) }
        }
        check(com.librestatic.lightforge.core.preferences.GallerySettingsRepository(context).exportJson().toString() == manifest.getString("settingsJson"))
        f.cleanup()
        check(load().toString() == manifest.toString()); check(receipt.delete())
        check(receiptDirectory.listFiles()!!.isEmpty()); check(receiptDirectory.delete())
        println("MOVE_COPY_PROCESS cleanup complete UUID=${f.uuid} own fixtures/journal/manifest absent")
    }
    private class Fixture {
        companion object { fun requiredUuid(): String = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also { require(UUID.fromString(it).toString() == it) } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uuid = requiredUuid()
        val authority = instrumentation.context.packageName + ".safcopyfixture"
        val endpoint = Uri.parse("content://$authority")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "$uuid:root")
        val journal = File(context.noBackupFilesDir.canonicalFile, "verified-move")
        val requests = AtomicInteger()
        var scope: CoroutineScope? = null
        lateinit var source: Uri
        lateinit var sourceHash: String
        var currentName = "$uuid.png"
        var providerReady = false
        suspend fun requestProviderAccess() {
            check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
            context.sendBroadcast(Intent("com.librestatic.lightforge.SAF_FIXTURE").addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                .setComponent(ComponentName(instrumentation.context.packageName, "com.librestatic.lightforge.SafCopyFixtureGrantReceiver"))
                .putExtra("target", context.packageName))
            withTimeout(5_000) { while (true) {
                try { val client = resolver.acquireUnstableContentProviderClient(endpoint); if (client != null) { client.close(); break } } catch (_: SecurityException) { }
                delay(25)
            } }
        }
        suspend fun setup() {
            requestProviderAccess()
            check(resolver.call(endpoint, "fixtureSetup", uuid, Bundle().apply { putString("mode", "fail-write-once") })!!.getBoolean("ready")); providerReady = true
            check(!journal.exists() || journal.listFiles()!!.isEmpty())
            source = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, currentName); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/move-controller-$uuid/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            try { val random = java.util.Random(77)
                val pixels = IntArray(384 * 384) { random.nextInt() or (0xff shl 24) }
                bitmap.setPixels(pixels, 0, 384, 0, 0, 384, 384); resolver.openOutputStream(source, "w")!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            check(resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            sourceHash = hash(source)
        }
        suspend fun controller(): VerifiedMoveController = withContext(Dispatchers.Main.immediate) {
            val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); scope = activeScope
            VerifiedMoveController(context, activeScope, journalDirectory = journal, requestFactory = MediaStoreRequestFactory { _, _ ->
                requests.incrementAndGet()
                PendingIntent.getBroadcast(context, 0, Intent("com.librestatic.lightforge.MOVE_INERT_$uuid").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            })
        }
        suspend fun ready(c: VerifiedMoveController) { withTimeout(10_000) { c.state.first { !it.busy } } }
        suspend fun copy(c: VerifiedMoveController): Uri = withContext(Dispatchers.Main.immediate) {
            c.copy(PendingTreeOperation(MediaActionTarget(MediaKey("external_primary", ContentUris.parseId(source)), MediaKind.Image),
                "copy.png", "image/png", null, true), tree)
        }
        suspend fun authorize(c: VerifiedMoveController, approved: Boolean) = withContext(Dispatchers.Main.immediate) {
            val token = requireNotNull(c.state.value.authorization)
            check(c.claimAuthorization(token)); c.finishAuthorization(token, approved)
        }
        suspend fun authorizeAndLaunch(c: VerifiedMoveController): MediaActionLaunch = coroutineScope {
            val next = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { c.launches.first() } }
            authorize(c, true); next.await().also { launch -> check(withContext(Dispatchers.Main.immediate) { c.confirmLaunch(launch.requestId) }) }
        }
        suspend fun closeScope() { scope?.coroutineContext?.get(Job)?.cancelAndJoin(); scope = null }
        fun hash(uri: Uri): String = resolver.openInputStream(uri)!!.use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun status() = requireNotNull(resolver.call(endpoint, "fixtureStatus", uuid, null))
        suspend fun cleanup() {
            if (providerReady) withTimeout(10_000) { while (status().getBoolean("writeFaultFired") && !status().getBoolean("writeFaultFinished")) delay(25) }
            closeScope()
            if (::source.isInitialized) {
                if (::sourceHash.isInitialized) check(hash(source) == sourceHash)
                resolver.query(source, arrayOf("_display_name", "relative_path", "owner_package_name"), null, null, null)!!.use {
                    check(it.moveToFirst() && it.getString(0) == currentName && it.getString(1) == "Pictures/move-controller-$uuid/" && it.getString(2) == context.packageName)
                }
                check(resolver.delete(source, "_display_name=? AND relative_path=? AND owner_package_name=?",
                    arrayOf(currentName, "Pictures/move-controller-$uuid/", context.packageName)) == 1)
            }
            if (providerReady) check(resolver.call(endpoint, "fixtureCleanup", uuid, null)!!.getBoolean("absent"))
            check(!journal.exists() || journal.listFiles()!!.isEmpty()); check(!journal.exists() || journal.delete())
            // Existing fixtureCleanup revokes its own authority prefix; host preflight verifies no prior grant.
        }
    }
}
