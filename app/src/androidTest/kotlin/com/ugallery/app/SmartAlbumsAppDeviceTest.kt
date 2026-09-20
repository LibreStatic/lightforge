package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.data.GallerySmartAlbumRepository
import com.ugallery.core.database.GalleryDatabaseFactory
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual Collections/rail -> smart editor -> MediaStore-backed live results and durable exclusions.
 */
class SmartAlbumsAppDeviceTest {
    @Test
    fun smartAlbumIsDiscoverableAndTracksLocalPhotosWithoutCopyingThem(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val repo = GallerySmartAlbumRepository(db)
        val label = "UGallery-Smart-${UUID.randomUUID()}"
        val topic = "smart-fixture-${UUID.randomUUID()}"
        val year = java.time.LocalDate.now().year
        val sources = mutableListOf<Uri>()
        var owned: String? = null
        val evidence =
            File(context.filesDir, "smart-albums-screen-${UUID.randomUUID()}").apply { mkdirs() }
        fun hash(uri: Uri) =
            context.contentResolver.openInputStream(uri)!!.use {
                java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
        fun addPhoto(index: Int): Uri {
            val uri =
                context.contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$label-$index.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$label/")
                        put(
                            MediaStore.Images.ImageColumns.DATE_TAKEN,
                            System.currentTimeMillis() + index,
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            sources.add(uri)
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(30 + index * 40, 120, 180))
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
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
            return uri
        }
        suspend fun labelCurrent(uri: Uri) {
            val id = ContentUris.parseId(uri)
            val row =
                withTimeout(45000) {
                    var found: com.ugallery.core.database.MediaItemEntity? = null
                    while (found == null) {
                        found = db.libraryDao().media("external_primary", id)
                        if (found == null) delay(100)
                    }
                    requireNotNull(found)
                }
            assertEquals(
                year,
                java.time.Instant.ofEpochMilli(row.timelineSortMillis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .year,
            )
            db.libraryDao()
                .replaceLabelResult(
                    com.ugallery.core.database.MediaLabelRunEntity(
                        "external_primary",
                        id,
                        row.generationModified,
                        "smart-fixture",
                        System.currentTimeMillis(),
                    ),
                    listOf(
                        com.ugallery.core.database.MediaLabelEntity(
                            "external_primary",
                            id,
                            topic,
                            topic,
                            .99f,
                            "smart-fixture",
                        )
                    ),
                )
        }
        fun clickText(text: String) {
            assertTrue("Missing text $text", device.wait(Until.hasObject(By.text(text)), 15000))
            val nodes = device.findObjects(By.text(text))
            nodes.first { !it.visibleBounds.isEmpty }.click()
            device.waitForIdle()
        }
        fun tag(tag: String): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(By.res(tag))?.let { if (!it.visibleBounds.isEmpty) return it }
                    val container =
                        device.findObject(By.res("smart-grid"))
                            ?: device.findObject(By.res("smart-list"))
                            ?: device.findObjects(By.scrollable(true)).maxByOrNull {
                                it.visibleBounds.width()
                            }
                    container?.scroll(Direction.DOWN, .65f)
                } catch (_: StaleObjectException) {
                    // Reacquire the container after detail/grid recomposition, within the same
                    // deadline.
                }
                device.waitForIdle()
                android.os.SystemClock.sleep(50)
            }
            error("Missing smart control $tag")
        }
        fun click(tag: String) {
            tag(tag).click()
            device.waitForIdle()
        }
        fun count(n: Int) {
            tag("smart-count")
            assertTrue(
                "Expected $n live photos",
                device.wait(
                    Until.hasObject(
                        By.text(
                            context.getString(
                                com.ugallery.feature.collections.R.string.smart_matches,
                                n,
                            )
                        )
                    ),
                    45000,
                ),
            )
        }
        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        try {
            addPhoto(0)
            addPhoto(1)
            device.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                clickText(grant)
                val allow =
                    By.res(
                        Pattern.compile(
                            ".*permissioncontroller:id/permission_allow(?:_all)?_button"
                        )
                    )
                assertTrue(device.wait(Until.hasObject(allow), 10000))
                device.findObject(allow).click()
                device.waitForIdle()
            }
            val decline =
                context.getString(
                    com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline
                )
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) clickText(decline)
            clickText(context.getString(R.string.nav_collections))
            tag("collections-smart-albums")
            capture("collections-entry")
            click("collections-smart-albums")
            assertTrue(device.wait(Until.hasObject(By.res("smart-albums-screen")), 15000))
            sources.forEach { labelCurrent(it) }
            click("smart-create")
            tag("smart-name").text = label
            tag("smart-year").text = year.toString()
            click("smart-topic")
            click("smart-topic-$topic")
            if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
                device.pressBack()
            capture("editor")
            withTimeout(45000) {
                while (
                    sources.any {
                        db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null
                    }
                ) delay(100)
            }
            click("smart-preview")
            count(2)
            click("smart-back")
            click("smart-save")
            count(2)
            owned =
                withTimeout(15000) {
                    var found: String? = null
                    while (found == null) {
                        db.openHelper.readableDatabase
                            .query("SELECT albumId FROM smart_albums WHERE name=?", arrayOf(label))
                            .use { if (it.moveToFirst()) found = it.getString(0) }
                        if (found == null) delay(50)
                    }
                    found
                }
            labelCurrent(addPhoto(2))
            count(3)
            val originalHashes = sources.map(::hash)
            val first = ContentUris.parseId(sources.first())
            tag("smart-photo-external_primary:$first")
            assertTrue(
                device.wait(
                    Until.hasObject(By.res("smart-image-loaded-external_primary:$first")),
                    15000,
                )
            )
            capture("live-results")
            click("smart-exclude-external_primary:$first")
            count(2)
            click("smart-undo")
            count(3)
            click("smart-exclude-external_primary:$first")
            count(2)
            click("smart-back")
            click("smart-back")
            val expanded = context.resources.configuration.screenWidthDp >= 840
            if (expanded)
                assertTrue(
                    "Expanded sidebar must expose smart albums",
                    device.wait(Until.hasObject(By.res("rail-smart-albums")), 15000),
                )
            else
                assertTrue(
                    device.wait(
                        Until.hasObject(By.text(context.getString(R.string.nav_collections))),
                        15000,
                    )
                )
            val rail = device.findObject(By.res("rail-smart-albums"))
            val railUsed = rail != null
            if (expanded) assertTrue(railUsed)
            capture("navigation-entry")
            if (rail != null) {
                rail.click()
                device.waitForIdle()
            } else {
                clickText(context.getString(R.string.nav_collections))
                click("collections-smart-albums")
            }
            click("smart-album-$owned")
            count(2)
            capture("reopened")
            val row = db.smartAlbumDao().get(owned!!)!!
            assertEquals(year, row.year)
            assertEquals(topic, row.topic)
            click("smart-delete")
            click("smart-confirm")
            assertTrue(device.wait(Until.hasObject(By.res("smart-create")), 15000))
            assertNull(db.smartAlbumDao().get(owned!!))
            owned = null
            assertEquals(originalHashes, sources.map(::hash))
            File(evidence, "result.json")
                .writeText(
                    "{\"status\":\"PASS\",\"collections\":true,\"rail\":$railUsed,\"liveArrival\":true,\"undo\":true,\"reopen\":true,\"sourceHashes\":true}"
                )
        } catch (failure: Throwable) {
            capture("failure")
            File(evidence, "failure.txt")
                .writeText(
                    failure.toString() +
                        "\n" +
                        sources.joinToString("\n") { uri ->
                            runBlocking {
                                    db.libraryDao()
                                        .media("external_primary", ContentUris.parseId(uri))
                                }
                                .toString()
                        }
                )
            throw failure
        } finally {
            owned?.let { id -> db.smartAlbumDao().get(id)?.let { repo.delete(id, it.revision) } }
            for (uri in sources) {
                context.contentResolver.delete(uri, null, null)
                db.libraryDao().deleteLabels("external_primary", ContentUris.parseId(uri))
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM media_label_runs WHERE volumeName=? AND mediaStoreId=?",
                    arrayOf<Any>("external_primary", ContentUris.parseId(uri)),
                )
                db.libraryDao().deleteMedia("external_primary", ContentUris.parseId(uri))
            }
            db.close()
        }
    }
}
