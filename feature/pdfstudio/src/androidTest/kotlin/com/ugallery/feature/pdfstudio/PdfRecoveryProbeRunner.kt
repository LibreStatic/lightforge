package com.ugallery.feature.pdfstudio

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit two-process probe, invoked only by tools/verify_pdf_process_recovery.py. */
class PdfRecoveryProbeRunner : AndroidJUnitRunner() {
    private var phase: String? = null

    override fun onCreate(arguments: Bundle?) {
        phase = arguments?.getString("phase")
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (phase == null) {
            super.onStart()
            return
        }
        try {
            runBlocking {
                when (phase) {
                    "write" -> writeCheckpoint()
                    "read" -> readCheckpoint()
                    "picker-prepare" -> preparePickerCheckpoint()
                    "portable-picker-prepare" -> preparePickerCheckpoint(true)
                    "portable-write" -> writeQueueCheckpoint(true)
                    "portable-read" -> readQueueCheckpoint()
                    "provider-prepare" -> PdfProviderAccessProbe(targetContext).prepare()
                    "provider-revoked-baseline" ->
                        PdfProviderAccessProbe(targetContext).revoked(true)
                    "provider-revoked" -> PdfProviderAccessProbe(targetContext).revoked(false)
                    "provider-retry" -> PdfProviderAccessProbe(targetContext).retry()
                    "provider-unavailable" -> PdfProviderAccessProbe(targetContext).unavailable()
                    "provider-finish" -> PdfProviderAccessProbe(targetContext).finish()
                    "gallery-write" -> PdfGalleryRecoveryProbe.write(targetContext)
                    "gallery-read" -> PdfGalleryRecoveryProbe.read(targetContext)
                    "inbox-write" -> writeInboxCheckpoint(false)
                    "inbox-portable-write" -> writeInboxCheckpoint(true)
                    "inbox-read",
                    "inbox-portable-read" -> readInboxCheckpoint()
                    "ui-cleanup" -> {
                        val marker = File(targetContext.filesDir, "pdf-ui-project")
                        if (marker.exists()) {
                            PdfProjectRepository(targetContext).delete(marker.readText())
                            marker.delete()
                        }
                    }
                    "renderer-pressure" -> rendererPressure()
                    "image-memory-baseline" -> PdfImageMemoryProbe(targetContext).run(true)
                    "image-memory" -> PdfImageMemoryProbe(targetContext).run(false)
                    "queue-write" -> writeQueueCheckpoint()
                    "queue-read" -> readQueueCheckpoint()
                    "publication-write" -> writePublicationCheckpoint(false)
                    "publication-complete-write" -> writePublicationCheckpoint(true)
                    "publication-read",
                    "publication-complete-read" -> readPublicationCheckpoint()
                    else -> error("Unknown phase")
                }
            }
            finish(
                Activity.RESULT_OK,
                Bundle().apply { putString("status", "PDF RECOVERY ${phase!!.uppercase()} PASS") },
            )
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString(
                        "status",
                        "PDF RECOVERY FAIL: ${e.javaClass.simpleName}: ${e.message}",
                    )
                },
            )
        }
    }

    private suspend fun preparePickerCheckpoint(portable: Boolean = false) {
        val checkpoint = File(targetContext.filesDir, "pdf-picker-checkpoint.json")
        check(!checkpoint.exists())
        listOf("created", "saved", "done").forEach {
            File(targetContext.filesDir, "pdf-picker-$it.json").delete()
        }
        val repo = PdfProjectRepository(targetContext)
        val file = File.createTempFile("picker-probe-", ".png", targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val p =
            try {
                repo.import(
                    PdfProject(name = "Picker process probe"),
                    listOf(Uri.fromFile(file)),
                    0,
                ) { _, _ ->
                }
            } finally {
                file.delete()
            }
        val queue = PdfExportQueue(targetContext)
        val job = queue.enqueue(p, false, portable)
        withTimeout(20_000) { while (queue.get(job.id)?.phase != PdfExportPhase.Ready) delay(20) }
        checkpoint.writeText(JSONObject().put("job", job.id).put("project", p.id).toString())
    }

    private val publicationCheckpoint: File
        get() = File(targetContext.filesDir, "pdf-publication-recovery.json")

    private fun publicationMarker(name: String) = File(targetContext.filesDir, name)

    private suspend fun writePublicationCheckpoint(complete: Boolean) {
        check(!publicationCheckpoint.exists()) { "Unfinished publication probe" }
        publicationMarker("pdf-publication-probe.entered").delete()
        val repo = PdfProjectRepository(targetContext)
        val queue = PdfExportQueue(targetContext)
        val source = File.createTempFile("publication-recovery-", ".png", targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val project =
            try {
                repo.import(
                    PdfProject(name = "Publication process recovery"),
                    listOf(Uri.fromFile(source)),
                    0,
                ) { _, _ ->
                }
            } finally {
                source.delete()
            }
        val job = queue.enqueue(project, false)
        withTimeout(30_000) {
            while (queue.get(job.id)?.phase != PdfExportPhase.Ready) {
                check(queue.get(job.id)?.phase != PdfExportPhase.Failed)
                delay(50)
            }
        }
        val ready = requireNotNull(queue.get(job.id))
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            requireNotNull(
                DocumentsContract.createDocument(
                    targetContext.contentResolver,
                    root,
                    "application/pdf",
                    "publication-recovery.pdf",
                )
            )
        targetContext.grantUriPermission(
            targetContext.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        publicationMarker("pdf-publication-probe.pause").writeText("process recovery")
        if (complete) publicationMarker("pdf-publication-probe.complete").writeText("full write")
        queue.publish(job.id, uri)
        val entered = publicationMarker("pdf-publication-probe.entered")
        withTimeout(30_000) { while (!entered.exists()) delay(50) }
        check(entered.readText() == Process.myPid().toString())
        check(queue.get(job.id)!!.phase == PdfExportPhase.Publishing)
        val written =
            targetContext.contentResolver.openInputStream(uri)!!.use {
                it.readBytes().size.toLong()
            }
        check(if (complete) written == ready.outputBytes else written in 1 until ready.outputBytes)
        repo.delete(project.id)
        check(repo.file(project.assets.single().hash).isFile)
        publicationCheckpoint.writeText(
            JSONObject()
                .put("job", job.id)
                .put("pid", Process.myPid())
                .put("uri", uri.toString())
                .put("hash", ready.outputHash)
                .put("bytes", ready.outputBytes)
                .put("complete", complete)
                .toString()
        )
    }

    private suspend fun readPublicationCheckpoint() {
        val saved = JSONObject(publicationCheckpoint.readText())
        check(saved.getInt("pid") != Process.myPid()) { "Same process" }
        listOf(
                "pdf-publication-probe.pause",
                "pdf-publication-probe.complete",
                "pdf-publication-probe.entered",
            )
            .forEach { publicationMarker(it).delete() }
        // Completed output must be recognized without opening it for writing again.
        val denied = publicationMarker("pdf-destination-denied")
        if (saved.getBoolean("complete")) denied.writeText("must not rewrite")
        val queue = PdfExportQueue(targetContext)
        val id = saved.getString("job")
        val uri = Uri.parse(saved.getString("uri"))
        queue.reconcile()
        withTimeout(45_000) {
            while (queue.get(id)?.phase != PdfExportPhase.Published) {
                check(queue.get(id)?.phase != PdfExportPhase.Failed) {
                    queue.get(id)?.error.orEmpty()
                }
                delay(100)
            }
        }
        withTimeout(10_000) {
            while (
                PdfProjectDatabase.get(targetContext).destinationGrants().get(uri.toString()) !=
                    null
            ) delay(20)
        }
        check(targetContext.contentResolver.persistedUriPermissions.none { it.uri == uri })
        val pdf = File.createTempFile("recovered-publication-", ".pdf", targetContext.cacheDir)
        try {
            targetContext.contentResolver.openInputStream(uri)!!.use { input ->
                pdf.outputStream().use { input.copyTo(it) }
            }
            check(pdf.length() == saved.getLong("bytes"))
            check(PdfProjectRepository.sha256(pdf) == saved.getString("hash"))
            check(IsolatedPdfEngine(targetContext).inspect(pdf).size == 1)
        } finally {
            pdf.delete()
            denied.delete()
        }
        queue.remove(id)
        DocumentsContract.deleteDocument(targetContext.contentResolver, uri)
        publicationCheckpoint.delete()
    }

    private suspend fun rendererPressure() {
        val checkpoint = File(targetContext.filesDir, "pdf-renderer-pressure.json")
        fun marker(name: String) = File(targetContext.filesDir, "pdf-renderer-$name")
        check(!checkpoint.exists())
        val p = PdfProject(name = "Renderer crash recovery", pages = List(3) { PdfPage() })
        val partial = File(targetContext.cacheDir, "renderer-pressure-${newId()}.pdf")
        val output = File(targetContext.cacheDir, "renderer-rebound-${newId()}.pdf")
        val host = Process.myPid()
        fun gated(stage: String) =
            IsolatedPdfEngine(
                targetContext,
                openOutput = { destination ->
                    PdfBlockedOutput.open(targetContext, destination) { bytes ->
                        check(bytes > 0)
                        checkpoint.writeText(
                            JSONObject()
                                .put("host", host)
                                .put("stage", stage)
                                .put("bytes", bytes)
                                .toString()
                        )
                        val deadline = System.nanoTime() + 60_000_000_000L
                        while (!marker("$stage.release").exists()) {
                            check(System.nanoTime() < deadline) { "Renderer observer deadline" }
                            Thread.sleep(20)
                        }
                    }
                },
            )
        try {
            val failure =
                runCatching { gated("blocked").export(p, emptyList(), partial, false) }
                    .exceptionOrNull()
                    ?: error("Renderer export should fail after its process is killed")
            check(PdfFailure.from(failure) == PdfFailure.RendererUnavailable) { failure.toString() }
            check(Process.myPid() == host && !partial.exists())
            gated("rebound").export(p, emptyList(), output, false)
            val firstPid = marker("blocked.pid").readText().trim().toInt()
            val nextPid = marker("rebound.pid").readText().trim().toInt()
            check(nextPid > 0 && nextPid != firstPid && nextPid != host)
            check(IsolatedPdfEngine(targetContext).inspect(output).size == 3)
            checkpoint.writeText(
                JSONObject()
                    .put("host", host)
                    .put("renderer", firstPid)
                    .put("replacement", nextPid)
                    .put("status", "PASS")
                    .put("error", PdfFailure.from(failure).name)
                    .toString()
            )
        } finally {
            partial.delete()
            output.delete()
            listOf("blocked.release", "rebound.release", "blocked.pid", "rebound.pid").forEach {
                marker(it).delete()
            }
        }
    }

    private val inboxCheckpoint: File
        get() = File(targetContext.filesDir, "pdf-inbox-recovery.json")

    private suspend fun writeInboxCheckpoint(portable: Boolean) {
        check(!inboxCheckpoint.exists())
        val source = File.createTempFile("inbox-probe-", ".png", targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(71)
        bitmap.setPixels(
            IntArray(256 * 256) { random.nextInt() or (0xff shl 24) },
            0,
            256,
            0,
            0,
            256,
            256,
        )
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val repo = PdfProjectRepository(targetContext)
        var project =
            PdfProject(name = "Active import recovery", pages = listOf(PdfPage(), PdfPage()))
        var archive: File? = null
        val hash = PdfProjectRepository.sha256(source)
        if (portable) {
            project = repo.import(project, listOf(Uri.fromFile(source)), 0) { _, _ -> }
            archive = repo.portable(project)
        } else repo.save(project, PdfEditorSession(pageId = project.pages[1].id))
        val input = archive ?: source
        check(input.length() > 65536)
        val inputSize = input.length()
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            DocumentsContract.createDocument(
                targetContext.contentResolver,
                root,
                if (portable) "application/zip" else "image/png",
                "incoming-source",
            )!!
        targetContext.contentResolver.openOutputStream(uri, "w")!!.use { out ->
            input.inputStream().use { it.copyTo(out) }
        }
        targetContext.grantUriPermission(
            targetContext.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        check(targetContext.contentResolver.persistedUriPermissions.none { it.uri == uri })
        val request =
            PdfImportRequest(
                newId(),
                portable,
                project.id,
                project.pages[1].id,
                listOf(uri.toString()),
            )
        PdfImportInbox(targetContext).stage(request)
        source.delete()
        archive?.delete()
        val gated =
            PdfProjectRepository(
                targetContext,
                sourceChunk = { bytes ->
                    val staged =
                        File(targetContext.filesDir, "pdf-studio").listFiles()!!.filter {
                            it.isDirectory && it.name.startsWith("import-")
                        }
                    val actualBytes =
                        staged.sumOf { dir ->
                            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        }
                    check(bytes > 0 && actualBytes > 0 && actualBytes < inputSize)
                    check(
                        PdfProjectDatabase.get(targetContext).importDeliveries().get(request.id) !=
                            null
                    )
                    check(
                        targetContext.contentResolver.persistedUriPermissions.any {
                            it.uri == uri && it.isReadPermission
                        }
                    )
                    inboxCheckpoint.writeText(
                        JSONObject()
                            .put("pid", Process.myPid())
                            .put("request", request.id)
                            .put("project", project.id)
                            .put("page", project.pages[1].id)
                            .put("hash", hash)
                            .put("uri", uri.toString())
                            .put("portable", portable)
                            .put("copied", actualBytes)
                            .put("total", inputSize)
                            .toString()
                    )
                    awaitCancellation() // test-only injection, after a REAL partial source write
                },
            )
        gated.importPicked(request) { _, _ -> }
        error("Copy gate must remain active until the fixture process is stopped")
    }

    private suspend fun readInboxCheckpoint() {
        val cp = JSONObject(inboxCheckpoint.readText())
        check(cp.getInt("pid") != Process.myPid())
        val uri = Uri.parse(cp.getString("uri"))
        check(
            targetContext.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        )
        val store = ViewModelStore()
        val vm =
            withContext(Dispatchers.Main) {
                PdfStudioViewModel(
                        targetContext.applicationContext as Application,
                        SavedStateHandle(),
                    )
                    .also { store.put("pdf", it) }
            }
        try {
            withTimeout(30_000) {
                while (
                    PdfProjectDatabase.get(targetContext).imports().get(cp.getString("request")) ==
                        null ||
                        PdfProjectDatabase.get(targetContext)
                            .importDeliveries()
                            .get(cp.getString("request")) != null ||
                        vm.state.value.busy ||
                        vm.state.value.project == null
                ) {
                    check(vm.state.value.message == null) {
                        "Import failed: ${vm.state.value.message}"
                    }
                    delay(30)
                }
            }
            val p = vm.state.value.project!!
            check(p.pages.size == 2)
            if (cp.getBoolean("portable")) {
                check(p.id != cp.getString("project"))
                check(p.pages.first().images.size == 1)
            } else {
                check(p.id == cp.getString("project"))
                check(p.pages[1].id == cp.getString("page"))
                check(p.pages[1].images.size == 1)
            }
            check(p.assets.single().hash == cp.getString("hash"))
            check(
                PdfProjectRepository.sha256(vm.repository.file(p.assets.single().hash)) ==
                    cp.getString("hash")
            )
            check(targetContext.contentResolver.persistedUriPermissions.none { it.uri == uri })
            check(
                PdfProjectDatabase.get(targetContext).destinationGrants().get(uri.toString()) ==
                    null
            )
            check(
                File(targetContext.filesDir, "pdf-studio").listFiles()!!.none {
                    it.isDirectory && it.name.startsWith("import-")
                }
            )
            val pdf = vm.repository.prepareExport(p, false)
            check(IsolatedPdfEngine(targetContext).inspect(pdf).size == 2)
            pdf.delete()
            vm.repository.delete(p.id)
            if (cp.getBoolean("portable")) vm.repository.delete(cp.getString("project"))
            check(DocumentsContract.deleteDocument(targetContext.contentResolver, uri))
            inboxCheckpoint.delete()
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    private val queueCheckpoint: File
        get() = File(targetContext.filesDir, "pdf-queue-recovery.json")

    private suspend fun writeQueueCheckpoint(portable: Boolean = false) {
        check(!queueCheckpoint.exists())
        val pause =
            File(
                targetContext.filesDir,
                if (portable) "pdf-portable-probe.pause" else "pdf-worker-probe.pause",
            )
        val entered =
            File(
                targetContext.filesDir,
                if (portable) "pdf-portable-probe.entered" else "pdf-worker-probe.entered",
            )
        entered.delete()
        pause.writeText("process recovery probe")
        val repo = PdfProjectRepository(targetContext)
        val queue = PdfExportQueue(targetContext)
        val source = File.createTempFile("queue-recovery-", ".png", targetContext.cacheDir)
        val b = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        val p =
            try {
                repo.import(
                    PdfProject(name = "Worker process recovery"),
                    listOf(Uri.fromFile(source)),
                    0,
                ) { _, _ ->
                }
            } finally {
                source.delete()
            }
        val job =
            queue.enqueue(
                p.copy(pages = listOf(p.pages[0], p.pages[0].copy(id = newId()))),
                false,
                portable,
            )
        withTimeout(30_000) { while (!entered.exists()) delay(50) }
        check(queue.get(job.id)!!.phase == PdfExportPhase.Running)
        check(entered.readText() == Process.myPid().toString())
        repo.delete(p.id)
        check(repo.file(p.assets.single().hash).isFile)
        queueCheckpoint.writeText(
            JSONObject()
                .put("job", job.id)
                .put("pid", Process.myPid())
                .put("portable", portable)
                .toString()
        )
    }

    private suspend fun readQueueCheckpoint() {
        val o = JSONObject(queueCheckpoint.readText())
        check(o.getInt("pid") != Process.myPid())
        File(targetContext.filesDir, "pdf-worker-probe.pause").delete()
        File(targetContext.filesDir, "pdf-worker-probe.entered").delete()
        File(targetContext.filesDir, "pdf-portable-probe.pause").delete()
        File(targetContext.filesDir, "pdf-portable-probe.entered").delete()
        val queue = PdfExportQueue(targetContext)
        val id = o.getString("job")
        queue.reconcile()
        withTimeout(45_000) {
            while (true) {
                val job = requireNotNull(queue.get(id))
                check(job.phase != PdfExportPhase.Cancelled && job.phase != PdfExportPhase.Failed) {
                    job.error.orEmpty()
                }
                if (job.phase == PdfExportPhase.Ready) break
                delay(100)
            }
        }
        check(queue.get(id)!!.completed == 2)
        if (o.optBoolean("portable")) {
            check(queue.get(id)!!.portable)
            PdfPortableArchive.verify(queue.output(id), PdfCodec.decode(queue.get(id)!!.manifest))
            val repo = PdfProjectRepository(targetContext)
            val imported = repo.importPortable(Uri.fromFile(queue.output(id)))
            check(imported.pages.size == 2)
            repo.delete(imported.id)
        } else check(IsolatedPdfEngine(targetContext).inspect(queue.output(id)).size == 2)
        queue.remove(id)
        queueCheckpoint.delete()
    }

    private val checkpoint: File
        get() = File(targetContext.filesDir, "pdf-recovery-probe.json")

    private suspend fun writeCheckpoint() {
        check(!checkpoint.exists()) { "An unfinished probe checkpoint exists" }
        val repo = PdfProjectRepository(targetContext)
        val source = File.createTempFile("recovery-", ".png", targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val before =
                repo.import(PdfProject(name = "Recovery probe"), listOf(Uri.fromFile(source)), 0) {
                    _,
                    _ ->
                }
            val p =
                before.copy(
                    pages = listOf(PdfPage(), before.pages[0]),
                    columns = 4,
                    gap = 6.0,
                    snap = true,
                )
            val s =
                PdfEditorSession(
                    pageId = p.pages[1].id,
                    imageId = p.pages[1].images[0].id,
                    selectedPages = setOf(p.pages[1].id),
                    undo = listOf(before),
                    redo = listOf(p.copy(name = "Redo survives")),
                    zoom = 2f,
                    panX = 14f,
                    panY = -28f,
                )
            repo.save(p, s)
            checkpoint.writeText(
                JSONObject().put("project", p.id).put("pid", Process.myPid()).toString()
            )
        } finally {
            source.delete()
        }
    }

    private suspend fun readCheckpoint() {
        val o = JSONObject(checkpoint.readText())
        check(o.getInt("pid") != Process.myPid()) { "The probe did not cross a process boundary" }
        val id = o.getString("project")
        val store = ViewModelStore()
        val repo = PdfProjectRepository(targetContext)
        try {
            val vm =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(
                            targetContext.applicationContext as Application,
                            SavedStateHandle(mapOf("projectId" to id)),
                        )
                        .also { store.put("probe", it) }
                }
            withTimeout(10_000) {
                while (vm.state.value.project == null || vm.state.value.busy) delay(20)
            }
            val s = vm.state.value
            val p = requireNotNull(s.project)
            check(s.page == 1 && s.image == 0 && s.selectedPages == setOf(p.pages[1].id))
            check(s.zoom == 2f && s.panX == 14f && s.panY == -28f)
            check(p.columns == 4 && p.gap == 6.0 && p.snap && s.canUndo && s.canRedo)
            val pdf = repo.prepareExport(p, false)
            check(pdf.length() > 0)
            pdf.delete()
            withContext(Dispatchers.Main) { vm.redo() }
            check(vm.state.value.project!!.name == "Redo survives")
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            repo.delete(id)
            checkpoint.delete()
        }
    }
}
