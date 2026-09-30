package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfImportPickerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)
    private val db = PdfProjectDatabase.get(context)

    private fun image(): File {
        val f = File.createTempFile("import-owner-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(21, 37, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return f
    }

    private suspend fun idle(vm: PdfStudioViewModel) =
        withTimeout(15_000) {
            while (
                vm.state.value.busy || vm.importPicker.request?.uris?.isNotEmpty() == true
            ) delay(20)
        }

    private suspend fun vm(store: ViewModelStore, saved: SavedStateHandle) =
        withContext(Dispatchers.Main) {
            PdfStudioViewModel(context.applicationContext as Application, saved).also {
                store.put("pdf", it)
            }
        }

    private fun saved(request: PdfImportRequest, open: String? = null): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                "projectId" to open,
                PdfImportPicker.KEY to
                    arrayListOf(
                            request.id,
                            if (request.portable) "portable" else "sources",
                            request.projectId.orEmpty(),
                            request.pageId.orEmpty(),
                        )
                        .apply { addAll(request.uris) },
            )
        )

    @Test
    fun v5MigrationPreservesProjectAndAddsEmptyReceiptJournal(): Unit = runBlocking {
        val name = "pdf-import-v5-${newId()}.db"
        val p = PdfProject(name = "Before import journal")
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
                    "INSERT INTO projects VALUES(?,?,?,?,?)",
                    arrayOf<Any>(p.id, p.name, p.updated, PdfCodec.encode(p), ""),
                )
                sql.version = 5
            }
        val migrated =
            androidx.room.Room.databaseBuilder(context, PdfProjectDatabase::class.java, name)
                .addMigrations(*PdfProjectDatabase.ALL_MIGRATIONS)
                .build()
        try {
            assertEquals(p, PdfCodec.decode(migrated.projects().get(p.id)!!.manifest))
            assertNull(migrated.imports().get("missing"))
            val receipt = PdfImportReceipt(newId(), p.id)
            migrated.imports().put(receipt)
            assertEquals(receipt, migrated.imports().get(receipt.requestId))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun resultWaitsForBusyEditorAndUsesOriginalPageIdInOtherProject() = busyDelivery(false)

    @Test fun cancellingUnrelatedBusyOperationDoesNotDiscardQueuedImport() = busyDelivery(true)

    private fun busyDelivery(cancelBusy: Boolean): Unit = runBlocking {
        val source = image()
        val a = PdfProject(name = "Original target", pages = listOf(PdfPage(), PdfPage()))
        val b = PdfProject(name = "Other editor")
        repo.save(a)
        repo.save(b)
        val store = ViewModelStore()
        val model = vm(store, SavedStateHandle(mapOf("projectId" to a.id)))
        try {
            idle(model)
            withContext(Dispatchers.Main) {
                model.selectPage(1)
                assertTrue(model.beginImport(false))
                model.movePage(-1) // selected page changes index after launch; ID remains its owner
            }
            val targetPage = a.pages[1].id
            PdfStorageLock.mutex.lock()
            try {
                withContext(Dispatchers.Main) { model.open(b.id) }
                withTimeout(10_000) { while (!model.state.value.busy) delay(20) }
                withContext(Dispatchers.Main) {
                    assertFalse(model.beginImport(true))
                    model.importResult(listOf(Uri.fromFile(source)))
                    model.importResult(listOf(Uri.fromFile(source)))
                }
                assertTrue(model.pendingImport.value != null)
                if (cancelBusy) withContext(Dispatchers.Main) { model.cancel() }
            } finally {
                PdfStorageLock.mutex.unlock()
            }
            idle(model)
            assertEquals(if (cancelBusy) a.id else b.id, model.state.value.project!!.id)
            assertTrue(repo.load(b.id)!!.pages.single().images.isEmpty())
            val (imported, editor) = repo.loadEditor(a.id)!!
            assertEquals(targetPage, imported.pages.first().id)
            assertEquals(1, imported.pages.first().images.size)
            assertTrue(imported.pages[1].images.isEmpty())
            assertEquals(2, editor.undo.size) // page move + import
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            repo.delete(a.id)
            repo.delete(b.id)
            source.delete()
        }
    }

    @Test
    fun savedDeliveryReplaysCommittedImportWithoutRereadingOrDuplicating(): Unit = runBlocking {
        val source = image()
        val p = PdfProject(name = "Replay import")
        repo.save(p)
        val request =
            PdfImportRequest(
                newId(),
                false,
                p.id,
                p.pages.single().id,
                listOf(Uri.fromFile(source).toString()),
            )
        val first = repo.importPicked(request) { _, _ -> }
        source.delete() // stale Activity saved state must acknowledge receipt, not reopen this URI
        val store = ViewModelStore()
        try {
            val model = vm(store, saved(request, p.id))
            idle(model)
            assertEquals(1, model.state.value.project!!.pages.single().images.size)
            assertEquals(first.pages, repo.load(p.id)!!.pages)
            assertEquals(1, repo.loadEditor(p.id)!!.second.undo.size)
            assertNull(model.state.value.message)
            withContext(Dispatchers.Main) { model.undo() }
            assertTrue(model.state.value.project!!.pages.single().images.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            repo.delete(p.id)
        }
    }

    @Test
    fun removedPageRejectsDeliveryWithoutFallingBackToCurrentPage(): Unit = runBlocking {
        val source = image()
        val p = PdfProject(name = "Removed page", pages = listOf(PdfPage(), PdfPage()))
        repo.save(p.copy(pages = p.pages.take(1)))
        val request =
            PdfImportRequest(
                newId(),
                false,
                p.id,
                p.pages[1].id,
                listOf(Uri.fromFile(source).toString()),
            )
        val store = ViewModelStore()
        try {
            val model = vm(store, saved(request, p.id))
            idle(model)
            assertEquals(R.string.pdf_failure_import_target, model.state.value.message)
            assertTrue(repo.load(p.id)!!.pages.single().images.isEmpty())
            assertNull(db.imports().get(request.id))
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            repo.delete(p.id)
            source.delete()
        }
    }

    @Test
    fun portableSavedDeliveryCreatesOnceAndDoesNotDiscardCurrentEdits(): Unit = runBlocking {
        val source = image()
        val portable =
            repo.import(PdfProject(name = "Portable incoming"), listOf(Uri.fromFile(source)), 0) {
                _,
                _ ->
            }
        val zip = repo.portable(portable)
        val current = PdfProject(name = "Unrelated project")
        repo.save(current)
        val request =
            PdfImportRequest(newId(), true, null, null, listOf(Uri.fromFile(zip).toString()))
        val store = ViewModelStore()
        var imported: PdfProject? = null
        try {
            val model = vm(store, SavedStateHandle(mapOf("projectId" to current.id)))
            idle(model)
            withContext(Dispatchers.Main) { model.update { it.copy(name = "Unsaved edit") } }
            // Recreated picker delivery can arrive while editor save is still pending.
            withContext(Dispatchers.Main) {
                assertTrue(model.beginImport(true))
                model.importResult(listOf(Uri.fromFile(zip)), true)
            }
            idle(model)
            imported = model.state.value.project!!
            assertNotEquals(current.id, imported.id)
            assertEquals("Unsaved edit", repo.load(current.id)!!.name)
            // Separate repository request verifies commit-before-ack replay with vanished source.
            val once = repo.importPicked(request) { _, _ -> }
            zip.delete()
            assertEquals(once.id, repo.importPicked(request) { _, _ -> }.id)
            repo.delete(once.id)
            assertEquals(
                PdfFailure.ImportTargetMissing,
                PdfFailure.from(
                    runCatching { repo.importPicked(request) { _, _ -> } }.exceptionOrNull()!!
                ),
            )
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            imported?.let { repo.delete(it.id) }
            repo.delete(current.id)
            repo.delete(portable.id)
            source.delete()
            zip.delete()
        }
    }

    @Test
    fun cancellationBeforeCommitLeavesNoReceiptAndRetryImportsOnce(): Unit = runBlocking {
        val source = image()
        val p = PdfProject(name = "Cancelled source import")
        repo.save(p)
        val request =
            PdfImportRequest(
                newId(),
                false,
                p.id,
                p.pages.single().id,
                listOf(Uri.fromFile(source).toString()),
            )
        try {
            val result = runCatching {
                repo.importPicked(request) { _, _ ->
                    throw CancellationException("cancel after source copy")
                }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertNull(db.imports().get(request.id))
            assertTrue(repo.load(p.id)!!.pages.single().images.isEmpty())
            assertEquals(1, repo.importPicked(request) { _, _ -> }.pages.single().images.size)
        } finally {
            repo.delete(p.id)
            source.delete()
        }
    }
}
