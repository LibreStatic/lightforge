package com.librestatic.lightforge.feature.privatealbum

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto

/** Small real provider/SQLCipher/Keystore/v3 reader/export fixture; no credentials or normal vault. */
@RunWith(AndroidJUnit4::class)
class PrivateSizedContainerDeviceTest {
    @Test fun publicOwnedPngImportsV3ReadsAndExportsWithoutChangingCiphertext() = runBlocking(Dispatchers.IO) {
        val f = Fixture(); var passed = false
        try {
            val result = f.importSource()
            assertTrue(result.error, result.success)
            val mediaId = requireNotNull(result.mediaId)
            val entity = requireNotNull(f.db.privateMediaDao().getById(mediaId))
            val container = File(entity.containerPath)
            f.anchorContainer(container)
            val cipherHash = sha(container)
            val header = container.inputStream().use { input ->
                val prefix = ByteArray(23); java.io.DataInputStream(input).readFully(prefix)
                assertEquals(3, prefix[4].toInt())
                val mimeSize = ByteBuffer.wrap(prefix, 21, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
                assertEquals("image/png".toByteArray().size, mimeSize)
                val tail = ByteArray(mimeSize + 8); java.io.DataInputStream(input).readFully(tail)
                assertEquals("image/png", tail.copyOf(mimeSize).toString(Charsets.UTF_8))
                val length = ByteBuffer.wrap(tail, mimeSize, 8).order(ByteOrder.BIG_ENDIAN).long
                assertEquals(sourceBytes().size.toLong(), length)
                JSONObject().put("version", 3).put("lengthOffset", 23 + mimeSize).put("plaintextBytes", length)
            }
            val reader = f.repo.openViewerSource(mediaId)
            try {
                assertEquals(sourceBytes().size.toLong(), reader.metadata.plaintextBytes)
                val decoded = ByteArray(sourceBytes().size); var count = 0
                while (count < decoded.size) {
                    val n = reader.readAt(count.toLong(), decoded, count, decoded.size - count)
                    check(n > 0); count += n
                }
                assertArrayEquals(sourceBytes(), decoded)
                assertEquals(-1, reader.readAt(decoded.size.toLong(), ByteArray(1), 0, 1))
                val tiles = PrivateImageTileSource(f.context, reader)
                try {
                    assertEquals(1, tiles.width); assertEquals(1, tiles.height)
                    val bitmap = tiles.decodeRegion(Rect(0, 0, 1, 1), 1)
                    try { assertEquals(1, bitmap.width); assertEquals(1, bitmap.height) } finally { bitmap.recycle() }
                } finally { tiles.close() }
            } finally { reader.close() }
            f.repo.privateExportCheckpoint = { entry ->
                val raw = JSONObject(requireNotNull(entry.snapshotJson)); val uri = Uri.parse(raw.getString("uri"))
                // The Inserted callback precedes backing-file creation. Persist identity, not imaginary bytes.
                f.update { it.put("exportId", entry.id).put("outputUri", uri.toString()).put("outputIdentity", raw) }
                if (entry.phase == "Ready" || entry.phase == "Published") {
                    val snapshot = requireNotNull(publicSnapshot(f.base, uri))
                    check(snapshot.getString("sha256") == digest(sourceBytes()))
                    f.update { it.put("outputSnapshot", snapshot) }
                }
            }
            val binding = f.repo.requireMasterKeyBinding()
            val output = requireNotNull(f.repo.exportToMediaStore(mediaId, binding))
            val review = f.repo.exportRecoveries().single()
            assertEquals(PrivateExportStatus.Published, review.status)
            assertEquals(output.toString(), review.uri)
            val snapshot = requireNotNull(publicSnapshot(f.base, output))
            assertEquals("0", snapshot.getString("pending")); assertEquals(digest(sourceBytes()), snapshot.getString("sha256"))
            assertEquals(cipherHash, sha(container)); f.assertSource()
            val transfer = PrivatePortableTransfer(f.context, f.db)
            val archive = transfer.prepareExport(binding, "Sized fixture passphrase 2026!".toCharArray())
            assertEquals(1, archive.count)
            val retainedArchive = File(f.root, "portable-${f.id}.ugpb")
            check(retainedArchive.createNewFile())
            java.io.FileOutputStream(retainedArchive).use { out -> archive.file.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            PrivatePortableJournal.syncDirectory(f.root)
            assertEquals(archive.sha256, sha(retainedArchive))
            f.update { it.put("portableArchive", retainedArchive.name).put("portableArchiveSha256", archive.sha256) }
            val prepared = transfer.prepareRestore(Uri.fromFile(retainedArchive), "Sized fixture passphrase 2026!".toCharArray())
            assertEquals(1, prepared.items.size)
            val restored = transfer.commit(prepared, binding)
            assertEquals(1, restored.count); assertFalse(restored.alreadyCommitted)
            assertEquals(2, f.db.privateMediaDao().count())
            val restoredRow = f.db.privateMediaDao().getPage(3, 0).single { it.id != mediaId }
            val restoredContainer = File(restoredRow.containerPath)
            f.update { it.put("restoredContainer", restoredContainer.relativeTo(f.root).invariantSeparatorsPath)
                .put("restoredCipherSha256", sha(restoredContainer)) }
            assertEquals(3, restoredContainer.inputStream().use { input -> val prefix = ByteArray(5); java.io.DataInputStream(input).readFully(prefix); prefix[4].toInt() })
            val oldKey = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(entity.encryptedDataKey, entity.dataKeyIv), binding.secretKey).encoded
            val restoredKey = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(restoredRow.encryptedDataKey, restoredRow.dataKeyIv), binding.secretKey).encoded
            try { assertFalse("Restore must rotate actual data key, not only wrapper nonce", oldKey.contentEquals(restoredKey)) }
            finally { oldKey.fill(0); restoredKey.fill(0) }
            val restoredReader = f.repo.openViewerSource(restoredRow.id)
            try {
                assertEquals(sourceBytes().size.toLong(), restoredReader.metadata.plaintextBytes)
                val bytes = ByteArray(sourceBytes().size); var offset = 0
                while (offset < bytes.size) { val n = restoredReader.readAt(offset.toLong(), bytes, offset, bytes.size - offset); check(n > 0); offset += n }
                assertArrayEquals(sourceBytes(), bytes)
            } finally { restoredReader.close() }
            assertEquals(cipherHash, sha(container)); f.assertSource()
            assertNotNull(f.db.portableRestoreDao().getReceiptByArchiveSha(archive.sha256))
            transfer.discard(archive)
            transfer.clearOwnedStaging() // Only this UUID context/transfer; retained encrypted archive stays in fixture root.
            f.update { it.put("portableRestoredV3", true).put("restoredMediaId", restoredRow.id).put("dataKeyRotated", true) }
            f.assertNoStaging()
            f.update { it.put("header", header).put("readerExact", true).put("tileDecoded", true)
                .put("outputSnapshot", snapshot).put("cipherUnchanged", true) }
            passed = true
        } finally { f.finish(passed) }
    }

