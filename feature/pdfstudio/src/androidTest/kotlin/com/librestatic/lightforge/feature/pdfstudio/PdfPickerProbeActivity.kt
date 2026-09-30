package com.librestatic.lightforge.feature.pdfstudio

import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Test APK only: real Android CreateDocument and automatic SavedState/ActivityResult restoration.
 */
class PdfPickerProbeActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[PdfStudioViewModel::class.java] }
    private val picker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
            vm.publicationResult(it)
        }

    private val portablePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
            vm.publicationResult(it)
        }

    private fun marker(name: String) = File(filesDir, "pdf-picker-$name.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jobId = intent.getStringExtra("jobId")!!
        marker("created")
            .writeText(
                JSONObject()
                    .put("pid", Process.myPid())
                    .put("restored", savedInstanceState != null)
                    .put("pending", vm.publishPicker.request?.first)
                    .toString()
            )
        setContentView(
            Button(this).apply {
                text = "Choose PDF destination"
                setOnClickListener {
                    if (vm.beginPublication(jobId) == PublishStart.Launch) {
                        if (intent.getBooleanExtra("portable", false))
                            portablePicker.launch("Lightforge-picker-${jobId}.ugpdfproject")
                        else picker.launch("Lightforge-picker-${jobId}.pdf")
                    }
                }
            }
        )
        lifecycleScope.launch {
            try {
                withTimeout(45_000) {
                    while (vm.exportQueue.get(jobId)?.phase != PdfExportPhase.Published) {
                        check(vm.exportQueue.get(jobId)?.phase != PdfExportPhase.Failed)
                        delay(50)
                    }
                }
                val job = requireNotNull(vm.exportQueue.get(jobId))
                val uri = Uri.parse(job.destination!!)
                val file = File.createTempFile("picker-verified-", ".pdf", cacheDir)
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)!!.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                        check(file.length() == job.outputBytes)
                        check(PdfProjectRepository.sha256(file) == job.outputHash)
                        if (job.portable) {
                            PdfPortableArchive.verify(file, PdfCodec.decode(job.manifest))
                            val restored = vm.repository.importPortable(Uri.fromFile(file))
                            check(restored.pages.size == 1)
                            vm.repository.delete(restored.id)
                        } else
                            check(
                                IsolatedPdfEngine(this@PdfPickerProbeActivity).inspect(file).size ==
                                    1
                            )
                    }
                } finally {
                    file.delete()
                }
                withTimeout(10_000) {
                    while (
                        PdfProjectDatabase.get(this@PdfPickerProbeActivity)
                            .destinationGrants()
                            .get(uri.toString()) != null
                    ) delay(20)
                }
                check(contentResolver.persistedUriPermissions.none { it.uri == uri })
                check(vm.pendingPublication.value == null)
                check(DocumentsContract.deleteDocument(contentResolver, uri))
                vm.exportQueue.remove(jobId)
                vm.repository.delete(intent.getStringExtra("projectId")!!)
                marker("done")
                    .writeText(
                        JSONObject()
                            .put("pid", Process.myPid())
                            .put("status", "PASS")
                            .put("portable", job.portable)
                            .put("hash", job.outputHash)
                            .put("bytes", job.outputBytes)
                            .toString()
                    )
                marker("checkpoint").delete()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        marker("saved")
            .writeText(
                JSONObject()
                    .put("pid", Process.myPid())
                    .put("pending", vm.publishPicker.request?.first)
                    .toString()
            )
    }
}
