package com.ugallery.app

import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.mediastore.*
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

import androidx.compose.ui.test.*
/** Real panel and Android confirmation, with a seeded verified proof; does not test SAF picker opening. */
class VerifiedMovePlatformDeviceTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @Test fun panelRetriesAfterAndroidCancelThenDeletesOnlyVerifiedOwnedOriginal() = runBlocking {
        val f = Fixture()
        val settings = com.ugallery.core.preferences.GallerySettingsRepository(f.context)
        val prefsBefore = settings.settings.first()
        check(!prefsBefore.security.destructiveActionLockEnabled)
        check(!f.journal.exists() || f.journal.listFiles().orEmpty().isEmpty())
        check(ManualMomentPendingCreateStore(File(f.context.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read() == null)
        for (name in listOf("collage-publications", "gif-publications", "motion-publications"))
            check(File(f.context.noBackupFilesDir.canonicalFile, name).walkTopDown().none { it.isFile })
        val journal = VerifiedMoveJournal(f.journal)
        check(journal.readActive() == null)
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        var proof: VerifiedMoveProof? = null
        var completed = false
        var failure: Throwable? = null
        try {
            f.setup()
            val identity = requireNotNull(VerifiedMoveOperations.identity(f.resolver, f.source))
            val target = MediaActionTarget(MediaKey("external_primary", ContentUris.parseId(f.source)), MediaKind.Image)
            val copied = ScopedMediaOperations.copyToTreeVerified(f.resolver, target, f.tree, "copy.png", "image/png")
            proof = VerifiedMoveProof(f.uuid, target.key.volumeName, target.key.mediaStoreId, target.kind.name,
                f.source.toString(), copied.uri.toString(), f.tree.toString(), copied.bytes, copied.sha256,
                identity.generationAdded, identity.generationModified, false).validated()
            journal.begin(proof)
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                val vm = androidx.lifecycle.ViewModelProvider(it)[GalleryViewModel::class.java]
                check(vm.selectionCount.value == 0L)
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("verified-move-panel").fetchSemanticsNodes().isNotEmpty() }
            val device = androidx.test.uiautomator.UiDevice.getInstance(f.instrumentation)
            fun systemButton(id: String): androidx.test.uiautomator.UiObject2 {
                val button = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android", id)), 10_000)
                    ?: error("Android confirmation button missing: $id")
                check(button.applicationPackage in setOf("com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media")) { "Unexpected confirmation owner: ${button.applicationPackage}" }
                check(device.hasObject(androidx.test.uiautomator.By.res("android", "button1")) &&
                    device.hasObject(androidx.test.uiautomator.By.res("android", "button2")))
                return button
            }
            compose.onNodeWithTag("verified-move-retry").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            compose.waitForIdle()
            systemButton("button2").click()
            withTimeout(10_000) { while (journal.readActive()?.phase != VerifiedMovePhase.Cancelled) delay(50) }
            check(VerifiedMoveOperations.identity(f.resolver, f.source) == identity)
            assertEquals(f.sourceHash, f.hash(f.source))
            assertEquals(f.sourceHash, f.hash(copied.uri))
            compose.onNodeWithTag("verified-move-retry").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            compose.waitForIdle()
            systemButton("button1").click()
            withTimeout(15_000) { while (journal.readActive()?.phase != VerifiedMovePhase.Completed) delay(50) }
            check(VerifiedMoveOperations.identity(f.resolver, f.source) == null)
            assertEquals(f.sourceHash, f.hash(copied.uri))
            completed = true
            compose.onNodeWithTag("verified-move-forget").assertIsEnabled().performTouchInput { click() }
            withTimeout(10_000) { while (journal.readActive() != null) delay(50) }
            println("VERIFIED_MOVE_SYSTEM actual MainActivity panel Retry→Android Cancel→source unchanged→Retry→Android Delete→original absent destinationSHA=${f.sourceHash}; seeded proof, no SAF picker or biometric acceptance")
        } catch (caught: Throwable) {
            scenario?.onActivity { println("VERIFIED_MOVE_UI ${androidx.lifecycle.ViewModelProvider(it)[GalleryViewModel::class.java].verifiedMove.state.value}") }
            println("VERIFIED_MOVE_DIAGNOSTIC journal=${runCatching { journal.readActive() }} sourceIdentity=${runCatching { VerifiedMoveOperations.identity(f.resolver, f.source) }}")
            val hierarchy = java.io.ByteArrayOutputStream()
            runCatching { androidx.test.uiautomator.UiDevice.getInstance(f.instrumentation).dumpWindowHierarchy(hierarchy) }
            println("VERIFIED_MOVE_HIERARCHY ${hierarchy.toString("UTF-8")}")
            failure = caught
        } finally {
            fun step(label: String, action: suspend () -> Unit) = runBlocking {
                try { action() } catch (caught: Throwable) {
                    println("VERIFIED_MOVE_CLEANUP $label failed=${caught.javaClass.name}:${caught.message}")
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            step("activity-close") { scenario?.close() }
            var journalOwned = false
            step("journal-CAS") {
                val active = journal.readActive()
                journalOwned = active == null || active.proof == proof
                if (active != null) {
                    journalOwned = active.proof == proof
                    check(journalOwned) { "Foreign journal and potentially referenced fixtures preserved" }
                    check(journal.forget(active))
                }
            }
            step("preferences-readback") { check(settings.settings.first() == prefsBefore) { "Preferences changed; retained" } }
            if (journalOwned) step("owned-fixtures") { f.cleanup() }
            step("journal-empty") { if (f.journal.exists()) check(f.journal.listFiles().orEmpty().isEmpty()) }
        }
        failure?.let { throw it }
        check(completed)
    }
    @Test fun corruptMoveReviewCancelPreservesBytesThenQuarantinesExactRecord(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val directory = File(context.noBackupFilesDir.canonicalFile, "verified-move")
        val existed = directory.exists()
        check(!existed || directory.isDirectory && directory.listFiles()!!.isEmpty())
        check(ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read() == null)
        for (name in listOf("collage-publications", "gif-publications", "motion-publications"))
            check(File(context.noBackupFilesDir.canonicalFile, name).walkTopDown().none { it.isFile })
        val settings = com.ugallery.core.preferences.GallerySettingsRepository(context)
        val before = settings.settings.first()
        val uuid = UUID.randomUUID().toString()
        val bytes = "corrupt-move-fixture:$uuid".toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val active = File(directory, "active.bin")
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            if (!existed) check(directory.mkdir())
            check(active.createNewFile())
            java.io.FileOutputStream(active).use { it.write(bytes); it.fd.sync() }
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("verified-move-review").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("verified-move-review").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("verified-move-review-cancel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("verified-move-review-cancel").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("verified-move-review-cancel").fetchSemanticsNodes().isEmpty() }
            assertArrayEquals(bytes, active.readBytes())
            assertEquals(listOf("active.bin"), directory.listFiles()!!.map { it.name })
            compose.onNodeWithTag("verified-move-review").assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("verified-move-preserve").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("verified-move-preserve").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(10_000) { !active.exists() && compose.onAllNodesWithTag("verified-move-panel").fetchSemanticsNodes().isEmpty() }
            val preserved = directory.listFiles()!!.single()
            check(preserved.name.matches(Regex("unreadable-[a-f0-9-]{36}-${hash.take(12)}\\.bin")))
            assertArrayEquals(bytes, preserved.readBytes())
            check(VerifiedMoveJournal(directory).readActive() == null)
            assertEquals(before, settings.settings.first())
            println("VERIFIED_MOVE_CORRUPT UUID=$uuid cancel bytes unchanged; preserve active absent quarantine=${preserved.name} SHA=$hash; controller panel cleared; no media operations; next begin covered separately")
        } catch (caught: Throwable) { failure = caught }
        finally {
            fun step(action: () -> Unit) {
                try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) }
            }
            step { scenario?.close() }
            step {
                // Every candidate must match before deleting any; foreign/changed state is retained.
                val candidates = directory.listFiles()?.toList().orEmpty()
                check(candidates.size <= 1)
                for (file in candidates) {
                    check(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
                    check(file.name == "active.bin" || file.name.matches(Regex("unreadable-[a-f0-9-]{36}-${hash.take(12)}\\.bin")))
                    check(file.readBytes().contentEquals(bytes))
                }
                for (file in candidates) check(file.delete())
                if (!existed && directory.exists()) check(directory.delete())
            }
            try { assertEquals(before, settings.settings.first()) }
            catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) }
        }
        failure?.let { throw it }
    }

    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uuid = UUID.randomUUID().toString()
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
        suspend fun setup() {
            check(context.packageName == "com.ugallery.app.pdfacceptance")
            context.sendBroadcast(Intent("com.ugallery.SAF_FIXTURE").addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                .setComponent(ComponentName(instrumentation.context.packageName, "com.ugallery.app.SafCopyFixtureGrantReceiver"))
                .putExtra("target", context.packageName))
            withTimeout(5_000) { while (true) {
                try { val client = resolver.acquireUnstableContentProviderClient(endpoint); if (client != null) { client.close(); break } } catch (_: SecurityException) { }
                delay(25)
            } }
            check(resolver.call(endpoint, "fixtureSetup", uuid, Bundle().apply { putString("mode", "normal") })!!.getBoolean("ready")); providerReady = true
            check(!journal.exists() || journal.listFiles().orEmpty().isEmpty())
            source = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, currentName); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/move-controller-$uuid/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            val bitmap = Bitmap.createBitmap(48, 40, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.BLUE); resolver.openOutputStream(source, "w")!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            check(resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            sourceHash = hash(source)
        }
        suspend fun closeScope() { scope?.coroutineContext?.get(Job)?.cancelAndJoin(); scope = null }
        fun hash(uri: Uri): String = resolver.openInputStream(uri)!!.use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        suspend fun cleanup() {
            closeScope()
            if (::source.isInitialized && VerifiedMoveOperations.identity(resolver, source) != null) {
                if (::sourceHash.isInitialized) check(hash(source) == sourceHash)
                resolver.query(source, arrayOf("_display_name", "relative_path", "owner_package_name"), null, null, null)!!.use {
                    check(it.moveToFirst() && it.getString(0) == currentName && it.getString(1) == "Pictures/move-controller-$uuid/" && it.getString(2) == context.packageName)
                }
                check(resolver.delete(source, "_display_name=? AND relative_path=? AND owner_package_name=?",
                    arrayOf(currentName, "Pictures/move-controller-$uuid/", context.packageName)) == 1)
            }
            if (providerReady) check(resolver.call(endpoint, "fixtureCleanup", uuid, null)!!.getBoolean("absent"))
            // Journal cleanup is performed by the test only after matching the exact proof.

            // Existing fixtureCleanup revokes its own authority prefix; host preflight verifies no prior grant.
        }
    }
}
