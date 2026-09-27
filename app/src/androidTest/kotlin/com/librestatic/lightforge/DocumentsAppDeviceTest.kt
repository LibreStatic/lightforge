package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.feature.pdfstudio.*
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual native Photos -> Documents -> Studio, only in the opt-in acceptance application. */
class DocumentsAppDeviceTest {
    @Test
    fun selectedPhotosBecomeDocumentsAndCreateOneRealPdf(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val projects = PdfProjectDatabase.get(context)
        check(projects.projects().all().isEmpty()) { "Dedicated acceptance projects must be empty" }
        val repo = PdfProjectRepository(context)
        val gallery = GalleryDatabaseFactory.open(context)
        val label = "Lightforge-Documents-${UUID.randomUUID()}"
        val evidence =
            File(context.filesDir, "documents-screen-${UUID.randomUUID()}").apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        val ownedProjects = mutableSetOf<String>()
        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun click(text: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            var clicked = false
            while (!clicked && android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(By.text(text))?.let { node ->
                        if (!node.visibleBounds.isEmpty) { node.click(); clicked = true }
                    }
                } catch (_: StaleObjectException) {
                    // Android 15 may invalidate the label node during the document transition.
                    // Refetch before input; never reuse the stale UiObject2 instance.
                }
                if (!clicked) { device.waitForIdle(); android.os.SystemClock.sleep(50) }
            }
            assertTrue("Missing stable control: $text", clicked)
            device.waitForIdle()
        }
        fun scrollTo(text: String) {
            repeat(12) {
                val node = device.findObject(By.text(text))
                if (node != null && !node.visibleBounds.isEmpty) {
                    node.click()
                    device.waitForIdle()
                    return
                }
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.7f)
                device.waitForIdle()
            }
            error("Missing scrollable action $text")
        }
        try {
            repeat(2) { index ->
                val uri =
                    context.contentResolver.insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "$label-$index.png")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$label/")
                            put(
                                MediaStore.Images.ImageColumns.DATE_TAKEN,
                                System.currentTimeMillis() + index,
                            )
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                sources.add(uri)
                val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(30 + index * 70, 100, 170))
                try {
                    context.contentResolver.openOutputStream(uri, "w")!!.use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            device.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )
            val grant = context.getString(com.librestatic.lightforge.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                click(grant)
                val allow =
                    By.res(
                        Pattern.compile(
                            ".*permissioncontroller:id/permission_allow(?:_all)?_button"
                        )
                    )
                assertTrue(device.wait(Until.hasObject(allow), 10_000))
                device.findObject(allow).click()
            }
            val decline =
                context.getString(
                    com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline
                )
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) click(decline)
            val photos = context.getString(R.string.nav_photos)
            if (!device.hasObject(By.res("timeline_grid")) && device.hasObject(By.text(photos)))
                click(photos)
            val tags = sources.map { "media_external_primary_${ContentUris.parseId(it)}" }
            for (tag in tags) assertTrue(
                "Fixture not indexed $tag",
                device.wait(Until.hasObject(By.res(tag)), 30_000),
            )
            val center = device.findObject(By.res(tags[0])).visibleCenter
            device.executeShellCommand(
                "input swipe ${center.x} ${center.y} ${center.x} ${center.y} ${android.view.ViewConfiguration.getLongPressTimeout() + 800}"
            )
            assertTrue(
                device.wait(
                    Until.hasObject(By.text(context.getString(R.string.selection_count, 1))),
                    10_000,
                )
            )
            device.findObject(By.res(tags[1])).click()
            assertTrue(
                device.wait(
                    Until.hasObject(By.text(context.getString(R.string.selection_count, 2))),
                    10_000,
                )
            )
            device
                .findObject(
                    By.desc(context.getString(com.librestatic.lightforge.feature.viewer.R.string.viewer_more))
                )
                .click()
            click(context.getString(com.librestatic.lightforge.feature.collections.R.string.documents_organize))
            withTimeout(15_000) {
                while (
                    sources.any {
                        gallery
                            .documentDao()
                            .get("external_primary", ContentUris.parseId(it))
                            ?.category != "Other"
                    }
                ) delay(50)
            }
            assertTrue(device.wait(Until.hasObject(By.res("documents-screen")), 15_000))
            capture("documents-library")
            scrollTo("$label-0.png")
            click(context.getString(com.librestatic.lightforge.feature.collections.R.string.documents_receipts))
            withTimeout(10_000) {
                while (
                    gallery
                        .documentDao()
                        .get("external_primary", ContentUris.parseId(sources[0]))
                        ?.category != "Receipt"
                ) delay(50)
            }
            capture("classified-receipt")
            scrollTo(
                context.getString(
                    com.librestatic.lightforge.feature.collections.R.string.documents_prepare_pdf,
                    1,
                )
            )
            val project =
                withTimeout(40_000) {
                    while (true) {
                        val rows = projects.projects().all()
                        ownedProjects.addAll(rows.map { it.id })
                        if (rows.size == 1) {
                            val p = PdfCodec.decode(rows.single().manifest)
                            if (p.assets.size == 1 && p.pages.sumOf { it.images.size } == 1)
                                return@withTimeout p
                        }
                        delay(50)
                    }
                    error("unreachable")
                }
            val sourceCopy = File.createTempFile("documents-source-", ".png", context.cacheDir)
            try {
                context.contentResolver.openInputStream(sources[0])!!.use { input ->
                    sourceCopy.outputStream().use { input.copyTo(it) }
                }
                assertEquals(PdfProjectRepository.sha256(sourceCopy), project.assets.single().hash)
            } finally {
                sourceCopy.delete()
            }
            val output = repo.prepareExport(project, false)
            try {
                assertEquals(1, IsolatedPdfEngine(context).inspect(output).size)
            } finally {
                output.delete()
            }
            capture("pdf-studio")
            device.pressBack()
            val close =
                By.desc(context.getString(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_close))
            assertTrue(device.wait(Until.hasObject(close), 10_000))
            device.findObject(close).click()
            assertTrue(device.wait(Until.hasObject(By.res("documents-screen")), 10_000))
            assertEquals(1, projects.projects().all().size)
            assertEquals(
                "Receipt",
                gallery
                    .documentDao()
                    .get("external_primary", ContentUris.parseId(sources[0]))!!
                    .category,
            )
            capture("returned-documents")
            File(evidence, "result.json")
                .writeText(
                    """{"status":"PASS","selected":2,"manualDocuments":2,"receipt":1,"pdfProjects":1,"pdfPages":1,"sourceHash":true,"returnedToDocuments":true}"""
                )
        } catch (e: Throwable) {
            capture("failure")
            throw e
        } finally {
            ownedProjects.addAll(projects.projects().all().map { it.id })
            ownedProjects.forEach { repo.delete(it) }
            for (uri in sources) {
                context.contentResolver.delete(uri, null, null)
                gallery.libraryDao().deleteMedia("external_primary", ContentUris.parseId(uri))
            }
            gallery.close()
        }
    }
}
