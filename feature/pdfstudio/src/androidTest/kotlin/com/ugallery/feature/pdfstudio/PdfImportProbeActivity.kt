package com.ugallery.feature.pdfstudio

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Only the test APK exposes this activity; all import code is the production ViewModel/repository.
 */
class PdfImportProbeActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[PdfStudioViewModel::class.java] }
    private val sources =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            vm.importResult(it)
        }
    private val portable =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            vm.importResult(listOfNotNull(it), true)
        }

    private fun marker(name: String) = File(filesDir, "pdf-import-$name.json")

    private fun checkpoint() = JSONObject(marker("checkpoint").readText())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val zip = intent.getBooleanExtra("portable", false)
        marker("created")
            .writeText(
                JSONObject()
                    .put("pid", Process.myPid())
                    .put("restored", savedInstanceState != null)
                    .put("pending", vm.importPicker.request?.id)
                    .toString()
            )
        val button =
            Button(this).apply {
                text = "Choose import source"
                isEnabled = false
                setOnClickListener {
                    if (vm.beginImport(zip)) {
                        val saved = checkpoint().put("request", vm.importPicker.request!!.id)
                        marker("checkpoint").writeText(saved.toString())
                        if (zip) portable.launch(arrayOf("application/zip"))
                        else sources.launch(arrayOf("image/png"))
                    }
                }
            }
        setContentView(button)
        lifecycleScope.launch {
            try {
                if (savedInstanceState == null) prepare(zip)
                withTimeout(15_000) { while (vm.state.value.busy) delay(20) }
                button.isEnabled = true
                withTimeout(90_000) {
                    while (true) {
                        val id = checkpoint().optString("request")
                        if (
                            id.isNotEmpty() &&
                                PdfProjectDatabase.get(this@PdfImportProbeActivity)
                                    .imports()
                                    .get(id) != null &&
                                vm.pendingImport.value == null &&
                                !vm.state.value.busy
                        )
                            break
                        check(vm.state.value.message == null) {
                            "Import error: ${vm.state.value.message}"
                        }
                        delay(50)
                    }
                }
                val cp = checkpoint()
                val receipt =
                    PdfProjectDatabase.get(this@PdfImportProbeActivity)
                        .imports()
                        .get(cp.getString("request"))!!
                val p = vm.repository.load(receipt.projectId)!!
                check(p.pages.size == 2)
                if (zip) {
                    check(p.id != cp.getString("project"))
                    check(p.pages.first().images.size == 1)
                } else {
                    check(p.id == cp.getString("project"))
                    check(p.pages.first().images.isEmpty())
                    check(p.pages[1].id == cp.getString("page"))
                    check(p.pages[1].images.size == 1)
                    check(vm.state.value.page == 1)
                    check(vm.state.value.canUndo)
                }
                p.assets.forEach {
                    check(PdfProjectRepository.sha256(vm.repository.file(it.hash)) == it.hash)
                }
                check(contentResolver.delete(Uri.parse(cp.getString("uri")), null, null) == 1)
                vm.repository.delete(p.id)
                if (zip) vm.repository.delete(cp.getString("project"))
                marker("done")
                    .writeText(
                        JSONObject()
                            .put("pid", Process.myPid())
                            .put("status", "PASS")
                            .put("portable", zip)
                            .put("request", receipt.requestId)
                            .put("pages", p.pages.size)
                            .toString()
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                marker("done")
                    .writeText(
                        JSONObject().put("status", "FAIL").put("error", e.toString()).toString()
                    )
            }
        }
    }

    private suspend fun prepare(zip: Boolean) {
        val source = File.createTempFile("import-probe-", ".png", cacheDir)
        val bitmap = Bitmap.createBitmap(24, 42, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var p = PdfProject(name = "Import process fixture", pages = listOf(PdfPage(), PdfPage()))
        var archive: File? = null
        try {
            if (zip) {
                p = vm.repository.import(p, listOf(Uri.fromFile(source)), 0) { _, _ -> }
                archive = vm.repository.portable(p)
            } else vm.repository.save(p, PdfEditorSession(pageId = p.pages[1].id))
            val name = "UGallery-import-${p.id}" + if (zip) ".zip" else ".png"
            val uri =
                contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            if (zip) "application/zip" else "image/png",
                        )
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "w")!!.use { out ->
                    (archive ?: source).inputStream().use { it.copyTo(out) }
                }
            }
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            marker("checkpoint")
                .writeText(
                    JSONObject()
                        .put("pid", Process.myPid())
                        .put("project", p.id)
                        .put("page", p.pages[1].id)
                        .put("uri", uri.toString())
                        .put("name", name)
                        .toString()
                )
            vm.open(p.id)
        } finally {
            source.delete()
            archive?.delete()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        marker("saved")
            .writeText(
                JSONObject()
                    .put("pid", Process.myPid())
                    .put("pending", vm.importPicker.request?.id)
                    .put("page", vm.importPicker.request?.pageId)
                    .toString()
            )
    }
}
