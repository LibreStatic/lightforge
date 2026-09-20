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

/** Partial provider write failure and controller recreation; not operating-system process death. */
class MoveCopyRecoveryDeviceTest {
    @Test fun partialCopyReopensReviewsAndRetriesSameUriIntoVerifiedProof(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.setup(); var c = f.controller(); f.ready(c)
            assertTrue(runCatching { f.copy(c) }.isFailure)
            f.ready(c)
            withTimeout(10_000) { while (!f.status().getBoolean("writeFaultFinished")) delay(25) }
            assertTrue(f.status().getBoolean("partialFsynced"))
            val draft = requireNotNull(c.state.value.copyDraft)
            val destination = Uri.parse(requireNotNull(draft.destinationUri))
            check(f.status().getLong("destinationSize") in 1 until draft.bytes)
            assertEquals(f.sourceHash, f.hash(f.source))
            f.closeScope(); c = f.controller(); f.ready(c)
            assertEquals(draft, c.state.value.copyDraft)
            withContext(Dispatchers.Main.immediate) { c.reviewInterruptedCopy() }
            withTimeout(10_000) { c.state.first { it.copyReview != null && !it.busy } }
            withContext(Dispatchers.Main.immediate) { c.retryReviewedCopy() }
            f.authorize(c, true)
            withTimeout(20_000) { c.state.first { it.entry != null && it.copyDraft == null } }
            val proof = requireNotNull(c.state.value.entry).proof
            assertEquals(destination.toString(), proof.destinationUri)
            assertEquals(f.sourceHash, proof.sha256)
            assertEquals(f.sourceHash, f.hash(destination))
            assertEquals(f.sourceHash, f.hash(f.source))
            assertEquals(0, f.requests.get())
            println("MOVE_COPY_RECOVERY partial fsynced→new scope same journal→review→authorize retry→same URI verified proof; original hash intact; no delete request; no actual process death")
        } finally { f.cleanup() }
    }

    @Test fun staleSourceOrDestinationReviewRejectsRetryBeforeOverwrite(): Unit = runBlocking {
        for (mutateSource in listOf(true, false)) {
            val f = Fixture()
            try {
                f.setup(); val c = f.controller(); f.ready(c)
                assertTrue(runCatching { f.copy(c) }.isFailure); f.ready(c)
                withTimeout(10_000) { while (!f.status().getBoolean("writeFaultFinished")) delay(25) }
                val draft = requireNotNull(c.state.value.copyDraft)
                val destination = Uri.parse(requireNotNull(draft.destinationUri))
                withContext(Dispatchers.Main.immediate) { c.reviewInterruptedCopy() }
                withTimeout(10_000) { c.state.first { it.copyReview != null && !it.busy } }
                if (mutateSource) {
                    f.currentName = "changed-${f.uuid}.png"
                    check(f.resolver.update(f.source, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, f.currentName) }, null, null) == 1)
                } else {
                    f.resolver.openOutputStream(destination, "wa")!!.use { it.write(byteArrayOf(42)) }
                }
                val destBefore = f.hash(destination)
                withContext(Dispatchers.Main.immediate) { c.retryReviewedCopy() }
                f.authorize(c, true); f.ready(c)
                assertTrue(c.state.value.failed)
                assertNull(c.state.value.entry)
                assertEquals(draft, c.state.value.copyDraft)
                assertEquals(destBefore, f.hash(destination))
                assertEquals(f.sourceHash, f.hash(f.source))
                assertEquals(0, f.requests.get())
                println("MOVE_COPY_RECOVERY stale ${if (mutateSource) "source identity" else "destination bytes"} review rejects retry; destination preserved; no delete request")
            } finally { f.cleanup() }
        }
    }
    @Test fun reviewedDiscardRequiresAuthorizationAndRemovesOnlyPartialCopy(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.setup(); val c = f.controller(); f.ready(c)
            assertTrue(runCatching { f.copy(c) }.isFailure); f.ready(c)
            withTimeout(10_000) { while (!f.status().getBoolean("writeFaultFinished")) delay(25) }
            assertTrue(f.status().getBoolean("partialFsynced"))
            val draft = requireNotNull(c.state.value.copyDraft)
            val destination = Uri.parse(requireNotNull(draft.destinationUri))
            val partialHash = f.hash(destination)
            withContext(Dispatchers.Main.immediate) { c.reviewInterruptedCopy() }
            withTimeout(10_000) { c.state.first { it.copyReview != null && !it.busy } }
            withContext(Dispatchers.Main.immediate) { c.discardReviewedCopy() }
            f.authorize(c, false); f.ready(c)
            assertEquals(draft, c.state.value.copyDraft)
            assertEquals(partialHash, f.hash(destination))
            assertEquals(f.sourceHash, f.hash(f.source))
            assertTrue(f.status().getBoolean("destinationExists"))
            assertEquals(0, f.requests.get())
            // A denied authorization must not turn into an implicit discard.
            f.currentName = "changed-${f.uuid}.png"
            check(f.resolver.update(f.source, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, f.currentName) }, null, null) == 1)
            withContext(Dispatchers.Main.immediate) { c.discardReviewedCopy() }
            f.authorize(c, true)
            f.ready(c)
            assertNull("Discard state: ${c.state.value}", c.state.value.copyDraft)
            assertNull(c.state.value.entry)
            assertFalse(f.status().getBoolean("destinationExists"))
            val missing = runCatching {
                f.resolver.query(destination, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            }.getOrElse { failure ->
                if (failure is java.io.FileNotFoundException) false else throw failure
            }
            assertFalse(missing)
            assertEquals(f.sourceHash, f.hash(f.source))
            assertEquals(0, f.requests.get())
            println("MOVE_COPY_RECOVERY reviewed discard denied preserves partial/source; explicit approval removes same partial URI and draft; original intact; zero delete requests")
        } finally { f.cleanup() }
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
            check(resolver.call(endpoint, "fixtureSetup", uuid, Bundle().apply { putString("mode", "fail-write-once") })!!.getBoolean("ready")); providerReady = true
            check(!journal.exists())
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
            check(!journal.exists() || journal.deleteRecursively()); check(!journal.exists())
            // Existing fixtureCleanup revokes its own authority prefix; host preflight verifies no prior grant.
        }
    }
}
