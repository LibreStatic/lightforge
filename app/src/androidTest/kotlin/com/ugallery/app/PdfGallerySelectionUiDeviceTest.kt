package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ugallery.feature.pdfstudio.IsolatedPdfEngine
import com.ugallery.feature.pdfstudio.PdfCodec
import com.ugallery.feature.pdfstudio.PdfProjectDatabase
import com.ugallery.feature.pdfstudio.PdfProjectRepository
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only with -Pugallery.pdfAcceptance=true; never install/run this flow over user app data. */
@RunWith(AndroidJUnit4::class)
class PdfGallerySelectionUiDeviceTest {
    @Test
    fun realPermissionSelectionIntakeAndReturnKeepOneProject(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val repo = PdfProjectRepository(context)
        val database = PdfProjectDatabase.get(context)
        check(database.projects().all().isEmpty()) { "Use an empty dedicated acceptance install" }
        val sources = mutableListOf<Uri>()
        val projectIds = mutableSetOf<String>()
        val label = "UGallery-PDF-" + UUID.randomUUID()
        val evidence = File(context.filesDir, "pdf-gallery-screen").apply { mkdirs() }
        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun clickText(text: String) {
            assertTrue(
                "Missing control: $text",
                device.wait(Until.hasObject(By.text(text)), 15_000),
            )
            device.findObject(By.text(text)).click()
            device.waitForIdle()
        }
        try {
            repeat(3) { index ->
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
                bitmap.eraseColor(android.graphics.Color.rgb(30 + index * 50, 120, 180))
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
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            clickText(grant)
            val allow =
                By.res(
                    Pattern.compile(".*permissioncontroller:id/permission_allow(?:_all)?_button")
                )
            assertTrue(
                "Android permission dialog missing",
                device.wait(Until.hasObject(allow), 10_000),
            )
            capture("permission")
            device.findObject(allow).click()
            val mediaPermissions =
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    listOf(
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                    )
                else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            withTimeout(10_000) {
                while (
                    mediaPermissions.any {
                        context.checkSelfPermission(it) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                ) delay(50)
            }
            val decline =
                context.getString(
                    com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline
                )
            assertTrue(device.wait(Until.hasObject(By.text(decline)), 15_000))
            capture("local-analysis-notice")
            clickText(decline)
            val timelineVisible = device.wait(Until.hasObject(By.res("timeline_grid")), 30_000)
            capture("after-permission")
            assertTrue("Timeline did not appear after explicit onboarding choice", timelineVisible)
            val tags = sources.map { "media_external_primary_${ContentUris.parseId(it)}" }
            for (tag in tags) assertTrue(
                "Fixture image not indexed: $tag",
                device.wait(Until.hasObject(By.res(tag)), 30_000),
            )
            val anchor = device.findObject(By.res(tags[0])).visibleCenter
            // Hold beyond the platform long-press timeout instead of UiObject2's fixed duration.
            val holdMillis = android.view.ViewConfiguration.getLongPressTimeout() + 800
            device.executeShellCommand(
                "input swipe ${anchor.x} ${anchor.y} ${anchor.x} ${anchor.y} $holdMillis"
            )
            val selectedOne = context.getString(R.string.selection_count, 1)
            assertTrue(device.wait(Until.hasObject(By.text(selectedOne)), 10_000))
            tags.drop(1).forEach { device.findObject(By.res(it)).click() }
            val selectedThree = context.getString(R.string.selection_count, 3)
            assertTrue(device.wait(Until.hasObject(By.text(selectedThree)), 10_000))
            capture("selection")
            val more = context.getString(com.ugallery.feature.viewer.R.string.viewer_more)
            val createPdf = context.getString(com.ugallery.feature.pdfstudio.R.string.pdf_selection_create_pdf)
            // "Create PDF" is a promoted icon-only action in the selection bar (Phase E), no
            // longer inside the overflow menu; its accessible label is "Create PDF", distinct
            // from the studio's own title ("PDF Studio") used elsewhere in this flow.
            device.findObject(By.desc(createPdf)).click()
            val project =
                withTimeout(40_000) {
                    while (true) {
                        val rows = database.projects().all()
                        projectIds.addAll(rows.map { it.id })
                        if (rows.size == 1) {
                            val p = PdfCodec.decode(rows.single().manifest)
                            if (p.pages.sumOf { it.images.size } == 3) return@withTimeout p
                        }
                        delay(100)
                    }
                    error("unreachable")
                }
            assertEquals(3, project.assets.size)
            assertEquals(1, project.pages.size)
            withTimeout(15_000) {
                while (database.galleryDeliveries().all().isNotEmpty()) delay(50)
            }
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri in sources })
            for (asset in project.assets) assertEquals(
                asset.hash,
                PdfProjectRepository.sha256(repo.file(asset.hash)),
            )
            val sourceHashes =
                sources
                    .map { uri ->
                        val copy = File.createTempFile("gallery-source-", ".png", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)!!.use { input ->
                                copy.outputStream().use { input.copyTo(it) }
                            }
                            PdfProjectRepository.sha256(copy)
                        } finally {
                            copy.delete()
                        }
                    }
                    .toSet()
            assertEquals(sourceHashes, project.assets.map { it.hash }.toSet())
            capture("studio")
            val previousActivity = java.util.concurrent.atomic.AtomicReference<MainActivity>()
            instrumentation.runOnMainSync {
                val activity =
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .single() as MainActivity
                previousActivity.set(activity)
                activity.recreate()
            }
            withTimeout(15_000) {
                while (true) {
                    var recreated = false
                    instrumentation.runOnMainSync {
                        recreated =
                            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                                .any { it is MainActivity && it !== previousActivity.get() }
                    }
                    if (recreated) break
                    delay(50)
                }
            }
            val canvas = context.getString(com.ugallery.feature.pdfstudio.R.string.pdf_canvas_label)
            assertTrue(device.wait(Until.hasObject(By.desc(canvas)), 15_000))
            assertEquals(1, database.projects().all().size)
            assertEquals(project.pages, repo.load(project.id)!!.pages)
            capture("recreated-studio")
            val pdf = repo.prepareExport(project, false)
            try {
                assertEquals(1, IsolatedPdfEngine(context).inspect(pdf).size)
            } finally {
                pdf.delete()
            }
            device.pressBack()
            val close = context.getString(com.ugallery.feature.pdfstudio.R.string.pdf_close)
            assertTrue(device.wait(Until.hasObject(By.desc(close)), 10_000))
            device.findObject(By.desc(close)).click()
            assertTrue(device.wait(Until.hasObject(By.text(selectedThree)), 10_000))
            for (tag in tags) assertTrue(device.findObject(By.res(tag)).isChecked)
            capture("returned-selection")
            // Creating from the retained selection is explicit; reopening via Create must not
            // replay it.
            val create = context.getString(com.ugallery.feature.photos.R.string.photos_create)
            val clear = context.getString(R.string.selection_clear)
            device.findObject(By.desc(more)).click()
            clickText(clear)
            assertFalse(device.hasObject(By.text(selectedThree)))
            assertEquals(1, database.projects().all().size)
            device.findObject(By.desc(create)).click()
            clickText(context.getString(com.ugallery.feature.pdfstudio.R.string.pdf_studio))
            clickText(context.getString(com.ugallery.feature.pdfstudio.R.string.pdf_open))
            assertTrue(device.wait(Until.hasObject(By.desc(canvas)), 15_000))
            assertEquals(1, database.projects().all().size)
            assertEquals(project.pages, repo.load(project.id)!!.pages)
            capture("reopened-project")
            File(evidence, "result.json")
                .writeText(
                    """{"status":"PASS","permissionUi":true,"selected":3,"projects":1,"sourceHashes":true,"pdfPages":1,"selectionRestored":true,"clearedByUser":true,"recreated":true,"reopenedWithoutDuplicate":true}"""
                )
        } catch (failure: Throwable) {
            capture("failure")
            throw failure
        } finally {
            projectIds.addAll(database.projects().all().map { it.id })
            projectIds.forEach { repo.delete(it) }
            sources.forEach { assertEquals(1, context.contentResolver.delete(it, null, null)) }
        }
    }
}
