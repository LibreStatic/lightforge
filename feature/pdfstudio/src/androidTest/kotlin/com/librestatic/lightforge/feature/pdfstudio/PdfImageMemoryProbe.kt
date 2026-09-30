package com.librestatic.lightforge.feature.pdfstudio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Process
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Test-only lifetime pin lets the host read this renderer's kernel RSS high-water mark. */
internal class PdfImageMemoryProbe(private val context: Context) {
    private val directory = File(context.filesDir, "pdf-image-memory")

    private fun record(value: JSONObject) {
        val temporary = File(directory, "state.tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(File(directory, "state.json")))
    }

    private suspend fun release(name: String) =
        withTimeout(180_000) { while (!File(directory, name).exists()) delay(50) }

    suspend fun run(baseline: Boolean) {
        check(!directory.exists()) { "Inspect the existing probe before starting another" }
        check(directory.mkdirs())
        val sources = mutableListOf<File>()
        val output = File(directory, "output.pdf")
        val bound = CompletableDeferred<Unit>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    bound.complete(Unit)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        var pinned = false
        try {
            repeat(4) { index ->
                val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                val row = IntArray(1024)
                val random = Random(100L + index)
                val file = File(directory, "source-$index.jpg")
                sources.add(file)
                try {
                    repeat(1024) { y ->
                        for (x in row.indices) row[x] = random.nextInt() or (0xff shl 24)
                        bitmap.setPixels(row, 0, 1024, 0, y, 1024, 1)
                    }
                    file.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            val assets =
                sources.map { PdfAsset(PdfProjectRepository.sha256(it), "image/jpeg", 1024, 1024) }
            val project =
                PdfProject(
                        name = "Repeated original photos",
                        assets = assets,
                        pages =
                            List(12) {
                                PdfPage(
                                    images =
                                        List(24) { index ->
                                            PdfImage(
                                                asset = assets[index % 4].hash,
                                                x = 10.0 + index % 6 * 31,
                                                y = 10.0 + index / 6 * 50,
                                                width = 25.0,
                                                height = 40.0,
                                            )
                                        }
                                )
                            },
                    )
                    .validate()
            pinned =
                context.bindService(
                    Intent(context, PdfProcessingService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            check(pinned)
            withTimeout(30_000) { bound.await() }
            val result =
                JSONObject()
                    .put("stage", "ready")
                    .put("host", Process.myPid())
                    .put("inputBytes", sources.sumOf { it.length() })
                    .put("sources", 4)
                    .put("pages", 12)
                    .put("placements", 288)
                    .put("baseline", baseline)
            record(result)
            release("start")
            record(JSONObject(result.toString()).put("stage", "export"))
            val start = android.os.SystemClock.elapsedRealtime()
            val engine = IsolatedPdfEngine(context)
            var failure: Throwable? = null
            try {
                engine.export(project, sources, output, false)
            } catch (error: Exception) {
                failure = error
            }
            result
                .put("exportMillis", android.os.SystemClock.elapsedRealtime() - start)
                .put("error", failure?.let { PdfFailure.from(it).name } ?: JSONObject.NULL)
                .put("outputBytes", output.length())
            if (failure == null) {
                check(engine.inspect(output).size == 12)
                PDFBoxResourceLoader.init(context)
                PDDocument.load(output).use { document ->
                    val hashes = mutableSetOf<String>()
                    document.pages.forEach { page ->
                        if (!baseline) check(page.resources.xObjectNames.count() == 4)
                        page.resources.xObjectNames.forEach { name ->
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            page.resources.getXObject(name).cosObject.createRawInputStream().use {
                                input ->
                                val bytes = ByteArray(8192)
                                while (true) {
                                    val n = input.read(bytes)
                                    if (n < 0) break
                                    digest.update(bytes, 0, n)
                                }
                            }
                            hashes.add(digest.digest().joinToString("") { "%02x".format(it) })
                        }
                    }
                    check(hashes == assets.map { it.hash }.toSet())
                    result.put("sourceBytesPreserved", true)
                }
                if (!baseline) check(output.length() < sources.sumOf { it.length() } + 256 * 1024)
                engine.preview(output, 0, File(directory, "preview.png"))
            } else check(!output.exists()) { "Failed export left partial output" }
            result.put("stage", "result")
            record(result)
            // Preserve the same renderer until the host has captured post-export VmHWM/meminfo.
            release("measured")
            if (!baseline) check(failure == null) { "Export failed: ${result.optString("error")}" }
            result.put("stage", "done").put("status", "PASS")
            record(result)
        } finally {
            if (pinned) context.unbindService(connection)
            sources.forEach { it.delete() }
            output.delete()
        }
    }
}
