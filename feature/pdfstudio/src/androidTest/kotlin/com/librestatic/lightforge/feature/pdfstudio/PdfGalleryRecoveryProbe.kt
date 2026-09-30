package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

internal object PdfGalleryRecoveryProbe {
    suspend fun write(context: Context) {
        val marker = File(context.filesDir, "pdf-gallery-recovery.json")
        check(!marker.exists())
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(927)
        bitmap.setPixels(
            IntArray(256 * 256) { random.nextInt() or (0xff shl 24) },
            0,
            256,
            0,
            0,
            256,
            256,
        )
        val source = File.createTempFile("gallery-process-", ".png", context.cacheDir)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val hash = PdfProjectRepository.sha256(source)
        val bytes = source.length()
        check(bytes > 65536)
        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-recovery-${newId()}.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        source.delete()
        val intake = PdfGalleryIntake(context)
        val id = newId()
        intake.stage(id, "Gallery process recovery", List(25) { uri })
        check(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        val repo =
            PdfProjectRepository(
                context,
                sourceChunk = { copied ->
                    val staging =
                        File(context.filesDir, "pdf-studio").listFiles()!!.single {
                            it.name.startsWith("import-")
                        }
                    val actual = File(staging, "source-0").length()
                    check(actual == copied && copied in 1 until bytes)
                    marker.writeText(
                        JSONObject()
                            .put("pid", Process.myPid())
                            .put("request", id)
                            .put("uri", uri.toString())
                            .put("hash", hash)
                            .put("copied", copied)
                            .put("total", bytes)
                            .put("staging", staging.name)
                            .toString()
                    )
                    awaitCancellation()
                },
            )
        intake.process(PdfProjectDatabase.get(context).galleryDeliveries().get(id)!!, repo) { _, _
            ->
        }
        error("Writer gate was bypassed")
    }

    suspend fun read(context: Context) {
        val marker = File(context.filesDir, "pdf-gallery-recovery.json")
        val cp = JSONObject(marker.readText())
        check(cp.getInt("pid") != Process.myPid())
        val id = cp.getString("request")
        val uri = Uri.parse(cp.getString("uri"))
        val db = PdfProjectDatabase.get(context)
        check(db.galleryDeliveries().get(id) != null)
        check(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        val store = ViewModelStore()
        val vm =
            withContext(Dispatchers.Main) {
                PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle())
                    .also { store.put("pdf", it) }
            }
        try {
            withTimeout(45_000) {
                while (
                    db.imports().get(id) == null ||
                        db.galleryDeliveries().get(id) != null ||
                        vm.state.value.busy ||
                        vm.state.value.project == null
                ) {
                    check(db.galleryDeliveries().get(id)?.error == null)
                    delay(30)
                }
            }
            val project = vm.state.value.project!!
            check(project.pages.map { it.images.size } == listOf(24, 1))
            check(project.assets.single().hash == cp.getString("hash"))
            check(
                PdfProjectRepository.sha256(vm.repository.file(project.assets.single().hash)) ==
                    cp.getString("hash")
            )
            check(!File(context.filesDir, "pdf-studio/" + cp.getString("staging")).exists())
            project.pages.forEach { check(it.images == PdfGeometry.grid(it, 2, 4.0).images) }
            val pdf = File(context.cacheDir, "gallery-recovered.pdf")
            try {
                IsolatedPdfEngine(context)
                    .export(project, project.assets.map { vm.repository.file(it.hash) }, pdf, false)
                check(IsolatedPdfEngine(context).inspect(pdf).size == 2)
            } finally {
                pdf.delete()
            }
            val intake = PdfGalleryIntake(context)
            intake.stage(id, "Replayed delivery", List(25) { uri })
            check(db.galleryDeliveries().get(id) == null)
            vm.repository.delete(project.id)
            check(context.contentResolver.delete(uri, null, null) == 1)
            marker.delete()
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
