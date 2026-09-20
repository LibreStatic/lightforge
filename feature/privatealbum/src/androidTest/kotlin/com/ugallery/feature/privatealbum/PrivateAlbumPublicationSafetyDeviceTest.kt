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
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** UUID-only, real encrypted Room and source IO; no device credentials or public media grants. */
@RunWith(AndroidJUnit4::class)
class PrivateAlbumPublicationSafetyDeviceTest {
    @Test fun directorySyncFailureNeverCommitsAndPreservesOriginal() = runBlocking {
        val fixture = Fixture()
        var passed = false
        var reached = false
        try {
            val repository = PrivateAlbumRepository(fixture.context, fixture.database)
            installBoundary(repository) { directory ->
                reached = true
                assertEquals(fixture.containerDirectory, directory)
                assertEquals(0, fixture.rowCount())
                assertEquals(fixture.sourceSha, sha(fixture.source))
                assertEquals(1, requireNotNull(directory.listFiles()).size)
                throw IOException("OWNED_DIRECTORY_SYNC_FAILURE")
            }
            val result = fixture.import(repository)
            assertFalse("Import must reject a failed pre-commit directory sync", result.success)
            assertTrue("The production durability boundary must execute", reached)
            assertEquals("OWNED_DIRECTORY_SYNC_FAILURE", result.error)
            assertEquals(0, fixture.rowCount())
            assertTrue(requireNotNull(fixture.containerDirectory.listFiles()).isEmpty())
            assertEquals(fixture.sourceSha, sha(fixture.source))
            fixture.reopen()
            assertEquals(0, fixture.rowCount())
            fixture.record("PASS", "directory-sync-failure", null)
            passed = true
        } finally { fixture.finish(passed) }
    }

    @Test fun durableContainerAndEncryptedIndexReopenWithIdenticalPlaintext() = runBlocking {
        val fixture = Fixture()
        var passed = false
        val synced = mutableListOf<File>()
        try {
            val repository = PrivateAlbumRepository(fixture.context, fixture.database)
            installBoundary(repository) { directory ->
                synced += directory
                assertEquals(0, fixture.rowCount())
                assertEquals(fixture.sourceSha, sha(fixture.source))
                PrivatePortableJournal.syncDirectory(directory)
            }
            val result = fixture.import(repository)
            assertTrue(result.error, result.success)
            assertEquals(listOf(fixture.containerDirectory, fixture.context.filesDir), synced)
            val id = requireNotNull(result.mediaId)
            val before = requireNotNull(fixture.database.privateMediaDao().getById(id))
            val container = File(before.containerPath)
            assertEquals(fixture.containerDirectory, container.parentFile)
            val cipherSha = sha(container)
            fixture.reopen()
            assertEquals(1, fixture.rowCount())
            val after = requireNotNull(fixture.database.privateMediaDao().getById(id))
            assertEquals(before.containerPath, after.containerPath)
            assertArrayEquals(before.encryptedDataKey, after.encryptedDataKey)
            assertArrayEquals(before.dataKeyIv, after.dataKeyIv)
            assertArrayEquals(before.sha256, after.sha256)
            assertEquals(cipherSha, sha(container))
            val key = PrivateAlbumCrypto.decryptDataKey(
                PrivateAlbumCrypto.EncryptedDataKey(after.encryptedDataKey, after.dataKeyIv), fixture.master)
            val plaintext = java.io.ByteArrayOutputStream()
            FileInputStream(container).use { PrivateAlbumCrypto.decryptStream(it, plaintext, key, after.sha256) }
            assertArrayEquals(fixture.sourceBytes, plaintext.toByteArray())
            assertEquals(fixture.sourceSha, sha(fixture.source))
            fixture.record("PASS", "durable-reopen", cipherSha)
            passed = true
        } finally { fixture.finish(passed) }
    }

    @Test fun cleanupOwnedImportFixture() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        cleanup(base, fixtureId(base))
    }

    /** Optional lookup keeps this same test compilable against the pristine baseline.
     * Baseline still executes real import and fails the assertions, never skips or mocks IO. */
    private fun installBoundary(repository: PrivateAlbumRepository, callback: (File) -> Unit) {
        val field = PrivateAlbumRepository::class.java.declaredFields.singleOrNull {
            it.name == "importDirectorySync"
        } ?: return
        field.isAccessible = true
        field.set(repository, callback)
    }

    private class Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = fixtureId(base)
        val receipt = receiptFile(base, id)
        val databaseName = "private-import-$id.db"
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
            writeReceipt(receipt, json)
            println("PRIVATE_IMPORT_DURABILITY $json")
        }
        fun finish(passed: Boolean) {
            database.close()
            val json = readReceipt(receipt)
            check(!json.has("files") && !json.has("directories")) { "Never replace a captured inventory" }
            check(sha(source) == sourceSha)
            val inventory = inventory(root)
            json.put("status", if (passed) "PASS" else "FAIL")
                .put("files", inventory.first).put("directories", inventory.second).put("databaseClosed", true)
            writeReceipt(receipt, json)
            if (passed) cleanup(base, id)
            else println("PRIVATE_IMPORT_DURABILITY_RETAINED fixture=$id root=$root receipt=$receipt")
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
        private fun rootFile(base: Context, id: String) = File(base.cacheDir.canonicalFile, "private-import-durability-$id")
        private fun receiptFile(base: Context, id: String) = File(base.filesDir.canonicalFile, "private-import-durability-$id.json")
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
            val name = "private-import-$id.db"
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
            // Durable start permits retry of exactly the recorded inventory, never a new snapshot.
            if (!retry) writeReceipt(receipt, json.put("cleanupStarted", true))
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
            println("PRIVATE_IMPORT_DURABILITY_CLEANUP $json")
        }
        private fun sha(file: File): String = FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; if (count > 0) digest.update(bytes, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
