package com.librestatic.lightforge

import android.content.*
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.IBinder
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.librestatic.lightforge.feature.pdfstudio.*
import java.io.File
import java.util.Random
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Full-app workload; never run over the user's debug installation. */
class PdfMixedMemoryDeviceTest {
    @Test
    fun mixedQueueKeepsEditingAndCancellationIndependent(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val directory = File(context.filesDir, "pdf-mixed-memory")
        check(!directory.exists()) { "Inspect the existing workload before starting another" }
        check(directory.mkdirs())
        val repo = PdfProjectRepository(context)
        val queue = PdfExportQueue(context)
        check(PdfProjectDatabase.get(context).projects().all().isEmpty())
        val device = UiDevice.getInstance(instrumentation)
        val sources = mutableListOf<File>()
        val jobs = mutableListOf<PdfExportJob>()
        var project = PdfProject(name = "Mixed memory fixture")
        val bound = CompletableDeferred<Unit>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    bound.complete(Unit)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        var pinned = false
        val result = JSONObject().put("host", Process.myPid())
        fun record(stage: String) {
            result.put("stage", stage)
            val temp = File(directory, "state.tmp")
            temp.writeText(result.toString())
            check(temp.renameTo(File(directory, "state.json")))
        }
        suspend fun release(name: String) =
            withTimeout(180_000) { while (!File(directory, name).exists()) delay(100) }
        fun capture(name: String) {
            device.takeScreenshot(File(directory, "$name.png"))
            device.dumpWindowHierarchy(File(directory, "$name.xml"))
        }
        fun click(text: String) {
            check(device.wait(Until.hasObject(By.text(text)), 15_000)) { "Missing $text" }
            device.findObject(By.text(text)).click()
            device.waitForIdle()
        }
        try {
            repeat(4) { index ->
                val image = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                val row = IntArray(1024)
                val random = Random(610L + index)
                val file = File(directory, "source-$index.jpg").also(sources::add)
                try {
                    repeat(1024) { y ->
                        for (x in row.indices) row[x] = random.nextInt() or (0xff shl 24)
                        image.setPixels(row, 0, 1024, 0, y, 1024, 1)
                    }
                    file.outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.JPEG, 90, it))
                    }
                } finally {
                    image.recycle()
                }
            }
            val vector = File(directory, "source-vector.pdf").also(sources::add)
            val vectorDocument = PdfDocument()
            try {
                val pdf = vectorDocument
                repeat(8) { index ->
                    val page =
                        pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    val paint =
                        Paint().apply {
                            color = android.graphics.Color.BLACK
                            textSize = 20f
                        }
                    page.canvas.drawText("Local vector page $index", 30f, 35f, paint)
                    repeat(1200) { n ->
                        paint.color = android.graphics.Color.rgb(n % 255, index * 25, (n * 7) % 255)
                        val x = 30f + n % 30 * 17
                        val y = 60f + n / 30 * 17
                        page.canvas.drawRect(x, y, x + 12, y + 12, paint)
                    }
                    pdf.finishPage(page)
                }
                vector.outputStream().use(pdf::writeTo)
            } finally {
                vectorDocument.close()
            }
            project = repo.import(project, sources.map(Uri::fromFile), 0) { _, _ -> }
            val photos = project.assets.filter { it.mime == "image/jpeg" }
            val pdf = project.assets.single { it.mime == "application/pdf" }
            project =
                project
                    .copy(
                        pages =
                            List(96) { n ->
                                if (n % 2 == 1) PdfPage(source = pdf.hash, sourcePage = (n / 2) % 8)
                                else
                                    PdfPage(
                                        images =
                                            List(24) { i ->
                                                PdfImage(
                                                    asset = photos[i % 4].hash,
                                                    x = 10.0 + i % 6 * 31,
                                                    y = 10.0 + i / 6 * 60,
                                                    width = 26.0,
                                                    height = 45.0,
                                                )
                                            }
                                    )
                            }
                    )
                    .validate()
            repo.save(project)
            result
                .put("pages", 96)
                .put("pdfPages", 48)
                .put("imagePages", 48)
                .put("placements", 1152)
                .put("sources", 5)
                .put("inputBytes", sources.sumOf { it.length() })
                .put("sourceHashes", JSONArray(project.assets.map { it.hash }))
            device.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )
            val create = context.getString(com.librestatic.lightforge.feature.photos.R.string.photos_create)
            check(device.wait(Until.hasObject(By.desc(create)), 15_000))
            device.findObject(By.desc(create)).click()
            click(context.getString(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_studio))
            click(context.getString(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_open))
            check(
                device.wait(
                    Until.hasObject(
                        By.desc(
                            context.getString(
                                com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_canvas_label
                            )
                        )
                    ),
                    20_000,
                )
            )
            capture("before")
            pinned =
                context.bindService(
                    Intent(context, PdfProcessingService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            check(pinned)
            withTimeout(30_000) { bound.await() }
            record("ready")
            release("start")
            val cancel = queue.enqueue(project, false).also(jobs::add)
            withTimeout(60_000) {
                while (true) {
                    val row = requireNotNull(queue.get(cancel.id))
                    check(row.phase !in setOf(PdfExportPhase.Ready, PdfExportPhase.Failed)) {
                        "Missed active cancellation: ${row.phase}"
                    }
                    if (row.phase == PdfExportPhase.Running && row.completed > 0) {
                        result.put("cancelProgress", row.completed)
                        break
                    }
                    delay(20)
                }
            }
            val original = queue.enqueue(project, false).also(jobs::add)
            val compact = queue.enqueue(project, true).also(jobs::add)
            result
                .put("cancelJob", cancel.id)
                .put("originalJob", original.id)
                .put("compactJob", compact.id)
            result.put("queuedAtEdit", queue.get(original.id)!!.phase.name)
            record("workload")
            val cancellation = async(Dispatchers.IO) { queue.cancel(cancel.id) }
            check(
                device.wait(
                    Until.hasObject(By.text(project.name).clazz("android.widget.EditText")),
                    15_000,
                )
            )
            device.findObject(By.text(project.name).clazz("android.widget.EditText")).text =
                "Edited during export"
            click(context.getString(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_save))
            withTimeout(15_000) {
                while (repo.load(project.id)?.name != "Edited during export") delay(50)
            }
            cancellation.await()
            assertEquals(PdfExportPhase.Cancelled, queue.get(cancel.id)!!.phase)
            assertFalse(queue.output(cancel.id).exists())
            assertFalse(queue.partial(cancel.id, cancel.workId).exists())
            result.put("edited", true).put("cancelled", true).put("partialRemoved", true)
            record("exporting")
            for (job in listOf(original, compact)) withTimeout(180_000) {
                while (true) {
                    val row = requireNotNull(queue.get(job.id))
                    check(row.phase != PdfExportPhase.Failed) { "Export failed: ${row.error}" }
                    if (row.phase == PdfExportPhase.Ready) break
                    delay(100)
                }
            }
            val engine = IsolatedPdfEngine(context)
            val outputs = JSONArray()
            for (job in listOf(original, compact)) {
                val row = queue.get(job.id)!!
                val output = queue.output(job.id)
                assertEquals(project.name, PdfCodec.decode(row.manifest).name)
                assertEquals(96, engine.inspect(output).size)
                assertEquals(row.outputHash, PdfProjectRepository.sha256(output))
                assertEquals(row.outputBytes, output.length())
                // Raster previews inspect both source-PDF and photo pages of each real output.
                for (page in listOf(0, 1, 94, 95)) {
                    val preview = File(directory, "${job.compact}-$page.png")
                    engine.preview(output, page, preview)
                    assertTrue(preview.length() > 0)
                }
                outputs.put(
                    JSONObject()
                        .put("compact", job.compact)
                        .put("bytes", output.length())
                        .put("sha256", row.outputHash)
                )
            }
            project.assets.forEach {
                assertEquals(it.hash, PdfProjectRepository.sha256(repo.file(it.hash)))
            }
            assertEquals("Edited during export", repo.load(project.id)!!.name)
            capture("after")
            result
                .put("outputs", outputs)
                .put("snapshotPreserved", true)
                .put("sourceHashesPreserved", true)
            record("result")
            release("measured")
        } catch (failure: Throwable) {
            result.put("failure", failure.toString())
            record("failed")
            capture("failure")
            throw failure
        } finally {
            withContext(NonCancellable) {
                jobs.forEach {
                    queue.cancel(it.id)
                    queue.remove(it.id)
                }
                repo.delete(project.id)
                sources.forEach { it.delete() }
                if (pinned) context.unbindService(connection)
            }
        }
        assertTrue(jobs.all { queue.get(it.id) == null })
        assertNull(repo.load(project.id))
        result.put("cleanup", true).put("status", "PASS")
        record("done")
    }
}
