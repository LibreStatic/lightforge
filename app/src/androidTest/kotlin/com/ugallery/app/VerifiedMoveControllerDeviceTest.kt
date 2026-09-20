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

/** Real copy/proof/controller; request factory never presents or dispatches a platform delete. */
class VerifiedMoveControllerDeviceTest {
    @Test fun denyThenAllowCancelAndChangedOriginalBlocksFreshRetry() = fixture { f ->
        var c = f.controller()
        f.ready(c)
        f.copy(c)
        f.authorize(c, false)
        assertEquals(0, f.requests.get())
        withContext(Dispatchers.Main.immediate) { c.requestAttempt() }
        val launch = f.authorizeAndLaunch(c)
        withContext(Dispatchers.Main.immediate) { c.onSystemResult(launch.requestId, false) }
        f.ready(c)
        assertEquals(VerifiedMovePhase.Cancelled, c.state.value.entry?.phase)
        val before = VerifiedMoveOperations.identity(f.resolver, f.source)
        f.currentName = "changed-${f.uuid}.png"
        assertEquals(1, f.resolver.update(f.source, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, f.currentName) }, null, null))
        val after = VerifiedMoveOperations.identity(f.resolver, f.source)
        check(after != before) { "MediaStore did not change identity" }
        withContext(Dispatchers.Main.immediate) { c.requestAttempt() }
        f.authorize(c, true)
        f.ready(c)
        assertTrue(c.state.value.failed)
        assertEquals(1, f.requests.get())
        assertEquals(f.sourceHash, f.hash(f.source))
        println("MOVE_CONTROLLER deny=no request; allow=1 inert request; cancel retained; changed identity retry rejected; source bytes unchanged")
    }

    @Test fun reopenedJournalValidRetryLaunchesAndMissingCopyRejectsAnotherAttempt() = fixture { f ->
        var c = f.controller(); f.ready(c); val destination = f.copy(c)
        f.authorize(c, false)
        val originalProof = c.state.value.entry!!.proof
        f.closeScope()
        c = f.controller(); f.ready(c)
        assertEquals(originalProof, c.state.value.entry!!.proof)
        assertNull(c.state.value.authorization)
        withContext(Dispatchers.Main.immediate) { c.requestAttempt() }
        val launch = f.authorizeAndLaunch(c)
        withContext(Dispatchers.Main.immediate) { c.onSystemResult(launch.requestId, false) }
        f.ready(c)
        assertTrue(DocumentsContract.deleteDocument(f.resolver, destination))
        withContext(Dispatchers.Main.immediate) { c.requestAttempt() }
        f.authorize(c, true); f.ready(c)
        assertTrue(c.state.value.failed); assertEquals(1, f.requests.get())
        assertEquals(f.sourceHash, f.hash(f.source))
        withContext(Dispatchers.Main.immediate) { c.forget() }; f.ready(c)
        assertNull(c.state.value.entry)
        println("MOVE_CONTROLLER same journal reopened; explicit valid retry=1 inert request; cancelled; deleted destination retry rejected; forget no source deletion")
    }

    @Test fun queuedIntentDoesNotAuthorizeChangedDestinationAtUiHandoff() = fixture { f ->
        val c = f.controller(); f.ready(c); val destination = f.copy(c)
        val launch = f.authorizeAndLaunch(c)
        assertTrue(DocumentsContract.deleteDocument(f.resolver, destination))
        assertFalse(withContext(Dispatchers.Main.immediate) { c.confirmLaunch(launch.requestId) })
        f.ready(c)
        assertTrue(c.state.value.failed)
        assertEquals(VerifiedMovePhase.RequestFailed, c.state.value.entry?.phase)
        assertEquals(f.sourceHash, f.hash(f.source))
        println("MOVE_CONTROLLER queued inert intent refused at UI handoff after destination deletion; original unchanged")
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { f.setup(); block(f) } finally { f.cleanup() }
    }
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uuid = UUID.randomUUID().toString()
        val authority = instrumentation.context.packageName + ".safcopyfixture"
        val endpoint = Uri.parse("content://$authority")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "$uuid:root")
        val journal = File(context.cacheDir.canonicalFile, "move-controller-$uuid")
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
            check(!journal.exists())
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
        suspend fun controller(): VerifiedMoveController = withContext(Dispatchers.Main.immediate) {
            val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); scope = activeScope
            VerifiedMoveController(context, activeScope, journalDirectory = journal, requestFactory = MediaStoreRequestFactory { _, _ ->
                requests.incrementAndGet()
                PendingIntent.getBroadcast(context, 0, Intent("com.ugallery.MOVE_INERT_$uuid").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
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
        suspend fun cleanup() {
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
            check(!journal.exists() || journal.deleteRecursively()); check(!journal.exists())
            // Existing fixtureCleanup revokes its own authority prefix; host preflight verifies no prior grant.
        }
    }
}