    @Test fun injectedDirectorySyncFailureLeavesNoRowOrStagingAndPreservesPublicSource() = runBlocking(Dispatchers.IO) {
        val f = Fixture(); var passed = false
        try {
            var syncCalls = 0
            f.repo.importDirectorySync = { directory ->
                syncCalls++
                assertEquals(File(f.context.filesDir, "private-album").canonicalFile, directory.canonicalFile)
                f.assertNoStaging()
                throw IOException("OWNED_V3_IMPORT_DIRECTORY_SYNC_FAILURE")
            }
            val result = f.importSource()
            assertFalse(result.success)
            assertEquals("OWNED_V3_IMPORT_DIRECTORY_SYNC_FAILURE", result.error)
            assertEquals(1, syncCalls)
            assertEquals(0, f.db.privateMediaDao().count())
            assertTrue(f.db.privateExportDao().list().isEmpty())
            val directory = File(f.context.filesDir, "private-album")
            assertTrue(requireNotNull(directory.listFiles()).isEmpty())
            f.assertNoStaging(); f.assertSource()
            f.update { it.put("syncFailure", true).put("syncCalls", syncCalls).put("rowCount", 0)
                .put("stagingAbsent", true).put("sourceIntact", true) }
            passed = true
        } finally { f.finish(passed) }
    }

