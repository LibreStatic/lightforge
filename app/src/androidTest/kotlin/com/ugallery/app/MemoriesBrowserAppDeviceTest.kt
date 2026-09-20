package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.data.MomentRepository
import com.ugallery.core.database.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Real application navigation and content-observer discovery, owning only its exact fixture rows.
 */
class MemoriesBrowserAppDeviceTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val prefix = "memories-browser-${UUID.randomUUID()}"
        val sources = mutableListOf<Uri>()
        val manualIds = mutableListOf<String>()
        val evidence = File(context.filesDir, prefix).apply { mkdirs() }

        init {
            check(context.packageName == "com.ugallery.app.pdfacceptance")
        }

        fun find(selector: BySelector, direction: Direction = Direction.DOWN): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 25000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    // Let route composition and its initial scroll finish before searching by
                    // gesture.
                    device.wait(Until.findObject(selector), 750)?.let {
                        if (!it.visibleBounds.isEmpty) return it
                    }
                    device
                        .findObjects(By.scrollable(true))
                        .maxByOrNull { it.visibleBounds.width() }
                        ?.scroll(direction, .65f)
                } catch (_: StaleObjectException) {
                    // Reacquire both target and container after recomposition; keep the original
                    // deadline.
                }
                device.waitForIdle()
            }
            capture("failure-${System.currentTimeMillis()}")
            error("Missing $selector")
        }

        fun click(tag: String) {
            find(By.res(tag)).click()
            device.waitForIdle()
        }

        fun await(tag: String) {
            assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 15000))
        }

        fun hash(uri: Uri): String =
            context.contentResolver.openInputStream(uri)!!.use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b ->
                    "%02x".format(b.toInt() and 255)
                }
            }

        fun launch() {
            device.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1000)) {
                find(By.text(grant)).click()
                val allow =
                    By.res(
                        Pattern.compile(
                            ".*permissioncontroller:id/permission_allow(?:_all)?_button"
                        )
                    )
                assertTrue(device.wait(Until.hasObject(allow), 10000))
                device.findObject(allow).click()
            }
            val decline =
                context.getString(
                    com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline
                )
            if (device.wait(Until.hasObject(By.text(decline)), 1000)) find(By.text(decline)).click()
        }

        fun create(time: Long): Uri {
            val uri =
                context.contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$prefix-${sources.size}.jpg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$prefix/")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.ImageColumns.DATE_TAKEN, time)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            sources += uri
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(50 + sources.size % 100, 90, 150))
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                }
            } finally {
                bitmap.recycle()
            }
            context.contentResolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochMilli(time)),
                    )
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                    saveAttributes()
                }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        }

        suspend fun indexed(uris: List<Uri>) =
            withTimeout(45000) {
                while (
                    uris.any {
                        db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null
                    }
                ) delay(100)
            }

        fun generatedFor(uris: List<Uri>): Set<String> {
            if (uris.isEmpty()) return emptySet()
            val marks = uris.joinToString(",") { "?" }
            return db.openHelper.readableDatabase
                .query(
                    "SELECT DISTINCT mo.momentId FROM moments mo JOIN moment_members mm ON mm.momentId=mo.momentId WHERE mo.origin='AUTO' AND mm.volumeName='external_primary' AND mm.mediaStoreId IN ($marks)",
                    uris.map { ContentUris.parseId(it) }.toTypedArray(),
                )
                .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }

        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }

        override fun close() = runBlocking {
            try {
                for (id in manualIds + generatedFor(sources)) db.momentDao().delete(id)
                for (uri in sources) {
                    context.contentResolver.delete(uri, null, null)
                    db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM media_items WHERE volumeName=? AND mediaStoreId=?",
                        arrayOf<Any>("external_primary", ContentUris.parseId(uri)),
                    )
                }
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun collectionsOpensEverySavedStoryAndReturnsToSameBrowserAfterRename() = runBlocking {
        Fixture().use { f ->
            val uri = f.create(Instant.parse("2001-01-01T12:00:00Z").toEpochMilli())
            f.launch()
            f.indexed(listOf(uri))
            val source = f.db.libraryDao().media("external_primary", ContentUris.parseId(uri))!!
            val originalHash = f.hash(uri)
            repeat(60) { n ->
                val id = "${f.prefix}-${n.toString().padStart(2,'0')}"
                f.manualIds += id
                f.db
                    .momentDao()
                    .upsertMoment(
                        MomentEntity(
                            id,
                            "USER",
                            if (n % 2 == 0) "SAVED" else "SUGGESTED",
                            "fixture",
                            source.timelineSortMillis,
                            source.timelineSortMillis,
                            "Owned story $n",
                            "USER",
                            true,
                            1,
                            1,
                        )
                    )
                f.db
                    .momentDao()
                    .insertMembers(
                        listOf(
                            MomentMemberEntity(
                                id,
                                0,
                                source.volumeName,
                                source.mediaStoreId,
                                source.generationModified,
                                "USER",
                                1f,
                            )
                        )
                    )
            }
            f.find(By.text(f.context.getString(R.string.nav_collections))).click()
            f.device.waitForIdle()
            f.click("collections-all-memories")
            f.await("memories-browser-screen")
            f.click("memories-filter-saved")
            val oldest = f.manualIds[58]
            f.click("memories-open-$oldest")
            f.await("moment-screen")
            f.click("moment-edit")
            f.find(By.res("moment-title"), Direction.UP).text = "Renamed owned old story"
            // Accessibility SET_TEXT does not open an IME. Back here would leave the story.
            f.click("moment-title-save")
            withTimeout(10000) {
                while (f.db.momentDao().moment(oldest)?.title != "Renamed owned old story") delay(
                    50
                )
            }
            f.find(
                    By.desc(
                        f.context.getString(com.ugallery.feature.collections.R.string.memory_back)
                    )
                )
                .click()
            f.await("memories-browser-screen")
            // Material FilterChip exports checkable/checked in the Android accessibility tree.
            assertTrue(f.find(By.res("memories-filter-saved")).isChecked)
            f.click("memories-open-$oldest")
            f.await("moment-screen")
            f.click("moment-memory-controls")
            f.await("memory-controls-screen")
            f.click("memory-controls-back")
            f.await("moment-screen")
            f.find(
                    By.desc(
                        f.context.getString(com.ugallery.feature.collections.R.string.memory_back)
                    )
                )
                .click()
            f.await("memories-browser-screen")
            f.capture("saved-old-story-roundtrip")
            assertEquals(originalHash, f.hash(uri))
            File(f.evidence, "result.txt")
                .writeText(
                    "60 stories; opened saved index58; rename durable; story-controls-story-browser roundtrip; original SHA256=$originalHash"
                )
        }
    }

    @Test
    fun newAndOlderRealImportsAreDiscoveredAfterCompletedRunWithoutManualRegeneration() =
        runBlocking {
            Fixture().use { f ->
                val time = Instant.parse("2003-06-01T12:00:00Z").toEpochMilli()
                val initial = (0..7).map { f.create(time + it * 1000L) }
                f.launch()
                f.indexed(initial)
                withTimeout(45000) {
                    while (
                        f.generatedFor(initial).size != 1 ||
                            f.db.momentDao().run(MomentRepository.AlgorithmVersion)?.status !=
                                "COMPLETE"
                    ) delay(100)
                }
                val originalId = f.generatedFor(initial).single()
                val repo = MomentRepository(f.db)
                repo.save(originalId)
                repo.rename(originalId, "Owned saved initial event")
                val before = f.db.momentDao().allMembers(originalId)
                val originalHashes = initial.associateWith(f::hash)
                val earlier = (0..7).map { f.create(time - 20 * 86400000L + it * 1000L) }
                val later = (0..7).map { f.create(time + 20 * 86400000L + it * 1000L) }
                f.indexed(earlier + later)
                withTimeout(60000) {
                    while (
                        f.generatedFor(earlier).size != 1 ||
                            f.generatedFor(later).size != 1 ||
                            f.db
                                .momentDao()
                                .run(MomentRepository.AlgorithmVersion)
                                ?.inputRevision != f.db.momentDiscoveryDao().discoveryRevision()
                    ) delay(100)
                }
                val ids = f.generatedFor(initial + earlier + later)
                assertEquals(3, ids.size)
                assertEquals(before, f.db.momentDao().allMembers(originalId))
                assertEquals(
                    "Owned saved initial event",
                    f.db.momentDao().moment(originalId)?.title,
                )
                assertEquals(originalHashes, initial.associateWith(f::hash))
                f.find(By.text(f.context.getString(R.string.nav_collections))).click()
                f.device.waitForIdle()
                f.click("collections-all-memories")
                f.await("memories-browser-screen")
                f.click("memories-filter-suggested")
                f.click("memories-open-${f.generatedFor(earlier).single()}")
                f.await("moment-screen")
                f.capture("older-import-discovered")
                val dismissedId = f.generatedFor(earlier).single()
                f.click("moment-delete")
                f.click("moment-delete-confirm")
                f.await("memories-browser-screen")
                withTimeout(10000) {
                    while (f.db.momentDao().moment(dismissedId)?.state != "DISMISSED") delay(50)
                }
                val subsequent = (0..7).map { f.create(time + 40 * 86400000L + it * 1000L) }
                f.indexed(subsequent)
                withTimeout(60000) {
                    while (
                        f.generatedFor(subsequent).size != 1 ||
                            f.db
                                .momentDao()
                                .run(MomentRepository.AlgorithmVersion)
                                ?.inputRevision != f.db.momentDiscoveryDao().discoveryRevision()
                    ) delay(100)
                }
                assertEquals("DISMISSED", f.db.momentDao().moment(dismissedId)?.state)
                val suggestions =
                    f.db
                        .momentDao()
                        .browser("SUGGESTED")
                        .load(androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false))
                        as androidx.paging.PagingSource.LoadResult.Page
                assertFalse(suggestions.data.any { it.moment.momentId == dismissedId })
                assertEquals(originalHashes, initial.associateWith(f::hash))
                File(f.evidence, "result.txt")
                    .writeText(
                        "Content observer generated earlier+later imports after COMPLETE; initial3 event IDs=$ids; saved raw members/title intact; deleted AUTO remains dismissed after subsequent real import; initial hashes=$originalHashes"
                    )
            }
        }
}
