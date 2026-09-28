package com.librestatic.lightforge.feature.pdfstudio

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host only: every project edit, import and export is driven through production screen controls.
 */
class PdfFlowProbeActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[PdfStudioViewModel::class.java] }

    private fun marker(name: String) = File(filesDir, "pdf-flow-$name.json")

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(
            base.createConfigurationContext(
                Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightforgeTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PdfStudioScreen(onExit = { finish() }, vm = vm)
                }
            }
        }
        lifecycleScope.launch {
            try {
                if (!marker("input").exists()) prepare()
                val seen = linkedSetOf<String>()
                val verified = mutableSetOf<String>()
                while (isActive) {
                    val current = vm.state.value
                    current.project?.let { seen.add(it.id) }
                    val jobs =
                        PdfProjectDatabase.get(this@PdfFlowProbeActivity).exports().all().filter {
                            PdfCodec.decode(it.manifest).id in seen
                        }
                    for (job in
                        jobs.filter {
                            it.phase == PdfExportPhase.Published && it.id !in verified
                        }) {
                        val local =
                            File.createTempFile(
                                "flow-published-",
                                if (job.portable) ".zip" else ".pdf",
                                cacheDir,
                            )
                        try {
                            contentResolver.openInputStream(Uri.parse(job.destination))!!.use {
                                input ->
                                local.outputStream().use { input.copyTo(it) }
                            }
                            check(local.length() == job.outputBytes)
                            check(PdfProjectRepository.sha256(local) == job.outputHash)
                            if (job.portable)
                                PdfPortableArchive.verify(local, PdfCodec.decode(job.manifest))
                            else
                                check(
                                    IsolatedPdfEngine(this@PdfFlowProbeActivity)
                                        .inspect(local)
                                        .size == 2
                                )
                            verified.add(job.id)
                        } finally {
                            local.delete()
                        }
                    }
                    val state =
                        JSONObject()
                            .put("busy", current.busy)
                            .put("project", current.project?.id)
                            .put("pages", current.project?.pages?.size ?: 0)
                            .put("assets", current.project?.assets?.size ?: 0)
                            .put("images", current.project?.pages?.sumOf { it.images.size } ?: 0)
                            .put("name", current.project?.name)
                            // Phase G4: page geometry so verify_pdf_screen_flow.py can assert a
                            // template's paper/margin/columns landed on the created project
                            // without relying on a screenshot or Layout-panel navigation.
                            .put("pageWidthMm", current.project?.pages?.firstOrNull()?.width)
                            .put("pageHeightMm", current.project?.pages?.firstOrNull()?.height)
                            .put("pageMarginMm", current.project?.pages?.firstOrNull()?.margin)
                            .put("columns", current.project?.columns)
                            // Feedback item B: the print size (null = free grid) and the COMPUTED
                            // photos-per-page count for the current page/gap, so
                            // verify_pdf_screen_flow.py can assert the "Print size" selector and
                            // its live count landed on the created project without relying on a
                            // screenshot.
                            .put("printSize", current.project?.printSize)
                            .put(
                                "photosPerPage",
                                current.project?.let { proj ->
                                    val size = PdfPrintSize.fromId(proj.printSize)
                                    val page = proj.pages.firstOrNull()
                                    if (size != null && page != null)
                                        PdfPrintLayout.fit(page.width, page.height, page.margin, proj.gap, size).perPage
                                    else null
                                },
                            )
                            .put("placementMode", current.project?.placementMode?.name)
                            .put("page", current.page)
                            .put("canUndo", current.canUndo)
                            .put("message", current.message)
                            .put("seen", JSONArray(seen.toList()))
                            .put(
                                "jobs",
                                JSONArray(
                                    jobs.map { job ->
                                        JSONObject()
                                            .put("id", job.id)
                                            .put("portable", job.portable)
                                            .put("compact", job.compact)
                                            .put("phase", job.phase.name)
                                            .put("verified", job.id in verified)
                                            .put("destination", job.destination)
                                            .put("error", job.error)
                                    }
                                ),
                            )
                    marker("state").writeText(state.toString())
                    if (File(filesDir, "pdf-flow-finish").exists()) {
                        check(seen.size == 2)
                        check(jobs.size == 2 && jobs.all { it.id in verified })
                        val original = vm.repository.load(seen.first())!!
                        val restored = vm.repository.load(seen.last())!!
                        check(
                            original.pages == restored.pages && original.assets == restored.assets
                        )
                        val input = JSONObject(marker("input").readText())
                        for (job in jobs) {
                            check(
                                DocumentsContract.deleteDocument(
                                    contentResolver,
                                    Uri.parse(job.destination),
                                )
                            )
                            PdfExportQueue(this@PdfFlowProbeActivity).remove(job.id)
                        }
                        check(
                            contentResolver.delete(Uri.parse(input.getString("uri")), null, null) ==
                                1
                        )
                        seen.forEach { vm.repository.delete(it) }
                        marker("done")
                            .writeText(
                                state
                                    .put("status", "PASS")
                                    .put("roundTrip", true)
                                    .put("cleaned", true)
                                    .toString()
                            )
                        marker("input").delete()
                        File(filesDir, "pdf-flow-finish").delete()
                        finish()
                        break
                    }
                    delay(100)
                }
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

    private suspend fun prepare() =
        withContext(Dispatchers.IO) {
            val name = "Lightforge-flow-${newId()}.png"
            val uri =
                contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            val bitmap = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(28, 130, 110))
            try {
                contentResolver.openOutputStream(uri, "w")!!.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            marker("input")
                .writeText(JSONObject().put("name", name).put("uri", uri.toString()).toString())
        }
}