    @Test fun cleanupOwnedSizedFixture() { cleanup(ApplicationProvider.getApplicationContext(), fixtureId()) }

    private class Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = fixtureId()
        val root = File(base.cacheDir.canonicalFile, "private-sized-$id")
        val receipt = File(base.filesDir.canonicalFile, "private-sized-$id.json")
        val name = "private-sized-$id.db"
        val masterAlias = "lightforge.privatealbum.fixture.sized.$id"
        val indexAlias = "lightforge.privatealbum.index.v1." + digest(name.toByteArray())
        val context: Context
        val db: PrivateAlbumDatabase
        val repo: PrivateAlbumRepository
        val sourceUri: Uri
        init {
            check(base.packageName == "com.librestatic.lightforge.feature.privatealbum.test")
            check(!present(root) && listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).none(::present))
            check(!keys().containsAlias(masterAlias) && !keys().containsAlias(indexAlias))
            check(root.mkdir())
            write(receipt, JSONObject().put("fixture", id).put("root", root.path).put("database", name)
                .put("masterAlias", masterAlias).put("indexAlias", indexAlias).put("sourceSha256", digest(sourceBytes())).put("status", "STARTED"))
            context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdir()) }
                override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
                override fun getNoBackupFilesDir() = File(root, "no-backup").apply { check(isDirectory || mkdir()) }
                override fun getDatabasePath(name: String) = File(File(root, "databases").apply { check(isDirectory || mkdir()) }, name)
            }
            db = PrivateAlbumDatabase.open(context, name); repo = PrivateAlbumRepository(context, db)
            runBlocking { repo.setup(PrivateAlbumCrypto.getOrCreateMasterKey(masterAlias), masterAlias) }
            val displayName = "private-sized-$id.png"
            update { it.put("sourceDisplayName", displayName) }
            sourceUri = requireNotNull(base.contentResolver.insert(MediaStore.Images.Media.getContentUri("external_primary"), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgeTests/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            update { it.put("sourceUri", sourceUri.toString()) }
            update { it.put("sourceInserted", requireNotNull(metadata(base, sourceUri))) }
            val fd = requireNotNull(base.contentResolver.openFileDescriptor(sourceUri, "w"))
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(sourceBytes()); it.flush(); it.fd.sync() }
            val pending = requireNotNull(publicSnapshot(base, sourceUri))
            check(pending.getString("sha256") == digest(sourceBytes()) && pending.getString("pending") == "1")
            update { it.put("sourceSnapshot", pending) }
            check(base.contentResolver.update(sourceUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, selection(pending), arguments(pending)) == 1)
            val published = requireNotNull(publicSnapshot(base, sourceUri)); check(published.getString("pending") == "0")
            update { it.put("sourceSnapshot", published) }
        }
        suspend fun importSource(): ImportResult = repo.importFromUri(sourceUri, "private-sized-$id.png", "image/png", "image", 1, 1, 0, repo.requireMasterKeyBinding())
        fun update(change: (JSONObject) -> Unit) { val json = read(receipt); change(json); write(receipt, json) }
        fun anchorContainer(file: File) { update { it.put("container", file.relativeTo(root).invariantSeparatorsPath).put("cipherSha256", sha(file)) } }
        fun assertSource() { check(publicSnapshot(base, sourceUri).toString() == read(receipt).getJSONObject("sourceSnapshot").toString()) }
        fun assertNoStaging() {
            check(root.walkTopDown().none { it.isFile && it.relativeTo(root).path.contains(".import-staging") })
            check(context.cacheDir.walkTopDown().none { it.isFile })
        }
        fun finish(passed: Boolean) {
            repo.disposeSession(); db.close()
            var verified = passed
            try {
                assertSource(); assertNoStaging()
                val json = read(receipt)
                if (json.has("container")) check(sha(File(root, json.getString("container"))) == json.getString("cipherSha256"))
                if (json.has("restoredContainer")) check(sha(File(root, json.getString("restoredContainer"))) == json.getString("restoredCipherSha256"))
                if (json.has("portableArchive")) check(sha(File(root, json.getString("portableArchive"))) == json.getString("portableArchiveSha256"))
                if (json.has("outputSnapshot")) check(publicSnapshot(base, Uri.parse(json.getString("outputUri"))).toString() == json.getJSONObject("outputSnapshot").toString())
            } catch (failure: Throwable) { verified = false; update { it.put("failure", failure.stackTraceToString().take(8192)) }; throw failure }
            finally {
                update { check(!it.has("inventory")); it.put("inventory", inventory(root)).put("databaseClosed", true).put("status", if (verified) "PASS" else "FAIL") }
                if (verified) cleanup(base, id) else println("PRIVATE_SIZED_RETAINED fixture=$id receipt=$receipt")
            }
        }
    }

    companion object {
        private fun fixtureId(): String = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also { require(UUID.fromString(it).toString() == it) }
        private fun sourceBytes() = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", 0)
        private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha(file: File): String { regular(file); return file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; if (n > 0) md.update(buf, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        } }
        private fun present(file: File): Boolean = try { Os.lstat(file.path); true } catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) false else throw e }
        private fun regular(file: File) { check(file.canonicalFile == file.absoluteFile && OsConstants.S_ISREG(Os.lstat(file.path).st_mode)) }
        private fun write(file: File, json: JSONObject) {
            val bytes = json.toString().toByteArray(); check(bytes.size <= 64 * 1024)
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).filter(::present).forEach(::regular)
            val atomic = AtomicFile(file); val out = atomic.startWrite()
            try { out.write(bytes); out.fd.sync(); atomic.finishWrite(out); check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes)); PrivatePortableJournal.syncDirectory(requireNotNull(file.parentFile)) }
            catch (e: Throwable) { atomic.failWrite(out); throw e }
        }
        private fun read(file: File): JSONObject { regular(file); return JSONObject(AtomicFile(file).openRead().use { input ->
            val bytes = ByteArray(65537); var n = 0
            while (n < bytes.size) { val count = input.read(bytes, n, bytes.size - n); if (count < 0) break; n += count }
            check(n < bytes.size); bytes.copyOf(n).toString(Charsets.UTF_8)
        }) }
        private val fields = linkedMapOf("owner" to MediaStore.MediaColumns.OWNER_PACKAGE_NAME, "name" to MediaStore.MediaColumns.DISPLAY_NAME,
            "path" to MediaStore.MediaColumns.RELATIVE_PATH, "mime" to MediaStore.MediaColumns.MIME_TYPE, "added" to MediaStore.MediaColumns.GENERATION_ADDED,
            "modified" to MediaStore.MediaColumns.GENERATION_MODIFIED, "pending" to MediaStore.MediaColumns.IS_PENDING,
            "trashed" to MediaStore.MediaColumns.IS_TRASHED, "size" to MediaStore.MediaColumns.SIZE)
        private fun metadata(base: Context, uri: Uri): JSONObject? {
            check(Regex("content://media/external_primary/images/media/[1-9][0-9]*").matches(uri.toString()))
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE); putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            return requireNotNull(base.contentResolver.query(uri, fields.values.toTypedArray(), args, null)).use { c ->
                if (!c.moveToFirst()) return null
                JSONObject().put("uri", uri.toString()).also { j -> fields.keys.forEachIndexed { i, key -> j.put(key, if (c.isNull(i)) JSONObject.NULL else c.getString(i)) }; check(!c.moveToNext()) }
            }
        }
        private fun publicSnapshot(base: Context, uri: Uri): JSONObject? {
            val meta = metadata(base, uri) ?: return null
            val bytes = requireNotNull(base.contentResolver.openInputStream(uri)).use { input ->
                val buffer = ByteArray(sourceBytes().size + 1); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                buffer.copyOf(count)
            }
            check(bytes.contentEquals(sourceBytes())) { "Public fixture bytes changed" }
            check(metadata(base, uri).toString() == meta.toString())
            return meta.put("sha256", digest(bytes)).put("bytes", bytes.size)
        }
        private fun selection(snapshot: JSONObject) = fields.entries.joinToString(" AND ") { (k, v) -> if (snapshot.isNull(k)) "$v IS NULL" else "$v=?" }
        private fun arguments(snapshot: JSONObject) = fields.keys.filterNot(snapshot::isNull).map(snapshot::getString).toTypedArray()
        private fun inventory(root: File): JSONArray = JSONArray().also { rows ->
            if (present(root)) root.walkTopDown().sortedBy { it.relativeTo(root).path }.forEach { file ->
                check(file.canonicalFile == file.absoluteFile)
                val dir = OsConstants.S_ISDIR(Os.lstat(file.path).st_mode); check(dir || OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
                rows.put(JSONObject().put("path", file.relativeTo(root).invariantSeparatorsPath).put("directory", dir)
                    .put("size", if (dir) 0 else file.length()).put("sha256", if (dir) JSONObject.NULL else sha(file)))
            }
        }
        private fun cleanup(base: Context, id: String) {
            check(base.packageName == "com.librestatic.lightforge.feature.privatealbum.test")
            val root = File(base.cacheDir.canonicalFile, "private-sized-$id"); val file = File(base.filesDir.canonicalFile, "private-sized-$id.json")
            val json = read(file); val name = "private-sized-$id.db"
            check(json.getString("fixture") == id && json.getString("root") == root.path && json.getString("database") == name && json.getBoolean("databaseClosed") && !json.optBoolean("cleanupComplete"))
            val master = "lightforge.privatealbum.fixture.sized.$id"; val index = "lightforge.privatealbum.index.v1." + digest(name.toByteArray())
            check(json.getString("masterAlias") == master && json.getString("indexAlias") == index && json.getString("sourceSha256") == digest(sourceBytes()))
            val recorded = json.getJSONArray("inventory"); check(recorded.toString() == inventory(root).toString())
            if (json.has("container")) {
                val relative = json.getString("container"); check(!File(relative).isAbsolute && relative.startsWith("files/private-album/") && relative.split('/').none { it == ".." || it == "." })
                check(sha(File(root, relative)) == json.getString("cipherSha256"))
            }
            if (json.has("restoredContainer")) {
                val relative = json.getString("restoredContainer"); check(!File(relative).isAbsolute && relative.startsWith("files/private-album/") && relative.split('/').none { it == ".." || it == "." })
                check(sha(File(root, relative)) == json.getString("restoredCipherSha256"))
            }
            if (json.has("portableArchive")) {
                check(json.getString("portableArchive") == "portable-$id.ugpb")
                check(sha(File(root, json.getString("portableArchive"))) == json.getString("portableArchiveSha256"))
            }
            val outputs = mutableListOf<Pair<Uri, JSONObject>>()
            for (prefix in listOf("source", "output")) if (json.has(prefix + "Uri")) {
                val uri = Uri.parse(json.getString(prefix + "Uri")); val snapshot = json.getJSONObject(prefix + "Snapshot")
                check(snapshot.getString("owner") == base.packageName && snapshot.getString("sha256") == digest(sourceBytes()))
                check(publicSnapshot(base, uri).toString() == snapshot.toString())
                outputs.add(uri to snapshot)
            }
            // Validate all exact objects before deleting either; no output is adopted at cleanup.
            for ((uri, snapshot) in outputs) {
                check(publicSnapshot(base, uri).toString() == snapshot.toString())
                check(base.contentResolver.delete(uri, selection(snapshot), arguments(snapshot)) == 1)
                check(metadata(base, uri) == null)
            }
            val entries = (0 until recorded.length()).map { recorded.getJSONObject(it) }
            entries.sortedByDescending { it.getString("path").length }.forEach { entry ->
                val relative = entry.getString("path"); check(!File(relative).isAbsolute && relative.split('/').none { it == ".." || it == "." })
                val target = if (relative.isEmpty()) root else File(root, relative); check(target.canonicalFile == target.absoluteFile)
                if (!entry.getBoolean("directory")) check(target.length() == entry.getLong("size") && sha(target) == entry.getString("sha256"))
                else check(requireNotNull(target.listFiles()).isEmpty())
                check(target.delete()); PrivatePortableJournal.syncDirectory(requireNotNull(target.parentFile))
            }
            check(!present(root)); keys().deleteEntry(master); keys().deleteEntry(index); check(!keys().containsAlias(master) && !keys().containsAlias(index))
            json.put("cleanupComplete", true).put("publicObjectsAbsent", true); write(file, json)
            println("PRIVATE_SIZED_CLEANUP $json")
        }
    }
}
