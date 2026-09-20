package com.ugallery.feature.privatealbum

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.ugallery.core.designsystem.UGalleryTheme
import org.junit.runner.RunWith

/** Small owned exports; real SQLCipher/AEAD/MediaStore, with independent UUID cleanup receipts. */
@RunWith(AndroidJUnit4::class)
class PrivateExportPublicationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private suspend fun seed(f: Fixture, repository: PrivateAlbumRepository): Long {
        val result = f.import(repository)
        assertTrue(result.error, result.success)
        return requireNotNull(result.mediaId)
    }
    private fun attach(f: Fixture, repository: PrivateAlbumRepository, stop: String? = null) {
        repository.privateExportCheckpoint = { receipt ->
            if (receipt.phase == "Inserted") f.track(Uri.parse(JSONObject(requireNotNull(receipt.snapshotJson)).getString("uri")))
            assertTrue(f.context.cacheDir.walkTopDown().none { it.isFile && it.name.startsWith("private-export-") })
            if (receipt.phase == stop) throw IOException("OWNED_EXPORT_STOP_$stop")
        }
    }

    @Test fun publishedRecoveryReopensWithoutOriginalAndWithoutDuplicate() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database); attach(f, repository)
            val id = seed(f, repository)
            val entity = requireNotNull(f.database.privateMediaDao().getById(id))
            val original = File(entity.containerPath); val cipherSha = sha(original)
            val uri = requireNotNull(repository.exportToMediaStore(id, f.master))
            assertEquals(f.sourceSha, publicSnapshot(f.base, uri)!!.getString("sha256"))
            assertEquals("0", publicSnapshot(f.base, uri)!!.getString("is_pending"))
            val saved = repository.exportRecoveries().single()
            assertEquals(PrivateExportStatus.Published, saved.status)
            val held = File(original.parentFile, original.name + ".held")
            check(original.renameTo(held))
            try {
                f.reopen()
                val reopened = PrivateAlbumRepository(f.context, f.database)
                val review = reopened.exportRecoveries().single()
                assertEquals(saved.id, review.id)
                assertEquals(uri, reopened.completeExport(review.id, review.proof))
                assertEquals(uri, reopened.exportToMediaStore(id, f.master))
                assertEquals(1, f.database.privateExportDao().list().size)
                val finalReview = reopened.exportRecoveries().single()
                reopened.forgetExport(finalReview.id, finalReview.proof)
                assertTrue(reopened.exportRecoveries().isEmpty())
                assertEquals(f.sourceSha, publicSnapshot(f.base, uri)!!.getString("sha256"))
            } finally { check(held.renameTo(original)) }
            assertEquals(cipherSha, sha(original)); assertEquals(f.sourceSha, sha(f.source))
            f.record("PASS", "published-source-independent", cipherSha); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun readyInterruptionCompletesSamePendingAfterReopen() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database); attach(f, repository, "Ready")
            val id = seed(f, repository)
            val result = runCatching { repository.exportToMediaStore(id, f.master) }
            assertEquals("OWNED_EXPORT_STOP_Ready", result.exceptionOrNull()?.message)
            val review = repository.exportRecoveries().single()
            assertEquals(PrivateExportStatus.Ready, review.status)
            val uri = Uri.parse(requireNotNull(review.uri))
            assertEquals("1", publicSnapshot(f.base, uri)!!.getString("is_pending"))
            f.reopen()
            val reopened = PrivateAlbumRepository(f.context, f.database)
            val current = reopened.exportRecoveries().single()
            assertEquals(uri, reopened.completeExport(current.id, current.proof))
            assertEquals(PrivateExportStatus.Published, reopened.exportRecoveries().single().status)
            assertEquals("0", publicSnapshot(f.base, uri)!!.getString("is_pending"))
            assertEquals(f.sourceSha, publicSnapshot(f.base, uri)!!.getString("sha256"))
            assertEquals(f.sourceSha, sha(f.source)); f.record("PASS", "ready-reopen", null); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun partialStaleProofCannotDeleteChangedDestination() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database); attach(f, repository, "Inserted")
            val id = seed(f, repository)
            assertEquals("OWNED_EXPORT_STOP_Inserted", runCatching { repository.exportToMediaStore(id, f.master) }.exceptionOrNull()?.message)
            val review = repository.exportRecoveries().single(); assertEquals(PrivateExportStatus.Partial, review.status)
            val uri = Uri.parse(JSONObject(requireNotNull(f.database.privateExportDao().get(review.id)!!.snapshotJson)).getString("uri"))
            val before = publicSnapshot(f.base, uri)!!
            assertEquals(1, f.base.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "changed-${f.id}.png") }, null, null))
            val changed = publicSnapshot(f.base, uri)!!
            assertTrue(runCatching { repository.discardExport(review.id, review.proof) }.isFailure)
            assertEquals(changed.toString(), publicSnapshot(f.base, uri).toString())
            assertEquals(PrivateExportStatus.Conflict, repository.exportRecoveries().single().status)
            assertEquals(1, f.base.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, before.getString("_display_name")) }, null, null))
            val fresh = repository.exportRecoveries().single(); assertEquals(PrivateExportStatus.Partial, fresh.status)
            repository.discardExport(fresh.id, fresh.proof)
            assertNull(publicSnapshot(f.base, uri)); assertTrue(repository.exportRecoveries().isEmpty())
            assertEquals(f.sourceSha, sha(f.source)); f.record("PASS", "partial-stale-proof", null); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun tamperedCiphertextNeverPublishesOrCreatesPlaintextCache() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database); attach(f, repository)
            val id = seed(f, repository)
            val file = File(requireNotNull(f.database.privateMediaDao().getById(id)).containerPath)
            val original = file.readBytes(); val changed = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            FileOutputStream(file).use { it.write(changed); it.fd.sync() }
            try {
                assertTrue(runCatching { repository.exportToMediaStore(id, f.master) }.isFailure)
                assertTrue(file.readBytes().contentEquals(changed))
                val review = repository.exportRecoveries().single()
                assertNotEquals(PrivateExportStatus.Published, review.status)
                assertTrue(f.context.cacheDir.walkTopDown().none { it.isFile })
                if (review.uri != null) assertEquals("1", publicSnapshot(f.base, Uri.parse(review.uri))!!.getString("is_pending"))
            } finally { FileOutputStream(file).use { it.write(original); it.fd.sync() } }
            assertTrue(original.contentEquals(file.readBytes())); assertEquals(f.sourceSha, sha(f.source))
            f.record("PASS", "tampered-source", sha(file)); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun encryptedVersionThreeMigrationPreservesMediaAndIndexWrapper() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database)
            val id = seed(f, repository); val before = requireNotNull(f.database.privateMediaDao().getById(id))
            val cipherSha = sha(File(before.containerPath))
            // The only v4 addition is this table/index; all other tables are exact retained v3.
            f.database.openHelper.writableDatabase.execSQL("DROP TABLE private_export_receipts")
            f.database.openHelper.writableDatabase.version = 3
            f.database.close()
            val keyFiles = f.context.noBackupFilesDir.listFiles()!!.filter { it.extension == "key" }.associate { it.name to sha(it) }
            f.reopen()
            val after = requireNotNull(f.database.privateMediaDao().getById(id))
            assertEquals(4, f.database.openHelper.writableDatabase.version)
            assertEquals(before.containerPath, after.containerPath)
            assertArrayEquals(before.encryptedDataKey, after.encryptedDataKey); assertArrayEquals(before.dataKeyIv, after.dataKeyIv)
            assertEquals(cipherSha, sha(File(after.containerPath))); assertTrue(f.database.privateExportDao().list().isEmpty())
            assertEquals(keyFiles, f.context.noBackupFilesDir.listFiles()!!.filter { it.extension == "key" }.associate { it.name to sha(it) })
            f.record("PASS", "encrypted-migration3-4", cipherSha); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun recoveryScreenCompletesReviewedOutputAndClosesTrackingOnly() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database); attach(f, repository, "Ready")
            val id = seed(f, repository)
            assertEquals("OWNED_EXPORT_STOP_Ready", runCatching { repository.exportToMediaStore(id, f.master) }.exceptionOrNull()?.message)
            val review = repository.exportRecoveries().single()
            val uri = Uri.parse(requireNotNull(review.uri))
            var authRequests = 0
            val showScreen = androidx.compose.runtime.mutableStateOf(true)
            compose.setContent { if (showScreen.value) UGalleryTheme(darkTheme = false) {
                PrivateExportRecoveryContent(repository, true, {}, { authRequests++ })
            } }
            val row = "private-export-recovery-row-${review.id}"
            fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
            waitTag(row); compose.onNodeWithTag(row).performClick()
            compose.onNodeWithTag("private-export-recovery-complete").performScrollTo().performClick()
            waitTag(row)
            assertEquals("0", publicSnapshot(f.base, uri)!!.getString("is_pending"))
            compose.onNodeWithTag(row).performClick()
            compose.onNodeWithTag("private-export-recovery-close-result").performScrollTo().performClick()
            compose.onNodeWithTag("private-export-recovery-confirm").performClick()
            waitTag("private-export-recovery-empty")
            assertTrue(repository.exportRecoveries().isEmpty())
            assertEquals(0, authRequests)
            assertEquals(f.sourceSha, publicSnapshot(f.base, uri)!!.getString("sha256"))
            compose.runOnIdle { showScreen.value = false }
            compose.waitForIdle()
            f.record("PASS", "ui-reviewed-complete-ack", null); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun cancelledReadyExportStaysPendingUntilExplicitRecovery() = runBlocking {
        val f = Fixture(); var passed = false
        try {
            val repository = PrivateAlbumRepository(f.context, f.database)
            val ready = CompletableDeferred<Unit>()
            repository.privateExportCheckpoint = { receipt ->
                if (receipt.phase == "Inserted") f.track(Uri.parse(JSONObject(requireNotNull(receipt.snapshotJson)).getString("uri")))
                if (receipt.phase == "Ready") { ready.complete(Unit); awaitCancellation() }
            }
            val id = seed(f, repository)
            val export = launch { repository.exportToMediaStore(id, f.master) }
            withTimeout(15_000) { ready.await() }
            export.cancelAndJoin()
            assertTrue(export.isCancelled)
            val review = repository.exportRecoveries().single()
            assertEquals(PrivateExportStatus.Ready, review.status)
            val uri = Uri.parse(requireNotNull(review.uri))
            assertEquals("1", publicSnapshot(f.base, uri)!!.getString("is_pending"))
            repository.privateExportCheckpoint = null
            assertEquals(uri, repository.completeExport(review.id, review.proof))
            assertEquals("0", publicSnapshot(f.base, uri)!!.getString("is_pending"))
            assertEquals(f.sourceSha, publicSnapshot(f.base, uri)!!.getString("sha256"))
            assertEquals(f.sourceSha, sha(f.source)); f.record("PASS", "cancel-ready-explicit-recover", null); passed = true
        } finally { f.finish(passed) }
    }

    @Test fun cleanupOwnedExportFixture() {
        val base = ApplicationProvider.getApplicationContext<Context>(); cleanup(base, fixtureId(base))
    }

    private class Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = fixtureId(base)
        val receipt = receiptFile(base, id)
        private val publicUris = linkedSetOf<String>()
        fun track(uri: Uri) {
            check(publicUris.add(uri.toString()))
            val json = readReceipt(receipt).put("publicUris", JSONArray(publicUris.toList()))
            writeReceipt(receipt, json)
        }
        val databaseName = "private-export-$id.db"
        private val alias = indexAlias(databaseName)
        private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val root = rootFile(base, id).also {
            check(!present(it) && !present(receipt) && !present(File(receipt.path + ".bak")) &&
                !present(File(receipt.path + ".new"))) { "Existing fixture must be preserved" }
            check(!store.containsAlias(alias)) { "Existing index alias must be preserved" }
            check(it.mkdir())
        }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdir()) }
            override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { check(isDirectory || mkdir()) }
            override fun getDatabasePath(name: String) = File(
                File(root, "databases").apply { check(isDirectory || mkdir()) }, name)
        }
        private val indexKey = PrivateIndexKey(context, databaseName)
        val master: SecretKey = PrivateAlbumCrypto.generateDataKey() // No global/media Keystore alias.
        val sourceBytes = sourceBytes()
        val source = File(root, "source-$id.png").also { file ->
            check(file.createNewFile())
            FileOutputStream(file).use { it.write(sourceBytes); it.fd.sync() }
        }
        val sourceSha = sha(source)
        val containerDirectory get() = File(context.filesDir, "private-album")
        var database: PrivateAlbumDatabase
        init {
            check(!store.containsAlias(indexKey.alias))
            record("STARTED", "owned-source", null)
            database = PrivateAlbumDatabase.open(context, databaseName)
        }
        fun rowCount(): Int = database.openHelper.writableDatabase.query("SELECT count(*) FROM private_media").use {
            check(it.moveToFirst()); it.getInt(0)
        }
        suspend fun import(repository: PrivateAlbumRepository) = repository.importFromUri(
            Uri.fromFile(source), source.name, "image/png", "image", 1, 1, 0, master)
        fun reopen() { database.close(); database = PrivateAlbumDatabase.open(context, databaseName) }
        fun record(status: String, phase: String, cipherSha: String?) {
            val json = JSONObject().put("fixture", id).put("status", status).put("phase", phase)
                .put("sourceSha256", sourceSha).put("root", root.path).put("database", databaseName)
                .put("indexAlias", indexKey.alias).put("cipherSha256", cipherSha ?: JSONObject.NULL)
                .put("publicUris", JSONArray(publicUris.toList()))
            writeReceipt(receipt, json)
            println("PRIVATE_EXPORT_RECOVERY $json")
        }
        fun finish(passed: Boolean) {
            database.close()
            val json = readReceipt(receipt)
            check(!json.has("files") && !json.has("directories")) { "Never replace a captured inventory" }
            check(sha(source) == sourceSha)
            val publicOutputs = JSONArray()
            for (uri in publicUris) publicOutputs.put(JSONObject().put("uri", uri).put("snapshot", publicSnapshot(base, Uri.parse(uri)) ?: JSONObject.NULL))
            json.put("publicOutputs", publicOutputs)
            val inventory = inventory(root)
            json.put("status", if (passed) "PASS" else "FAIL")
                .put("files", inventory.first).put("directories", inventory.second).put("databaseClosed", true)
            writeReceipt(receipt, json)
            if (passed) cleanup(base, id)
            else println("PRIVATE_EXPORT_RECOVERY_RETAINED fixture=$id root=$root receipt=$receipt")
        }
    }
    companion object {
        private fun fixtureId(base: Context): String {
            check(base.packageName == "com.ugallery.feature.privatealbum.test") { "Unexpected fixture package" }
            val id = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")) {
                "Required -e fixtureUuid UUID"
            }
            require(UUID.fromString(id).toString() == id) { "Canonical fixture UUID required" }
            return id
        }
        private fun rootFile(base: Context, id: String) = File(base.cacheDir.canonicalFile, "private-export-recovery-$id")
        private fun receiptFile(base: Context, id: String) = File(base.filesDir.canonicalFile, "private-export-recovery-$id.json")
        private fun indexAlias(name: String) = "ugallery.privatealbum.index.v1." + digest(name.toByteArray(Charsets.UTF_8))
        private fun sourceBytes() = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", 0)
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun present(file: File): Boolean = try { Os.lstat(file.path); true }
            catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }
        private fun regular(file: File) {
            check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode) && file.canonicalFile == file.absoluteFile)
        }
        private fun writeReceipt(file: File, json: JSONObject) {
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
                if (present(it)) regular(it)
            }
            val atomic = AtomicFile(file)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= 64 * 1024)
            val output = atomic.startWrite()
            try { output.write(bytes); output.fd.sync(); atomic.finishWrite(output) }
            catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes))
            PrivatePortableJournal.syncDirectory(requireNotNull(file.parentFile))
        }
        private fun readReceipt(file: File): JSONObject {
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
                if (present(it)) regular(it)
            }
            return AtomicFile(file).openRead().use {
                val bytes = ByteArray(64 * 1024 + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = it.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    if (count > 0) size += count
                }
                check(size <= 64 * 1024) { "Unexpected fixture receipt size" }
                JSONObject(bytes.copyOf(size).toString(Charsets.UTF_8))
            }
        }
        private fun inventory(root: File): Pair<JSONArray, JSONArray> {
            val files = JSONArray()
            val directories = JSONArray()
            fun visit(file: File) {
                check(file.canonicalFile == file.absoluteFile)
                check(file == root || file.path.startsWith(root.path + File.separator))
                val relative = if (file == root) "" else file.relativeTo(root).invariantSeparatorsPath
                val stat = Os.lstat(file.path)
                if (OsConstants.S_ISDIR(stat.st_mode)) {
                    directories.put(relative)
                    requireNotNull(file.listFiles()).sortedBy { it.name }.forEach(::visit)
                } else {
                    check(OsConstants.S_ISREG(stat.st_mode)) { "Unexpected nonregular fixture file" }
                    files.put(JSONObject().put("path", relative).put("size", file.length()).put("sha256", sha(file)))
                }
            }
            if (present(root)) visit(root)
            return files to directories
        }
        private fun cleanup(base: Context, id: String) {
            val root = rootFile(base, id)
            val receipt = receiptFile(base, id)
            val json = readReceipt(receipt)
            check(!json.optBoolean("cleanupComplete")) { "Cleanup already confirmed; never adopt new files" }
            val name = "private-export-$id.db"
            check(json.getString("fixture") == id && json.getString("root") == root.path &&
                json.getString("database") == name && json.getString("indexAlias") == indexAlias(name))
            check(json.getBoolean("databaseClosed")) { "A closed database inventory is required" }
            check(json.getString("sourceSha256") == digest(sourceBytes()))
            val expectedFiles = json.getJSONArray("files")
            val expectedDirs = json.getJSONArray("directories")
            val expected = (0 until expectedFiles.length()).associate { i ->
                val entry = expectedFiles.getJSONObject(i)
                val path = entry.getString("path")
                require(path.isNotBlank() && !File(path).isAbsolute && path.split('/').none { it.isEmpty() || it == ".." || it == "." })
                path to entry
            }
            check(expected.size == expectedFiles.length())
            val sourceEntry = requireNotNull(expected["source-$id.png"])
            check(sourceEntry.getString("sha256") == json.getString("sourceSha256") &&
                sourceEntry.getLong("size") == sourceBytes().size.toLong())
            val (currentFiles, currentDirs) = inventory(root)
            val retry = json.optBoolean("cleanupStarted")
            if (!retry) {
                check(currentFiles.toString() == expectedFiles.toString()) { "Fixture bytes changed; retain all files" }
                check(currentDirs.toString() == expectedDirs.toString()) { "Fixture directories changed" }
            }
            val directories = (0 until expectedDirs.length()).map { expectedDirs.getString(it) }
            check(directories.distinct().size == directories.size && "" in directories)
            directories.forEach { path ->
                require(path.isEmpty() || (!File(path).isAbsolute && path.split('/').none { it.isEmpty() || it == ".." || it == "." }))
            }
            for (i in 0 until currentDirs.length()) check(currentDirs.getString(i) in directories)
            for (i in 0 until currentFiles.length()) {
                val current = currentFiles.getJSONObject(i)
                val entry = requireNotNull(expected[current.getString("path")]) { "Unexpected fixture file; retain" }
                check(current.getLong("size") == entry.getLong("size") && current.getString("sha256") == entry.getString("sha256"))
            }
            val outputs = json.getJSONArray("publicOutputs")
            for (i in 0 until outputs.length()) {
                val entry = outputs.getJSONObject(i)
                val current = publicSnapshot(base, Uri.parse(entry.getString("uri")))
                if (current == null && retry) continue
                check((current?.toString() ?: "null") == entry.get("snapshot").toString()) { "Public output changed; retain" }
            }
            // Durable start permits retry of exactly the recorded inventory, never a new snapshot.
            if (!retry) writeReceipt(receipt, json.put("cleanupStarted", true))
            for (i in 0 until outputs.length()) {
                val entry = outputs.getJSONObject(i)
                val uri = Uri.parse(entry.getString("uri"))
                val current = publicSnapshot(base, uri) ?: continue
                check(current.toString() == entry.getJSONObject("snapshot").toString())
                deletePublicExact(base, uri, current)
            }
            for ((path, entry) in expected) {
                val file = File(root, path)
                if (!present(file)) continue
                regular(file)
                check(file.length() == entry.getLong("size") && sha(file) == entry.getString("sha256"))
                check(file.delete() && !present(file))
                PrivatePortableJournal.syncDirectory(requireNotNull(file.parentFile))
            }
            for (path in directories.sortedByDescending { it.length }) {
                require(path.isEmpty() || (!File(path).isAbsolute && path.split('/').none { it.isEmpty() || it == ".." || it == "." }))
                val directory = if (path.isEmpty()) root else File(root, path)
                if (!present(directory)) continue
                check(directory.canonicalFile == directory.absoluteFile && OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode))
                check(requireNotNull(directory.listFiles()).isEmpty() && directory.delete())
                PrivatePortableJournal.syncDirectory(requireNotNull(directory.parentFile))
            }
            check(!present(root))
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(indexAlias(name))
            check(!store.containsAlias(indexAlias(name)))
            writeReceipt(receipt, json.put("cleanupComplete", true))
            println("PRIVATE_EXPORT_RECOVERY_CLEANUP $json")
        }
        private val publicColumns = arrayOf("_id", "owner_package_name", "generation_added", "generation_modified", "is_pending", "is_trashed", "_display_name", "relative_path", "mime_type", "_size")
        private fun publicSnapshot(base: Context, uri: Uri): JSONObject? {
            require(uri.toString().matches(Regex("content://media/external_primary/images/media/[1-9][0-9]*")))
            val json = base.contentResolver.query(uri, publicColumns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else JSONObject().apply {
                    publicColumns.forEachIndexed { index, name -> put(name, if (cursor.isNull(index)) JSONObject.NULL else cursor.getString(index)) }
                }
            } ?: return null
            check(json.getString("owner_package_name") == base.packageName)
            val hash = try { base.contentResolver.openInputStream(uri)?.use { digest(it.readBytes()) } }
                catch (missing: java.io.FileNotFoundException) { if (json.isNull("_size") || json.getString("_size") == "0") null else throw missing }
            return json.put("sha256", hash ?: JSONObject.NULL)
        }
        private fun deletePublicExact(base: Context, uri: Uri, expected: JSONObject) {
            check(publicSnapshot(base, uri).toString() == expected.toString())
            val args = mutableListOf<String>()
            val where = publicColumns.joinToString(" AND ") { name ->
                if (expected.isNull(name)) "$name IS NULL" else { args += expected.getString(name); "$name = ?" }
            }
            check(base.contentResolver.delete(uri, where, args.toTypedArray()) == 1)
            check(publicSnapshot(base, uri) == null)
        }
        private fun sha(file: File): String = FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; if (count > 0) digest.update(bytes, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
