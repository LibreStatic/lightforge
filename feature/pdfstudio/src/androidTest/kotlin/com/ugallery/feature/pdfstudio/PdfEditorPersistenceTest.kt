package com.ugallery.feature.pdfstudio

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfEditorPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrationPreservesV1Projects(): Unit = runBlocking {
        val name = "pdf-migration-${newId()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val p = PdfProject(name = "Before migration")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, updated INTEGER NOT NULL, manifest TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO projects VALUES(?,?,?,?)",
                arrayOf<Any>(p.id, p.name, p.updated, PdfCodec.encode(p)),
            )
            db.version = 1
        }
        val db =
            Room.databaseBuilder(context, PdfProjectDatabase::class.java, name)
                .addMigrations(
                    PdfProjectDatabase.MIGRATION_1_2,
                    PdfProjectDatabase.MIGRATION_2_3,
                    PdfProjectDatabase.MIGRATION_3_4,
                    PdfProjectDatabase.MIGRATION_4_5,
                    PdfProjectDatabase.MIGRATION_5_6,
                    PdfProjectDatabase.MIGRATION_6_7,
                    PdfProjectDatabase.MIGRATION_7_8,
                )
                .build()
        try {
            val row = db.projects().get(p.id)!!
            assertEquals(p, PdfCodec.decode(row.manifest))
            assertEquals("", row.editor)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationPreservesV3JobAndDoesNotClaimExistingGrants(): Unit = runBlocking {
        val name = "pdf-migration-v3-${newId()}.db"
        val file = context.getDatabasePath(name)
        val project = PdfProject(name = "Queued before upgrade")
        val jobId = newId()
        val workId = newId()
        val uri = "content://test/existing-output"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, updated INTEGER NOT NULL, manifest TEXT NOT NULL, editor TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL(
                "CREATE TABLE export_jobs (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, projectName TEXT NOT NULL, manifest TEXT NOT NULL, compact INTEGER NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, destination TEXT, error TEXT)"
            )
            db.execSQL(
                "INSERT INTO projects VALUES(?,?,?,?,?)",
                arrayOf<Any>(
                    project.id,
                    project.name,
                    project.updated,
                    PdfCodec.encode(project),
                    "",
                ),
            )
            db.execSQL(
                "INSERT INTO export_jobs VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    jobId,
                    workId,
                    project.name,
                    PdfCodec.encode(project),
                    0,
                    "Publishing",
                    1,
                    1,
                    100L,
                    200L,
                    uri,
                    null,
                ),
            )
            db.version = 3
        }
        val db =
            Room.databaseBuilder(context, PdfProjectDatabase::class.java, name)
                .addMigrations(
                    PdfProjectDatabase.MIGRATION_3_4,
                    PdfProjectDatabase.MIGRATION_4_5,
                    PdfProjectDatabase.MIGRATION_5_6,
                    PdfProjectDatabase.MIGRATION_6_7,
                    PdfProjectDatabase.MIGRATION_7_8,
                )
                .build()
        try {
            assertEquals(project, PdfCodec.decode(db.projects().get(project.id)!!.manifest))
            val job = db.exports().get(jobId)!!
            assertEquals(workId, job.workId)
            assertEquals(PdfExportPhase.Publishing, job.phase)
            assertEquals(uri, job.destination)
            assertEquals(project, PdfCodec.decode(job.manifest))
            assertNull(job.outputHash)
            assertEquals(0L, job.outputBytes)
            assertEquals(0, db.destinationGrants().get(uri)!!.ownedFlags)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationV4KeepsPdfKindHashesAndGrantOwnership(): Unit = runBlocking {
        val name = "pdf-migration-v4-${newId()}.db"
        val file = context.getDatabasePath(name)
        val p = PdfProject(name = "Before portable queue")
        val id = newId()
        val uri = "content://fixture/existing"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,updated INTEGER NOT NULL,manifest TEXT NOT NULL,editor TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL(
                "CREATE TABLE export_jobs (id TEXT NOT NULL PRIMARY KEY,workId TEXT NOT NULL,projectName TEXT NOT NULL,manifest TEXT NOT NULL,compact INTEGER NOT NULL,status TEXT NOT NULL,completed INTEGER NOT NULL,total INTEGER NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,destination TEXT,error TEXT,outputHash TEXT,outputBytes INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE destination_grants (uri TEXT NOT NULL PRIMARY KEY,ownedFlags INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO export_jobs VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    id,
                    newId(),
                    p.name,
                    PdfCodec.encode(p),
                    0,
                    "Ready",
                    1,
                    1,
                    1L,
                    1L,
                    uri,
                    null,
                    "a".repeat(64),
                    42L,
                ),
            )
            db.execSQL("INSERT INTO destination_grants VALUES(?,?)", arrayOf<Any>(uri, 3))
            db.version = 4
        }
        val db =
            Room.databaseBuilder(context, PdfProjectDatabase::class.java, name)
                .addMigrations(
                    PdfProjectDatabase.MIGRATION_4_5,
                    PdfProjectDatabase.MIGRATION_5_6,
                    PdfProjectDatabase.MIGRATION_6_7,
                    PdfProjectDatabase.MIGRATION_7_8,
                )
                .build()
        try {
            val row = db.exports().get(id)!!
            assertFalse(row.portable)
            assertEquals("a".repeat(64), row.outputHash)
            assertEquals(42L, row.outputBytes)
            assertEquals(PdfExportPhase.Ready, row.phase)
            assertEquals(3, db.destinationGrants().get(uri)!!.ownedFlags)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun historyProtectsRemovedSourcesAcrossReopenAndGarbageCollection(): Unit = runBlocking {
        val repo = PdfProjectRepository(context)
        val source = File.createTempFile("pdf-history-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var p = PdfProject(name = "History source")
        val unrelated = PdfProject(name = "Other")
        try {
            p = repo.import(p, listOf(Uri.fromFile(source)), 0) { _, _ -> }
            val removed = p.copy(pages = listOf(p.pages[0].copy(images = emptyList())))
            repo.save(removed, PdfEditorSession(pageId = removed.pages[0].id, undo = listOf(p)))
            source.delete()
            repo.save(unrelated)
            repo.delete(unrelated.id)
            val fresh =
                Room.databaseBuilder(context, PdfProjectDatabase::class.java, "pdf-projects.db")
                    .addMigrations(
                        PdfProjectDatabase.MIGRATION_1_2,
                        PdfProjectDatabase.MIGRATION_2_3,
                        PdfProjectDatabase.MIGRATION_3_4,
                        PdfProjectDatabase.MIGRATION_4_5,
                        PdfProjectDatabase.MIGRATION_5_6,
                        PdfProjectDatabase.MIGRATION_6_7,
                        PdfProjectDatabase.MIGRATION_7_8,
                    )
                    .build()
            try {
                val row = fresh.projects().get(p.id)!!
                val current = PdfCodec.decode(row.manifest)
                val session = PdfEditorSessionCodec.decode(row.editor, current)
                assertTrue(current.pages[0].images.isEmpty())
                assertEquals(1, session.undo.size)
                assertTrue(repo.file(p.assets.single().hash).isFile)
                val pdf = repo.prepareExport(session.undo.single(), false)
                assertTrue(pdf.length() > 0)
                pdf.delete()
            } finally {
                fresh.close()
            }
        } finally {
            repo.delete(p.id)
            repo.delete(unrelated.id)
            source.delete()
        }
    }

    @Test
    fun recreatedViewModelRestoresViewportSelectionUndoAndRedo(): Unit = runBlocking {
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        var id: String? = null
        try {
            val first =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(
                            context.applicationContext as Application,
                            SavedStateHandle(),
                        )
                        .also {
                            firstStore.put("pdf", it)
                            it.newProject("Recreation")
                        }
                }
            withTimeout(10_000) {
                while (first.state.value.project == null || first.state.value.busy) delay(20)
            }
            id = first.state.value.project!!.id
            withContext(Dispatchers.Main) {
                first.addPage()
                first.selectPage(1)
                first.selectExportPage(first.state.value.project!!.pages[1].id, true)
                first.update { it.copy(columns = 4, gap = 8.0, snap = true) }
                first.update { it.copy(name = "Renamed") }
                first.undo()
                first.viewport(2f, 12f, -24f)
                first.save()
            }
            withTimeout(10_000) { while (first.state.value.busy) delay(20) }
            assertEquals(R.string.pdf_savedlocal, first.state.value.message)
            val expected = first.state.value
            withContext(Dispatchers.Main) { firstStore.clear() }
            val second =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(
                            context.applicationContext as Application,
                            SavedStateHandle(mapOf("projectId" to id)),
                        )
                        .also { secondStore.put("pdf", it) }
                }
            withTimeout(10_000) {
                while (second.state.value.project == null || second.state.value.busy) delay(20)
            }
            val actual = second.state.value
            assertEquals(expected.page, actual.page)
            assertEquals(expected.selectedPages, actual.selectedPages)
            assertEquals(2f, actual.zoom, 0f)
            assertEquals(12f, actual.panX, 0f)
            assertEquals(-24f, actual.panY, 0f)
            assertTrue(actual.canUndo)
            assertTrue(actual.canRedo)
            assertTrue(actual.project!!.snap)
            assertEquals(4, actual.project.columns)
            withContext(Dispatchers.Main) { second.redo() }
            assertEquals("Renamed", second.state.value.project!!.name)
        } finally {
            withContext(Dispatchers.Main) {
                firstStore.clear()
                secondStore.clear()
            }
            id?.let { PdfProjectRepository(context).delete(it) }
        }
    }
}
