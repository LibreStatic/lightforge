package com.ugallery.feature.pdfstudio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** The provider belongs to a different APK/UID; Android, not a mock, enforces revocation. */
internal class PdfProviderAccessProbe(private val context: Context) {
    private val db = PdfProjectDatabase.get(context)
    private val repo = PdfProjectRepository(context)
    private val inbox = PdfImportInbox(context)
    private val one =
        DocumentsContract.buildDocumentUri("com.ugallery.pdfprovider.fixture.documents", "one")
    private val two =
        DocumentsContract.buildDocumentUri("com.ugallery.pdfprovider.fixture.documents", "two")
    private val marker = File(context.filesDir, "pdf-provider-access.json")
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun request(cp: JSONObject) =
        PdfImportRequest(
            cp.getString("request"),
            false,
            cp.getString("project"),
            cp.getString("page"),
            listOf(one.toString(), one.toString(), two.toString()),
        )

    private fun hash(uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun writeOnlyOne() {
        val permission = context.contentResolver.persistedUriPermissions.single { it.uri == one }
        check(permission.isWritePermission && !permission.isReadPermission)
        check(context.contentResolver.persistedUriPermissions.none { it.uri == two })
    }

    private suspend fun awaitGrant(phase: String) {
        val waiting = File(context.filesDir, "pdf-provider-waiting")
        val ready = File(context.filesDir, "pdf-provider-ready")
        ready.delete()
        waiting.writeText(phase)
        withTimeout(40_000) { while (!ready.exists() || ready.readText() != phase) delay(30) }
        waiting.delete()
        ready.delete()
    }

    suspend fun prepare() {
        check(!marker.exists())
        awaitGrant("prepare")
        context.contentResolver.takePersistableUriPermission(one, write)
        val project =
            repo.import(PdfProject(name = "Cross UID provider access"), listOf(one), 0) { _, _ -> }
        val cp =
            JSONObject()
                .put("uid", Process.myUid())
                .put("project", project.id)
                .put("page", project.pages[0].id)
                .put("request", newId())
                .put("hashOne", hash(one))
                .put("hashTwo", hash(two))
        inbox.stage(request(cp))
        val original = db.projects().get(project.id)!!
        cp.put("manifest", original.manifest).put("editor", original.editor)
        marker.writeText(cp.toString())
        check(
            context.contentResolver.persistedUriPermissions.any {
                it.uri == two && it.isReadPermission
            }
        )
    }

    private suspend fun open(request: PdfImportRequest, store: ViewModelStore): PdfStudioViewModel =
        withContext(Dispatchers.Main) {
            val saved = SavedStateHandle(mapOf("projectId" to request.projectId))
            check(PdfImportPicker(saved).restore(request))
            PdfStudioViewModel(context.applicationContext as Application, saved).also {
                store.put("pdf", it)
            }
        }

    suspend fun revoked(baseline: Boolean) {
        val cp = JSONObject(marker.readText())
        val req = request(cp)
        check(context.contentResolver.persistedUriPermissions.none { it.uri == two })
        try {
            context.contentResolver.openInputStream(two)?.close()
            error("Revoked cross-UID URI remained readable")
        } catch (_: SecurityException) {}
        val store = ViewModelStore()
        try {
            val vm = open(req, store)
            withTimeout(20_000) {
                while (
                    vm.state.value.busy ||
                        vm.pendingImport.value != null ||
                        vm.state.value.message == null ||
                        vm.state.value.project == null
                ) delay(20)
            }
            check(vm.state.value.message == PdfFailure.AccessDenied.message)
            check(vm.state.value.sourceError == (if (baseline) null else 3)) {
                "Wrong source attribution: ${vm.state.value.sourceError}"
            }
            check(db.imports().get(req.id) == null && db.importDeliveries().get(req.id) == null)
            val original = db.projects().get(cp.getString("project"))!!
            check(
                original.manifest == cp.getString("manifest") &&
                    original.editor == cp.getString("editor")
            )
            writeOnlyOne()
            check(
                db.destinationGrants().get(one.toString()) == null &&
                    db.destinationGrants().get(two.toString()) == null
            )
            cp.put("revoked", true).put("sourceError", vm.state.value.sourceError ?: -1)
            marker.writeText(cp.toString())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    suspend fun retry() {
        awaitGrant("retry")
        val cp = JSONObject(marker.readText())
        val req = request(cp)
        val store = ViewModelStore()
        try {
            val vm = open(req, store)
            withTimeout(20_000) {
                while (
                    vm.state.value.busy ||
                        vm.pendingImport.value != null ||
                        db.imports().get(req.id) == null
                ) {
                    check(vm.state.value.message == null)
                    delay(20)
                }
            }
            val project = repo.load(req.projectId!!)!!
            check(project.pages.single().images.size == 4 && project.assets.size == 2)
            check(
                project.assets.map { it.hash }.toSet() ==
                    setOf(cp.getString("hashOne"), cp.getString("hashTwo"))
            )
            writeOnlyOne()
            context.contentResolver.takePersistableUriPermission(two, read)
            cp.put("retry", true)
            marker.writeText(cp.toString())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    suspend fun unavailable() {
        val cp = JSONObject(marker.readText())
        val before = db.projects().get(cp.getString("project"))!!
        val req =
            PdfImportRequest(
                newId(),
                false,
                before.id,
                cp.getString("page"),
                listOf(two.toString()),
            )
        val store = ViewModelStore()
        try {
            val vm = open(req, store)
            withTimeout(20_000) {
                while (
                    vm.state.value.busy ||
                        vm.pendingImport.value != null ||
                        vm.state.value.message == null
                ) delay(20)
            }
            check(
                vm.state.value.message in
                    listOf(PdfFailure.MissingFile.message, PdfFailure.AccessDenied.message)
            ) {
                "Unexpected unavailable failure: ${vm.state.value.message}"
            }
            check(db.projects().get(before.id) == before)
            check(db.imports().get(req.id) == null && db.importDeliveries().get(req.id) == null)
            check(db.destinationGrants().get(two.toString()) == null)
            check(
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == two && it.isReadPermission
                }
            )
            context.contentResolver.releasePersistableUriPermission(two, read)
            cp.put("unavailable", true).put("unavailableMessage", vm.state.value.message)
            marker.writeText(cp.toString())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    suspend fun finish() {
        val cp = JSONObject(marker.readText())
        check(cp.getBoolean("revoked") && cp.getBoolean("retry"))
        val project = repo.load(cp.getString("project"))!!
        val output = File(context.cacheDir, "provider-access.pdf")
        try {
            IsolatedPdfEngine(context)
                .export(project, project.assets.map { repo.file(it.hash) }, output, false)
            check(IsolatedPdfEngine(context).inspect(output).size == 1)
        } finally {
            output.delete()
        }
        if (
            context.contentResolver.persistedUriPermissions.any {
                it.uri == two && it.isReadPermission
            }
        )
            context.contentResolver.releasePersistableUriPermission(two, read)
        writeOnlyOne()
        context.contentResolver.releasePersistableUriPermission(one, write)
        repo.delete(project.id)
        marker.delete()
    }
}
