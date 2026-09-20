package com.ugallery.feature.pdfstudio

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfImportInboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = PdfProjectDatabase.get(context)
    private val inbox = PdfImportInbox(context)
    private val repo = PdfProjectRepository(context)
    private val queue = PdfExportQueue(context)
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun sourceUri(): Uri {
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                "image/png",
                "inbox-fixture",
            )!!
        context.grantUriPermission(
            context.packageName,
            uri,
            read or write or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return uri
    }

    private fun request(uri: Uri) =
        PdfImportRequest(newId(), false, "fixture-project", "fixture-page", listOf(uri.toString()))

    @Test
    fun durableIntakeDoesNotWaitForAnActiveSourceCopyLock(): Unit = runBlocking {
        val uri = sourceUri()
        val request = request(uri)
        try {
            PdfStorageLock.mutex.lock()
            try {
                withTimeout(5_000) { inbox.stage(request) }
                assertNotNull(db.importDeliveries().get(request.id))
                assertTrue(
                    context.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission
                    }
                )
            } finally {
                PdfStorageLock.mutex.unlock()
            }
        } finally {
            inbox.finish(request.id)
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    @Test
    fun v6MigrationPreservesReceiptAndAddsEmptyInbox(): Unit = runBlocking {
        val name = "pdf-inbox-v6-${newId()}.db"
        val receipt = PdfImportReceipt(newId(), "existing-project")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(name),
                null,
            )
            .use { sql ->
                sql.execSQL(
                    "CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `updated` INTEGER NOT NULL, `manifest` TEXT NOT NULL, `editor` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))"
                )
                sql.execSQL(
                    "CREATE TABLE IF NOT EXISTS `export_jobs` (`id` TEXT NOT NULL, `workId` TEXT NOT NULL, `projectName` TEXT NOT NULL, `manifest` TEXT NOT NULL, `compact` INTEGER NOT NULL, `status` TEXT NOT NULL, `completed` INTEGER NOT NULL, `total` INTEGER NOT NULL, `created` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `destination` TEXT, `error` TEXT, `outputHash` TEXT, `outputBytes` INTEGER NOT NULL DEFAULT 0, `portable` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                )
                sql.execSQL(
                    "CREATE TABLE IF NOT EXISTS `destination_grants` (`uri` TEXT NOT NULL, `ownedFlags` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
                )
                sql.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_receipts` (`requestId` TEXT NOT NULL, `projectId` TEXT NOT NULL, PRIMARY KEY(`requestId`))"
                )
                sql.execSQL(
                    "INSERT INTO import_receipts VALUES(?,?)",
                    arrayOf<Any>(receipt.requestId, receipt.projectId),
                )
                sql.version = 6
            }
        val migrated =
            androidx.room.Room.databaseBuilder(context, PdfProjectDatabase::class.java, name)
                .addMigrations(PdfProjectDatabase.MIGRATION_6_7, PdfProjectDatabase.MIGRATION_7_8)
                .build()
        try {
            assertEquals(receipt, migrated.imports().get(receipt.requestId))
            assertTrue(migrated.importDeliveries().all().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sharedIncomingReadGrantPreservesPreexistingWritePermission(): Unit = runBlocking {
        val uri = sourceUri()
        context.contentResolver.takePersistableUriPermission(uri, write)
        val one = request(uri)
        val two = request(uri)
        try {
            inbox.stage(one)
            inbox.stage(two)
            assertEquals(read, db.destinationGrants().get(uri.toString())!!.ownedFlags)
            inbox.finish(one.id)
            assertTrue(
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }
            )
            inbox.finish(two.id)
            val kept = context.contentResolver.persistedUriPermissions.single { it.uri == uri }
            assertFalse(kept.isReadPermission)
            assertTrue(kept.isWritePermission)
            assertNull(db.destinationGrants().get(uri.toString()))
        } finally {
            inbox.finish(one.id)
            inbox.finish(two.id)
            context.contentResolver.releasePersistableUriPermission(uri, write)
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    @Test
    fun importCleanupDoesNotRevokeSourceStillReferencedByExport(): Unit = runBlocking {
        val uri = sourceUri()
        val request = request(uri)
        val p = PdfProject(name = "Shared URI")
        val job =
            PdfExportJob(
                newId(),
                newId(),
                p.name,
                PdfCodec.encode(p),
                false,
                PdfExportPhase.Ready.name,
                0,
                1,
                0,
                0,
                destination = uri.toString(),
            )
        try {
            inbox.stage(request)
            db.exports().put(job)
            inbox.finish(request.id)
            assertTrue(
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            )
            assertNotNull(db.destinationGrants().get(uri.toString()))
            db.exports().put(job.copy(status = PdfExportPhase.Published.name))
            queue.releaseUnusedGrants()
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        } finally {
            db.exports().delete(job.id)
            inbox.finish(request.id)
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    @Test
    fun restoredInboxWithNoSavedStateImportsAndCleansStaleAttempt(): Unit = runBlocking {
        val file = File.createTempFile("inbox-image-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(27, 43, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val p = PdfProject(name = "Durable intake", pages = listOf(PdfPage(), PdfPage()))
        repo.save(p, PdfEditorSession(pageId = p.pages[1].id))
        val request =
            PdfImportRequest(
                newId(),
                false,
                p.id,
                p.pages[1].id,
                listOf(Uri.fromFile(file).toString()),
            )
        inbox.stage(request)
        val stale = File(context.filesDir, "pdf-studio/import-${newId()}").apply { mkdirs() }
        File(stale, "source-0").writeText("partial source")
        val store = ViewModelStore()
        try {
            val vm =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(
                            context.applicationContext as Application,
                            SavedStateHandle(),
                        )
                        .also { store.put("pdf", it) }
                }
            withTimeout(15_000) {
                while (
                    db.imports().get(request.id) == null ||
                        db.importDeliveries().get(request.id) != null ||
                        vm.state.value.busy ||
                        vm.state.value.project == null
                ) delay(20)
            }
            assertEquals(p.id, vm.state.value.project!!.id)
            assertEquals(1, vm.state.value.project!!.pages[1].images.size)
            assertEquals(1, vm.state.value.page)
            assertTrue(vm.state.value.canUndo)
            assertFalse(stale.exists())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            inbox.finish(request.id)
            repo.delete(p.id)
            file.delete()
            stale.deleteRecursively()
        }
    }

    @Test
    fun committedDeliveryWithRemovedSourceAcknowledgesWithoutTakingAnotherGrant(): Unit =
        runBlocking {
            val p = PdfProject(name = "Already committed portable")
            repo.save(p)
            val missing = Uri.parse("content://missing-provider/removed")
            val request = PdfImportRequest(newId(), true, null, null, listOf(missing.toString()))
            db.imports().put(PdfImportReceipt(request.id, p.id))
            inbox.stage(request)
            val store = ViewModelStore()
            try {
                val vm =
                    withContext(Dispatchers.Main) {
                        PdfStudioViewModel(
                                context.applicationContext as Application,
                                SavedStateHandle(),
                            )
                            .also { store.put("pdf", it) }
                    }
                withTimeout(10_000) {
                    while (
                        db.importDeliveries().get(request.id) != null ||
                            vm.state.value.busy ||
                            vm.state.value.project == null
                    ) delay(20)
                }
                assertEquals(p.id, vm.state.value.project!!.id)
                assertNull(vm.state.value.message)
                assertNull(db.destinationGrants().get(missing.toString()))
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
                inbox.finish(request.id)
                repo.delete(p.id)
            }
        }

    @Test
    fun grantFailureRetainsOriginalSourceIndexAfterDuplicateUris(): Unit = runBlocking {
        val valid = sourceUri()
        val absent = Uri.parse("content://com.ugallery.absent.fixture/document/missing")
        val request =
            PdfImportRequest(
                newId(),
                false,
                "target",
                "page",
                listOf(valid.toString(), valid.toString(), absent.toString()),
            )
        try {
            try {
                inbox.stage(request)
                fail("Missing grant accepted")
            } catch (e: PdfSourceFailure) {
                assertEquals(3, e.number)
                assertEquals(PdfFailure.AccessDenied, PdfFailure.from(e))
            }
            assertNotNull(db.importDeliveries().get(request.id))
            inbox.finish(request.id)
            assertNull(db.importDeliveries().get(request.id))
            assertNull(db.destinationGrants().get(valid.toString()))
            assertNull(db.destinationGrants().get(absent.toString()))
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == valid })
        } finally {
            inbox.finish(request.id)
            DocumentsContract.deleteDocument(context.contentResolver, valid)
        }
    }
}
