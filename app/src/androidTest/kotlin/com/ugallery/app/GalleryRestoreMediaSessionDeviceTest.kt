package com.ugallery.app

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.PortableMediaKind
import com.ugallery.core.model.PortableSourceFacts
import com.ugallery.feature.settings.BackupManifest
import com.ugallery.feature.settings.LocalRestoreGalleryResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Each fixture owns new MediaStore rows plus an isolated journal directory; no real database. */
class GalleryRestoreMediaSessionDeviceTest {
    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val root =
            File(base.cacheDir, "restore-session-test-${UUID.randomUUID()}").apply { mkdirs() }

        override fun getFilesDir(): File = root

        override fun getApplicationContext(): Context = this
    }

    private fun fixture(block: suspend (FixtureContext, MutableList<Uri>) -> Unit) = runBlocking {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Native restore fixtures require the acceptance application",
            actual.packageName.endsWith(".pdfacceptance"),
        )
        val context = FixtureContext(actual)
        val owned = mutableListOf<Uri>()
        try {
            block(context, owned)
        } finally {
            context.root
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .forEach { file ->
                    runCatching {
                        val rows = JSONObject(file.readText()).getJSONArray("rows")
                        repeat(rows.length()) {
                            if (!rows.getJSONObject(it).isNull("uri"))
                                owned += Uri.parse(rows.getJSONObject(it).getString("uri"))
                        }
                    }
                }
            owned.distinct().forEach {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            context.root.deleteRecursively()
        }
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff428570.toInt())
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun entry(bytes: ByteArray, name: String = "restore.jpg", index: Int = 0) =
        BackupManifest.Entry(
            BackupManifest.path(index),
            name,
            "image/jpeg",
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            },
            UUID.randomUUID().toString(),
        )

    private fun facts(entry: BackupManifest.Entry, favorite: Boolean = false) =
        PortableSourceFacts(
            entry.sourceId,
            entry.sha256,
            entry.bytes,
            PortableMediaKind.Image,
            946684800000L,
            null,
            favorite,
            null,
        )

    private fun rows(context: Context, id: String): List<Uri> {
        val rows =
            JSONObject(File(context.filesDir, "gallery-restore-journal/$id.json").readText())
                .getJSONArray("rows")
        return buildList {
            repeat(rows.length()) {
                val row = rows.getJSONObject(it)
                if (!row.isNull("uri")) add(Uri.parse(row.getString("uri")))
            }
        }
    }

    private fun exists(context: Context, uri: Uri) =
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } == true

    private fun coldProcess(context: Context, id: String) {
        // Simulates the process-local live-operation registry vanishing, without restarting the
        // app.
        val field = GalleryRestoreMediaSession::class.java.getDeclaredField("active")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST") val active = field.get(null) as MutableSet<String>
        synchronized(active) {
            active.remove(File(context.filesDir, "gallery-restore-journal/$id.json").absolutePath)
        }
    }

    @Test
    fun identicalCopiesRemainDistinctFavoritesSurviveRescanAndReceiptProtectsAbort() =
        fixture { context, owned ->
            val bytes = jpeg()
            val first = entry(bytes)
            val second = entry(bytes, "restore.jpg", 1)
            val id = UUID.randomUUID().toString()
            var committed = false
            var mapping = emptyList<GalleryRestoredMedia>()
            val session =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(first.sourceId to facts(first, true), second.sourceId to facts(second)),
                    { _, values ->
                        mapping = values
                        owned += values.map { it.uri }
                        committed = true
                        LocalRestoreGalleryResult(values.size, 2)
                    },
                    { committed },
                )
            var primary: Throwable? = null
            try {
                session.stage(first, ByteArrayInputStream(bytes))
                session.stage(second, ByteArrayInputStream(bytes))
                val result = session.commit()
                assertEquals(2, result.files)
                assertEquals(2, mapping.map { it.key }.distinct().size)
                assertEquals(2, mapping.map { it.entry.sourceId }.distinct().size)
                mapping.forEach {
                    assertArrayEquals(
                        bytes,
                        context.contentResolver.openInputStream(it.uri)!!.use { stream ->
                            stream.readBytes()
                        },
                    )
                }
                val uri = mapping.first().uri
                val path =
                    context.contentResolver
                        .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!
                        .use { c ->
                            assertTrue(c.moveToFirst())
                            c.getString(0)
                        }
                val scanned = CompletableDeferred<Unit>()
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg")) {
                    _,
                    _ ->
                    scanned.complete(Unit)
                }
                withTimeout(15_000) { scanned.await() }
                val favorite =
                    context.contentResolver
                        .query(
                            uri,
                            arrayOf(MediaStore.MediaColumns.IS_FAVORITE),
                            null,
                            null,
                            null,
                        )!!
                        .use { c ->
                            assertTrue(c.moveToFirst())
                            c.getInt(0)
                        }
                assertEquals(1, favorite)
                session.abort()
                assertTrue(mapping.all { exists(context, it.uri) })
            } catch (error: Throwable) {
                primary = error
                // Capture the original failure before cleanup can change either row or journal.
                runCatching {
                    val diagnostic = JSONObject().put("original", error.stackTraceToString())
                    val journalFile = File(context.filesDir, "gallery-restore-journal/$id.json")
                    val saved = JSONObject(journalFile.readText())
                    diagnostic.put("journal", saved)
                    val fingerprints = org.json.JSONArray()
                    val recorded = saved.getJSONArray("rows")
                    repeat(recorded.length()) { index ->
                        val record = recorded.getJSONObject(index)
                        if (!record.isNull("uri")) {
                            val uri = Uri.parse(record.getString("uri"))
                            // Keep every exact fixture-created URI, including pre-mapping failures.
                            owned += uri
                            val fingerprint = JSONObject().put("uri", uri.toString())
                            val projection = arrayOf(
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.GENERATION_ADDED,
                                MediaStore.MediaColumns.GENERATION_MODIFIED,
                                MediaStore.MediaColumns.IS_PENDING,
                                MediaStore.MediaColumns.IS_FAVORITE,
                                MediaStore.MediaColumns.SIZE,
                            )
                            runCatching {
                                context.contentResolver.query(uri, projection, null, null, null)
                                    ?.use { cursor ->
                                        val exists = cursor.moveToFirst()
                                        fingerprint.put("exists", exists)
                                        if (exists) projection.forEachIndexed { column, name ->
                                            fingerprint.put(
                                                name,
                                                if (cursor.isNull(column)) JSONObject.NULL else cursor.getString(column),
                                            )
                                        }
                                    }
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val actual = input.readBytes() // Two bounded 8x8 JPEG fixtures only.
                                    fingerprint.put("actualBytes", actual.size)
                                    fingerprint.put("actualSha256", MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
                                }
                            }.onFailure { fingerprint.put("observationError", it.toString()) }
                            fingerprints.put(fingerprint)
                        }
                    }
                    diagnostic.put("fingerprints", fingerprints)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("stream", "WRITER ORIGINAL FAILURE $diagnostic\n")
                    })
                }.onFailure { error.addSuppressed(it) }
                throw error
            } finally {
                try {
                    session.abort()
                } catch (cleanup: Throwable) {
                    val original = primary
                    if (original != null) original.addSuppressed(cleanup) else throw cleanup
                }
            }
        }

    @Test
    fun failedMetadataCommitRollsBackOnlyNewPublishedCopies() = fixture { context, owned ->
        val bytes = jpeg()
        val original = entry(bytes)
        val id = UUID.randomUUID().toString()
        val session =
            GalleryRestoreMediaSession(
                context,
                id,
                mapOf(original.sourceId to facts(original)),
                { _, _ -> throw IOException("Injected Room failure") },
                { false },
            )
        session.stage(original, ByteArrayInputStream(bytes))
        val copies = rows(context, id)
        owned += copies
        try {
            session.commit()
            fail("Expected metadata failure")
        } catch (_: IOException) {}
        session.abort()
        assertTrue(copies.none { exists(context, it) })
        assertFalse(File(context.filesDir, "gallery-restore-journal/$id.json").exists())
    }

    @Test
    fun editedVerifiedPendingGenerationIsRetainedEvenAfterNameIsRestored() =
        fixture { context, owned ->
            val bytes = jpeg()
            val original = entry(bytes)
            val id = UUID.randomUUID().toString()
            val session =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    { _, _ -> LocalRestoreGalleryResult(1, 0) },
                    { false },
                )
            session.stage(original, ByteArrayInputStream(bytes))
            val uri = rows(context, id).single()
            owned += uri
            val name =
                context.contentResolver
                    .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!
                    .use { c ->
                        c.moveToFirst()
                        c.getString(0)
                    }
            assertEquals(
                1,
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "edited.jpg")
                    },
                    null,
                    null,
                ),
            )
            assertEquals(
                1,
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                    null,
                    null,
                ),
            )
            try {
                session.abort()
                fail("Expected changed-copy retention")
            } catch (_: IOException) {}
            assertTrue(exists(context, uri))
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
        }

    @Test
    fun recoveryRemovesVerifiedPendingOrphansAndRetainsUnrelatedRows() = fixture { context, owned ->
        val bytes = jpeg()
        val original = entry(bytes)
        val id = UUID.randomUUID().toString()
        val session =
            GalleryRestoreMediaSession(
                context,
                id,
                mapOf(original.sourceId to facts(original)),
                { _, _ -> LocalRestoreGalleryResult(1, 0) },
                { false },
            )
        session.stage(original, ByteArrayInputStream(bytes))
        val copies = rows(context, id)
        owned += copies
        coldProcess(context, id)
        val unrelated =
            context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "unrelated.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Pictures/UGallery Restore/${UUID.randomUUID()}/",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        owned += unrelated
        context.contentResolver.openOutputStream(unrelated)!!.use { it.write(bytes) }
        val result = GalleryRestoreMediaSession.recover(context) { false }
        assertTrue(exists(context, unrelated))
        assertArrayEquals(
            bytes,
            context.contentResolver.openInputStream(unrelated)!!.use { it.readBytes() },
        )
        assertEquals(1, result.removed)
        assertEquals(0, result.retained)
        assertTrue(copies.none { exists(context, it) })
        assertFalse(File(context.filesDir, "gallery-restore-journal/$id.json").exists())
    }

    @Test
    fun recoveryReceiptPreservesCopiesAfterUncertainCommitResponse() = fixture { context, owned ->
        val bytes = jpeg()
        val original = entry(bytes)
        val id = UUID.randomUUID().toString()
        var committed = false
        val session =
            GalleryRestoreMediaSession(
                context,
                id,
                mapOf(original.sourceId to facts(original)),
                { _, _ ->
                    committed = true
                    throw IOException("Response lost after receipt")
                },
                { committed },
            )
        session.stage(original, ByteArrayInputStream(bytes))
        val copies = rows(context, id)
        owned += copies
        try {
            session.commit()
            fail("Expected lost response")
        } catch (_: IOException) {}
        coldProcess(context, id)
        val result = GalleryRestoreMediaSession.recover(context) { committed }
        assertEquals(1, result.committed)
        assertEquals(0, result.removed)
        assertTrue(copies.all { exists(context, it) })
    }

    @Test
    fun missingReceiptDatabaseIsNotTreatedAsUncommitted() = fixture { context, owned ->
        val bytes = jpeg()
        val original = entry(bytes)
        val id = UUID.randomUUID().toString()
        val session =
            GalleryRestoreMediaSession(
                context,
                id,
                mapOf(original.sourceId to facts(original)),
                { _, _ -> LocalRestoreGalleryResult(1, 0) },
                { throw IOException("Database unavailable") },
            )
        session.stage(original, ByteArrayInputStream(bytes))
        val copies = rows(context, id)
        owned += copies
        try {
            session.abort()
            fail("Expected unavailable receipt lookup")
        } catch (_: IOException) {}
        assertTrue(copies.all { exists(context, it) })
        assertTrue(File(context.filesDir, "gallery-restore-journal/$id.json").exists())
    }

    @Test
    fun recoveryRetainsRenamedInsertMissingFromPlannedJournal() = fixture { context, owned ->
        val bytes = jpeg()
        val original = entry(bytes)
        val id = UUID.randomUUID().toString()
        val session =
            GalleryRestoreMediaSession(
                context,
                id,
                mapOf(original.sourceId to facts(original)),
                { _, _ -> LocalRestoreGalleryResult(1, 0) },
                { false },
            )
        session.stage(original, ByteArrayInputStream(bytes))
        val uri = rows(context, id).single()
        owned += uri
        assertEquals(
            1,
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "restore (1).jpg")
                },
                null,
                null,
            ),
        )
        val file = File(context.filesDir, "gallery-restore-journal/$id.json")
        val json = JSONObject(file.readText())
        json
            .getJSONArray("rows")
            .getJSONObject(0)
            .put("phase", "planned")
            .put("uri", JSONObject.NULL)
            .put("actualName", JSONObject.NULL)
            .put("added", -1)
            .put("modified", -1)
        file.writeText(json.toString())
        coldProcess(context, id)
        repeat(2) {
            val result = GalleryRestoreMediaSession.recover(context) { false }
            assertEquals(0, result.removed)
            assertEquals(1, result.retained)
            assertTrue(exists(context, uri))
            assertTrue(file.exists())
        }
        assertArrayEquals(
            bytes,
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
        )
    }

    @Test
    fun recoveryRetainsEditedPendingPublicationIntent() = fixture { context, owned ->
        val bytes = jpeg()
        val original = entry(bytes)
        val id = UUID.randomUUID().toString()
        val session =
            GalleryRestoreMediaSession(
                context,
                id,
                mapOf(original.sourceId to facts(original)),
                { _, _ -> LocalRestoreGalleryResult(1, 0) },
                { false },
            )
        session.stage(original, ByteArrayInputStream(bytes))
        val uri = rows(context, id).single()
        owned += uri
        val file = File(context.filesDir, "gallery-restore-journal/$id.json")
        val json = JSONObject(file.readText())
        val row = json.getJSONArray("rows").getJSONObject(0)
        row.put("phase", "publishing")
        file.writeText(json.toString())
        listOf("edited-publication.jpg", row.getString("actualName")).forEach { name ->
            assertEquals(
                1,
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                    null,
                    null,
                ),
            )
        }
        coldProcess(context, id)
        val result = GalleryRestoreMediaSession.recover(context) { false }
        assertEquals(0, result.removed)
        assertEquals(1, result.retained)
        assertTrue(exists(context, uri))
        assertTrue(file.exists())
        assertArrayEquals(
            bytes,
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
        )
    }

    @Test
    fun conditionalCleanupPreservesRowChangedAfterObservedFingerprint() =
        fixture { context, owned ->
            val bytes = jpeg()
            val original = entry(bytes)
            val id = UUID.randomUUID().toString()
            val session =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    { _, _ -> LocalRestoreGalleryResult(1, 0) },
                    { false },
                )
            session.stage(original, ByteArrayInputStream(bytes))
            val uri = rows(context, id).single()
            owned += uri
            val file = File(context.filesDir, "gallery-restore-journal/$id.json")
            val row = JSONObject(file.readText()).getJSONArray("rows").getJSONObject(0)
            assertEquals(
                1,
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "changed-after-observation.jpg")
                    },
                    null,
                    null,
                ),
            )
            fun fingerprint(): List<String> =
                context.contentResolver
                    .query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.GENERATION_ADDED,
                            MediaStore.MediaColumns.GENERATION_MODIFIED,
                            MediaStore.MediaColumns.IS_PENDING,
                        ),
                        null,
                        null,
                        null,
                    )!!
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        (0 until cursor.columnCount).map { cursor.getString(it) }
                    }
            val current = fingerprint()
            assertEquals(context.packageName, current[0])
            assertEquals(row.getString("relativePath"), current[1])
            assertEquals("changed-after-observation.jpg", current[2])
            assertEquals(row.getLong("added"), current[3].toLong())
            assertTrue(current[4].toLong() > row.getLong("modified"))
            assertEquals("1", current[5])
            try {
                assertEquals(
                    0,
                    GalleryRestoreMediaSession.deleteUnchanged(
                        context,
                        uri,
                        context.packageName,
                        row.getString("relativePath"),
                        row.getLong("added"),
                        row.getLong("modified"),
                    ),
                )
            } catch (_: SecurityException) {
                // SDK35 can deny a stale URI predicate instead of returning zero affected rows.
                // Production cleanup treats either outcome as retained, never as deletion success.
            }
            assertTrue(exists(context, uri))
            assertEquals(current, fingerprint())
            try {
                session.abort()
                fail("Expected retained changed row")
            } catch (_: IOException) {}
            assertTrue(file.exists())
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
        }

    @Test
    fun durableTaskResumeReusesVerifiedMediaIdentityAndSkipsStartupCleanup() =
        fixture { context, owned ->
            val bytes = jpeg()
            val original = entry(bytes)
            val id = UUID.randomUUID().toString()
            var committed = false
            val commit: suspend (String, List<GalleryRestoredMedia>) -> LocalRestoreGalleryResult =
                { _, values ->
                    committed = true
                    LocalRestoreGalleryResult(values.size, 1)
                }
            val first =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    commit,
                    { committed },
                )
            first.stage(original, ByteArrayInputStream(bytes))
            val uri = rows(context, id).single()
            owned += uri
            first.pause()
            val recovery = GalleryRestoreMediaSession.recover(context, setOf(id)) { committed }
            assertEquals(0, recovery.removed)
            assertTrue(exists(context, uri))
            val resumed =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    commit,
                    { committed },
                    resume = true,
                )
            resumed.stage(original, ByteArrayInputStream(bytes))
            assertEquals(listOf(uri), rows(context, id))
            assertEquals(1, resumed.commit().files)
            resumed.abort()
            assertTrue(exists(context, uri))
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
        }

    @Test
    fun durableTaskResumeVerifiesInterruptedPrefixThenRestartsOnlyItsPartialEntry() =
        fixture { context, owned ->
            val bytes = jpeg()
            val original = entry(bytes)
            val id = UUID.randomUUID().toString()
            var committed = false
            val commit: suspend (String, List<GalleryRestoredMedia>) -> LocalRestoreGalleryResult =
                { _, values ->
                    owned += values.map { it.uri }
                    committed = true
                    LocalRestoreGalleryResult(values.size, 0)
                }
            val first =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    commit,
                    { committed },
                )
            val broken =
                object : java.io.InputStream() {
                    var offset = 0

                    override fun read(): Int {
                        if (offset == 100) throw IOException("Interrupted source")
                        return bytes[offset++].toInt() and 255
                    }

                    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                        if (offset == 100) throw IOException("Interrupted source")
                        val count = minOf(length, 100 - offset)
                        bytes.copyInto(buffer, start, offset, offset + count)
                        offset += count
                        return count
                    }
                }
            try {
                first.stage(original, broken)
                fail("Expected interrupted partial")
            } catch (_: IOException) {}
            val partial = rows(context, id).single()
            owned += partial
            first.pause()
            val resumed =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    commit,
                    { committed },
                    resume = true,
                )
            try {
                resumed.stage(original, ByteArrayInputStream(bytes))
                fail("Expected verified partial regeneration")
            } catch (_: com.ugallery.feature.settings.LocalBackupTaskRetryEntry) {}
            assertFalse(exists(context, partial))
            resumed.stage(original, ByteArrayInputStream(bytes))
            assertEquals(1, resumed.commit().files)
            assertTrue(owned.last() != partial)
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(owned.last())!!.use { it.readBytes() },
            )
        }

    @Test
    fun cancellationRetainsChangedWritingPartialWithoutVerifiedArchivePrefix() =
        fixture { context, owned ->
            val bytes = jpeg()
            val original = entry(bytes)
            val id = UUID.randomUUID().toString()
            val session =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    { _, _ -> LocalRestoreGalleryResult(1, 0) },
                    { false },
                )
            val interrupted =
                object : java.io.InputStream() {
                    var sent = false

                    override fun read(): Int = throw IOException("Interrupted")

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (sent) throw IOException("Interrupted")
                        sent = true
                        bytes.copyInto(buffer, offset, 0, minOf(100, length))
                        return minOf(100, length)
                    }
                }
            try {
                session.stage(original, interrupted)
                fail("Expected interruption")
            } catch (_: IOException) {}
            val uri = rows(context, id).single()
            owned += uri
            session.pause()
            val changed = byteArrayOf(66, 77, 88)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(changed) }
            val resumed =
                GalleryRestoreMediaSession(
                    context,
                    id,
                    mapOf(original.sourceId to facts(original)),
                    { _, _ -> LocalRestoreGalleryResult(1, 0) },
                    { false },
                    resume = true,
                )
            try {
                resumed.verifyBeforeAbort(original, ByteArrayInputStream(bytes))
                fail("Changed prefix accepted")
            } catch (_: IllegalArgumentException) {}
            try {
                resumed.abort()
                fail("Changed partial deleted")
            } catch (_: IOException) {}
            assertTrue(exists(context, uri))
            assertArrayEquals(
                changed,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
            assertTrue(File(context.filesDir, "gallery-restore-journal/$id.json").exists())
        }
}
