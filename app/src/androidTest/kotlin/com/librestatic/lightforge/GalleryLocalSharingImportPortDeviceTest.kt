package com.librestatic.lightforge

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.feature.localsharing.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses only an isolated Room DB and MediaStore rows created by each random fixture operation. */
class GalleryLocalSharingImportPortDeviceTest {
    @Test
    fun repeatedRevisionSkipsCopiesAndDeletedRevisionRemainsHistorical() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val entry = f.entry("source-a", "revision-1", 100)
                val first = f.input(listOf(entry))
                val receipt =
                    f.port.enqueueOnce(f.peer, first.id, first.manifest, first.files, emptyMap())
                assertEquals(receipt, f.port.existing(first.id))
                assertEquals(
                    receipt,
                    f.port.enqueueOnce(f.peer, first.id, first.manifest, first.files, emptyMap()),
                )
                val version = f.db.peerImportDao().version(f.peer, entry.sourceId, entry.revision)!!
                f.owned.add(Uri.parse(version.uri))
                val second = f.input(listOf(entry))
                f.port.enqueueOnce(f.peer, second.id, second.manifest, second.files, emptyMap())
                assertEquals(
                    0,
                    f.db.galleryRestoreReceiptDao().get(f.port.existing(second.id)!!)!!.files,
                )
                assertEquals(
                    1,
                    f.db.openHelper.readableDatabase
                        .query("SELECT COUNT(*) FROM peer_import_versions")
                        .use {
                            it.moveToFirst()
                            it.getInt(0)
                        },
                )
                assertEquals(
                    1,
                    f.context.contentResolver.delete(Uri.parse(version.uri), null, null),
                )
                f.deleted.add(Uri.parse(version.uri))
                assertEquals(
                    LocalSharingImportDisposition.AlreadyReceived,
                    f.port
                        .review(f.peer, UUID.randomUUID().toString(), first.manifest)
                        .single()
                        .disposition,
                )
            }
        }

    @Test
    fun equalBytesKeepDistinctSourceInstances() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val a = f.entry("source-a", "same-revision", 100)
                val b = a.copy(sourceId = "source-b", name = "other.png")
                val input = f.input(listOf(a, b))
                val operation =
                    f.port.enqueueOnce(f.peer, input.id, input.manifest, input.files, emptyMap())
                val versions = f.db.peerImportDao().operationVersions(operation)
                f.owned.addAll(versions.map { Uri.parse(it.uri) })
                assertEquals(2, versions.size)
                assertEquals(2, versions.map { it.uri }.distinct().size)
                assertEquals(1, versions.map { it.sha256 }.distinct().size)
                assertEquals(2, f.db.galleryRestoreReceiptDao().get(operation)!!.files)
            }
        }

    @Test
    fun keepBothAndExplicitNewestNeverDeletePreviousVersions() =
        runBlocking<Unit> {
            Fixture().use { f ->
                suspend fun add(revision: String, time: Long, choice: LocalSharingConflictChoice?) {
                    val input = f.input(listOf(f.entry("source", revision, time)))
                    val op =
                        f.port.enqueueOnce(
                            f.peer,
                            input.id,
                            input.manifest,
                            input.files,
                            if (choice == null) emptyMap() else mapOf("source" to choice),
                        )
                    f.owned.addAll(
                        f.db.peerImportDao().operationVersions(op).map { Uri.parse(it.uri) }
                    )
                }
                add("first", 100, null)
                add("keep-both-newer", 200, LocalSharingConflictChoice.KeepBoth)
                assertEquals(
                    "first",
                    f.db.peerImportDao().source(f.peer, "source")!!.activeRevision,
                )
                add("prefer-older", 90, LocalSharingConflictChoice.UseNewest)
                assertEquals(
                    "first",
                    f.db.peerImportDao().source(f.peer, "source")!!.activeRevision,
                )
                add("prefer-newer", 300, LocalSharingConflictChoice.UseNewest)
                assertEquals(
                    "prefer-newer",
                    f.db.peerImportDao().source(f.peer, "source")!!.activeRevision,
                )
                assertEquals(4, f.owned.size)
                f.owned.forEach { uri ->
                    assertArrayEquals(
                        f.bytes,
                        f.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
                    )
                }
            }
        }

    @Test
    fun transferIdentityCannotBeReboundToAnotherPeerOrManifest() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val input = f.input(listOf(f.entry("source", "revision", 100)))
                val op =
                    f.port.enqueueOnce(f.peer, input.id, input.manifest, input.files, emptyMap())
                f.owned.addAll(f.db.peerImportDao().operationVersions(op).map { Uri.parse(it.uri) })
                assertTrue(
                    runCatching {
                            f.port.enqueueOnce(
                                "b".repeat(64),
                                input.id,
                                input.manifest,
                                input.files,
                                emptyMap(),
                            )
                        }
                        .isFailure
                )
                val changed =
                    LocalSharingManifest(
                        input.manifest.entries.map { it.copy(name = "different.png") },
                        true,
                    )
                assertTrue(
                    runCatching {
                            f.port.enqueueOnce(f.peer, input.id, changed, input.files, emptyMap())
                        }
                        .isFailure
                )
                assertEquals(op, f.port.existing(input.id))
            }
        }

    @Test
    fun reopeningAfterPublicationBeforeRoomReceiptResumesExactlyOnce() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val input = f.input(listOf(f.entry("source", "revision", 100)))
                f.failReceipt()
                assertTrue(
                    runCatching {
                            f.port.enqueueOnce(
                                f.peer,
                                input.id,
                                input.manifest,
                                input.files,
                                emptyMap(),
                            )
                        }
                        .isFailure
                )
                assertNull(f.port.existing(input.id))
                val operation = f.operation(input.id)
                val staged = f.journalUris(operation)
                f.owned.addAll(staged)
                assertEquals(1, staged.size)
                assertTrue(operation in GalleryLocalSharingImportPort.retainedOperations(f.context))
                f.reopen()
                f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_peer_fixture_receipt")
                val receipt =
                    f.port.enqueueOnce(f.peer, input.id, input.manifest, input.files, emptyMap())
                assertEquals(operation, receipt)
                val versions = f.db.peerImportDao().operationVersions(operation)
                assertEquals(staged.map(Uri::toString).toSet(), versions.map { it.uri }.toSet())
                assertEquals(1, f.db.galleryRestoreReceiptDao().get(receipt)!!.files)
                assertEquals(receipt, f.port.existing(input.id))
            }
        }

    @Test
    fun explicitCancelUsesWriterOwnedRollbackWhilePrivateOriginalSurvives() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val input = f.input(listOf(f.entry("source", "revision", 100)))
                f.failReceipt()
                assertTrue(
                    runCatching {
                            f.port.enqueueOnce(
                                f.peer,
                                input.id,
                                input.manifest,
                                input.files,
                                emptyMap(),
                            )
                        }
                        .isFailure
                )
                val operation = f.operation(input.id)
                val staged = f.journalUris(operation)
                f.owned.addAll(staged)
                assertEquals(1, staged.size)
                f.port.cancel(input.id)
                assertNull(f.port.existing(input.id))
                staged.forEach { uri ->
                    assertEquals(
                        0,
                        f.context.contentResolver
                            .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!
                            .use { it.count },
                    )
                }
                f.deleted.addAll(staged)
                assertArrayEquals(f.bytes, input.files.single().file.readBytes())
                assertFalse(
                    File(f.context.filesDir, "gallery-restore-journal/$operation.json").exists()
                )
            }
        }

    private data class Input(
        val id: String,
        val manifest: LocalSharingManifest,
        val files: List<LocalSharingReceivedFile>,
    )

    private class Fixture : AutoCloseable {
        private val base =
            InstrumentationRegistry.getInstrumentation().targetContext.also {
                check(it.packageName == "com.librestatic.lightforge.pdfacceptance")
            }
        private val uuid = UUID.randomUUID().toString()
        private val directory = File(base.cacheDir, "peer-import-fixture-$uuid").apply { mkdirs() }
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir() = directory

                override fun getApplicationContext(): Context = this
            }
        private val dbName = "peer-import-fixture-$uuid.db"
        var db = Room.databaseBuilder(context, GalleryDatabase::class.java, dbName).build()
        var port = GalleryLocalSharingImportPort(context, db)
        val peer = "a".repeat(64)
        val owned = linkedSetOf<Uri>()
        val deleted = linkedSetOf<Uri>()
        val transfers = mutableListOf<String>()
        val bytes =
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).let { bitmap ->
                try {
                    bitmap.eraseColor(android.graphics.Color.rgb(55, 95, 140))
                    ByteArrayOutputStream().use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                        out.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }

        fun entry(source: String, revision: String, modified: Long) =
            LocalSharingEntry(
                source,
                revision,
                "fixture.png",
                "image/png",
                bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                },
                modified,
                true,
            )

        fun input(entries: List<LocalSharingEntry>): Input {
            val id = UUID.randomUUID().toString()
            transfers += id
            val root = File(context.filesDir, "local-sharing/tasks/$id").apply { mkdirs() }
            return Input(
                id,
                LocalSharingManifest(entries, true),
                entries.mapIndexed { i, e ->
                    LocalSharingReceivedFile(
                        e,
                        File(root, "received-$i.partial").apply { writeBytes(bytes) },
                    )
                },
            )
        }

        fun failReceipt() {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_peer_fixture_receipt BEFORE INSERT ON gallery_restore_receipts BEGIN SELECT RAISE(ABORT, 'fixture interruption before receipt'); END"
            )
        }

        fun operation(id: String) =
            File(context.filesDir, "peer-import-requests/$id")
                .listFiles()!!
                .single { it.name.endsWith(".json") }
                .nameWithoutExtension

        fun journalUris(operation: String): List<Uri> {
            val f = File(context.filesDir, "gallery-restore-journal/$operation.json")
            if (!f.exists()) return emptyList()
            val j = JSONObject(f.readText())
            check(j.getString("operationId") == operation)
            val rows = j.getJSONArray("rows")
            return List(rows.length()) { rows.getJSONObject(it) }
                .filter { !it.isNull("uri") }
                .map { Uri.parse(it.getString("uri")) }
        }

        fun reopen() {
            db.close()
            db = Room.databaseBuilder(context, GalleryDatabase::class.java, dbName).build()
            port = GalleryLocalSharingImportPort(context, db)
        }

        override fun close() {
            runBlocking {
                transfers.forEach { id ->
                    runCatching { operation(id) }
                        .getOrNull()
                        ?.let { op ->
                            owned.addAll(journalUris(op))
                            owned.addAll(
                                db.peerImportDao().operationVersions(op).map { Uri.parse(it.uri) }
                            )
                        }
                }
                owned.filterNot { it in deleted }.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            }
            db.close()
            base.deleteDatabase(dbName)
            directory.deleteRecursively()
        }
    }
}
