package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.MediaKind
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaStore byte protocol. No renderer stub, shared-row cleanup or publication on failure. */
@RunWith(AndroidJUnit4::class)
class PendingFilePublicationGuardDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver

    @Test fun securityFailureAfterVerificationRemovesPendingAndPreservesSource() = runBlocking {
        exercise(cancelAtGuard = false)
    }

    @Test fun cancellationWhileFinalGuardSuspendsRemovesPendingAndPreservesSource() = runBlocking {
        exercise(cancelAtGuard = true)
    }

    private suspend fun exercise(cancelAtGuard: Boolean) {
        val uuid = UUID.randomUUID().toString()
        val evidence = File(context.filesDir, "pending-file-guard-$uuid").apply { check(mkdir()) }
        val source = File(context.cacheDir.canonicalFile, "pending-file-guard-$uuid.jpg")
        check(source.createNewFile())
        val bytes = ByteArray(64 * 1024) { ((it * 31 + 7) % 251).toByte() }
        source.outputStream().use { it.write(bytes) }
        val sourceHash = sha(bytes)
        val sourcePath = source.canonicalPath
        val spec = MediaWriteSpec(MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaKind.Image,
            "pending-file-guard-$uuid.jpg", "image/jpeg", "Pictures/LightforgeWriterTest/guard-$uuid/")
        val baseline = rows(spec)
        check(baseline.isEmpty())
        File(evidence, "fixture.json").writeText(JSONObject()
            .put("fixtureUuid", uuid).put("scenario", if (cancelAtGuard) "cancel" else "security")
            .put("sourcePath", sourcePath).put("sourceSha256", sourceHash).put("sourceSize", bytes.size)
            .put("ownerPackage", context.packageName).put("relativePath", spec.relativePath)
            .put("displayName", spec.displayName).put("mimeType", spec.mimeType).toString(2))
        report("fixture=$uuid evidence=${evidence.absolutePath} source=$sourcePath sha256=$sourceHash")
        val pending = AtomicReference<PendingWriteSnapshot?>(null)
        val firstPending = AtomicReference<PendingWriteSnapshot?>(null)
        val verifyingCalls = AtomicInteger(0)
        val guardCalls = AtomicInteger(0)
        var observedException: String? = null
        var published: PublishedCopy? = null
        var passed = false
        var cleaned = false
        val writer = PendingMediaWriter(resolver, persistPending = { snapshot ->
            if (snapshot != null) {
                check(firstPending.compareAndSet(null, snapshot)) { "Unexpected second pending destination" }
            }
            pending.set(snapshot)
        })
        fun inspectGuard() {
            assertEquals(1, verifyingCalls.get())
            assertEquals(1, guardCalls.incrementAndGet())
            val snapshot = checkNotNull(pending.get())
            assertEquals(spec, snapshot.spec)
            assertEquals(snapshot, firstPending.get())
            val uri = Uri.parse(snapshot.pendingUri)
            val before = metadata(uri)
            assertEquals(context.packageName, before[1])
            assertEquals(spec.displayName, before[2])
            assertEquals(spec.relativePath, before[3])
            assertEquals(spec.mimeType, before[4])
            assertEquals("1", before[8]); assertEquals("0", before[9])
            assertTrue(checkNotNull(before[5]).toLong() >= 0)
            assertTrue(checkNotNull(before[6]).toLong() >= checkNotNull(before[5]).toLong())
            assertEquals(listOf(before), rows(spec))
            val actual = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals(bytes, actual)
            assertEquals(sourceHash, sha(actual))
            assertEquals(before, metadata(uri))
            assertEquals(sourceHash, sha(source.readBytes()))
            File(evidence, "guard.json").writeText(JSONObject()
                .put("fixtureUuid", uuid).put("pendingUri", snapshot.pendingUri)
                .put("pendingMetadata", JSONArray(before.map { it ?: JSONObject.NULL }))
                .put("columns", JSONArray(columns.toList())).put("pendingSha256", sha(actual))
                .put("pendingBytes", actual.size).put("sourceSha256", sourceHash)
                .put("phase", "BEFORE_PUBLICATION").toString(2))
        }
        try {
            if (cancelAtGuard) supervisorScope {
                val reached = CompletableDeferred<Unit>()
                val job = async {
                    published = writer.publishFile(source, spec,
                        onVerifying = { verifyingCalls.incrementAndGet() },
                        beforePublish = {
                            inspectGuard()
                            reached.complete(Unit)
                            awaitCancellation()
                        })
                }
                job.invokeOnCompletion { failure -> if (failure != null) reached.completeExceptionally(failure) }
                try {
                    withTimeout(15_000) { reached.await() }
                    assertFalse("Writer must still be suspended before publication", job.isCompleted)
                    job.cancel(CancellationException("Owned fixture final guard cancellation $uuid"))
                    withTimeout(10_000) { job.join() }
                    val failure = runCatching { job.await() }.exceptionOrNull()
                    assertTrue("Expected cancelled writer, got $failure", failure is CancellationException)
                    assertTrue(job.isCancelled)
                    observedException = failure!!::class.java.name
                } finally { job.cancelAndJoin() }
            } else {
                val failure = runCatching {
                    published = writer.publishFile(source, spec,
                        onVerifying = { verifyingCalls.incrementAndGet() },
                        beforePublish = { inspectGuard(); throw SecurityException("Owned fixture source access revoked $uuid") })
                }.exceptionOrNull()
                assertTrue("Expected guard SecurityException, got $failure", failure is SecurityException)
                assertEquals("Owned fixture source access revoked $uuid", failure!!.message)
                observedException = failure::class.java.name
            }
            assertEquals(1, verifyingCalls.get()); assertEquals(1, guardCalls.get())
            assertNull("A guard rejection must not return a published copy", published)
            val snapshot = checkNotNull(firstPending.get())
            assertNull("Pending persistence must be cleared after rollback", pending.get())
            assertFalse("Exact pending URI remains after rollback", exists(Uri.parse(snapshot.pendingUri)))
            assertEquals(baseline, rows(spec))
            assertEquals(sourcePath, source.canonicalPath)
            assertEquals(sourceHash, sha(source.readBytes()))
            passed = true
        } finally {
            try {
                if (passed) {
                    assertEquals(sourcePath, source.canonicalPath)
                    assertEquals(sourceHash, sha(source.readBytes()))
                    assertEquals(baseline, rows(spec))
                    assertFalse(exists(Uri.parse(checkNotNull(firstPending.get()).pendingUri)))
                    check(source.delete() && !source.exists())
                    cleaned = true
                }
            } finally {
                File(evidence, "result.json").writeText(JSONObject()
                    .put("fixtureUuid", uuid).put("status", if (passed && cleaned) "PASS" else "FAIL")
                    .put("scenario", if (cancelAtGuard) "cancel" else "security")
                    .put("exceptionClass", observedException ?: JSONObject.NULL)
                    .put("pendingUri", firstPending.get()?.pendingUri ?: JSONObject.NULL)
                    .put("returnedPublication", published?.uri?.toString() ?: JSONObject.NULL)
                    .put("verifyingCalls", verifyingCalls.get()).put("guardCalls", guardCalls.get())
                    .put("pendingRemoved", passed).put("sourceIntactBeforeCleanup", passed)
                    .put("cleanupComplete", cleaned).toString(2))
                report("${if (passed && cleaned) "PASS" else "FAIL retained"} fixture=$uuid result=${File(evidence, "result.json").absolutePath}")
            }
        }
    }

    private val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path", "mime_type",
        "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
    private fun queryArgs() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    private fun metadata(uri: Uri): List<String?> = checkNotNull(resolver.query(uri, columns, queryArgs(), null)).use { cursor ->
        check(cursor.moveToFirst())
        columns.indices.map { cursor.getString(it) }.also { check(!cursor.moveToNext()) }
    }
    private fun exists(uri: Uri): Boolean = checkNotNull(resolver.query(uri, arrayOf("_id"), queryArgs(), null)).use { it.moveToFirst() }
    private fun rows(spec: MediaWriteSpec): List<List<String?>> {
        val args = queryArgs().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "owner_package_name=? AND relative_path=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, spec.relativePath))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
        }
        return checkNotNull(resolver.query(MediaStore.Images.Media.getContentUri(spec.destinationVolume), columns, args, null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(columns.indices.map { cursor.getString(it) }) }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
