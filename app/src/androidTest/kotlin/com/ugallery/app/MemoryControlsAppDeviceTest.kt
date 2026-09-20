package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.data.MemoryExclusionRepository
import com.ugallery.core.data.MomentRepository
import com.ugallery.core.database.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real-app navigation and reversible rule UI, using only owned opt-in acceptance fixtures. */
class MemoryControlsAppDeviceTest {
    @Test
    fun controlsAreDiscoverableAndRoundTripToLiveMemoryWithUndo(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val exclusions = MemoryExclusionRepository(db)
        val moments = MomentRepository(db)
        val id = "memory-controls-fixture-${UUID.randomUUID()}"
        val title = "UGallery controls ${UUID.randomUUID()}"
        val evidence = File(context.filesDir, id).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        var ownedDateRule: String? = null
        var ownedVideo: Uri? = null
        var videosBefore: Set<Long>? = null
        val videoCollection =
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun memoryVideoIds(): Set<Long> =
            context.contentResolver
                .query(
                    videoCollection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                    arrayOf(
                        "Movies/UGallery/Memories/",
                        "UGallery-Memory-%.mp4",
                        context.packageName,
                    ),
                    null,
                )!!
                .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

        fun hash(uri: Uri) =
            context.contentResolver.openInputStream(uri)!!.use {
                java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                } catch (_: StaleObjectException) {}
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width() }
                    ?.scroll(Direction.DOWN, .6f)
                device.waitForIdle()
            }
            error("Missing memory control $selector")
        }
        fun click(tag: String) {
            if (tag == "moment-memory-controls" || tag == "moment-make-video") {
                device.findObject(By.res("moment-list"))?.scroll(Direction.UP, 1f)
                device.waitForIdle()
            }
            find(By.res(tag)).click()
            device.waitForIdle()
        }
        fun awaitTag(tag: String) {
            assertTrue("Missing $tag", device.wait(Until.hasObject(By.res(tag)), 15_000))
        }
        fun position(total: Int) {
            device.findObject(By.res("moment-list"))?.scroll(Direction.UP, 1f)
            device.waitForIdle()
            find(By.res("moment-position"))
            assertTrue(
                device.wait(
                    Until.hasObject(
                        By.text(
                            context.getString(
                                com.ugallery.feature.collections.R.string.moment_story_position,
                                1,
                                total,
                            )
                        )
                    ),
                    15_000,
                )
            )
        }
        try {
            repeat(3) { i ->
                val uri =
                    context.contentResolver.insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "$id-$i.jpg")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$id/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                sources.add(uri)
                val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(40 + i * 60, 100, 160))
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
            }
            device.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )
            val grant = context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
            if (device.wait(Until.hasObject(By.text(grant)), 1500)) {
                find(By.text(grant)).click()
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
            if (device.wait(Until.hasObject(By.text(decline)), 1500)) find(By.text(decline)).click()
            withTimeout(45000) {
                while (
                    sources.any {
                        db.libraryDao().media("external_primary", ContentUris.parseId(it)) == null
                    }
                ) delay(100)
            }
            val rows =
                sources.map { db.libraryDao().media("external_primary", ContentUris.parseId(it))!! }
            val hashes = sources.map(::hash)
            db.momentDao()
                .upsertMoment(
                    MomentEntity(
                        id,
                        "USER",
                        "SAVED",
                        "fixture",
                        rows.first().timelineSortMillis,
                        rows.last().timelineSortMillis,
                        title,
                        "USER",
                        true,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                    )
                )
            db.momentDao()
                .insertMembers(
                    rows.mapIndexed { index, row ->
                        MomentMemberEntity(
                            id,
                            index,
                            row.volumeName,
                            row.mediaStoreId,
                            row.generationModified,
                            "USER",
                            1f,
                        )
                    }
                )

            db.momentDao()
                .upsertCover(
                    MomentCoverEntity(id, rows.first().volumeName, rows.first().mediaStoreId, true)
                )
            find(By.text(context.getString(R.string.nav_collections))).click()
            device.waitForIdle()
            click("collections-memory-controls")
            awaitTag("memory-controls-screen")
            capture("collections-entry")
            click("memory-controls-back")
            repeat(3) {
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width() }
                    ?.scroll(Direction.UP, 1f)
            }
            find(By.text(title)).click()
            device.waitForIdle()
            position(3)
            click("moment-memory-controls")
            awaitTag("memory-controls-screen")
            capture("story-entry")
            val days =
                rows.map {
                    Instant.ofEpochMilli(it.timelineSortMillis)
                        .atZone(ZoneId.of("UTC"))
                        .toLocalDate()
                }
            val beforeRules = exclusions.dates().first().map { it.ruleId }.toSet()
            assertFalse(
                "Fixture date rule already exists",
                exclusions.dates().first().any {
                    it.startDay == days.min().toEpochDay() &&
                        it.endDay == days.max().toEpochDay() &&
                        it.zoneId == "UTC"
                },
            )
            click("memory-controls-add-dates")
            find(By.res("memory-controls-start")).text = days.min().toString()
            find(By.res("memory-controls-end")).text = days.max().toString()
            find(By.res("memory-controls-zone")).text = "UTC"
            if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
                device.pressBack()
            click("memory-controls-save")
            capture("global-confirmation")
            click("memory-controls-confirm")
            ownedDateRule =
                withTimeout(15_000) {
                    var rule: MemoryDateExclusionEntity? = null
                    while (rule == null) {
                        rule =
                            exclusions.dates().first().firstOrNull {
                                it.ruleId !in beforeRules &&
                                    it.startDay == days.min().toEpochDay() &&
                                    it.endDay == days.max().toEpochDay() &&
                                    it.zoneId == "UTC"
                            }
                        if (rule == null) delay(50)
                    }
                    rule.ruleId
                }
            click("memory-controls-back")
            awaitTag("moment-screen")
            find(By.res("moment-empty"))
            assertTrue(moments.members(id).isEmpty())
            assertEquals(3, db.momentDao().allMembers(id).size)
            assertEquals(title, db.momentDao().moment(id)?.title)
            capture("story-hidden")
            click("moment-memory-controls")
            awaitTag("memory-controls-screen")
            click("memory-controls-undo")
            withTimeout(15_000) {
                while (exclusions.dates().first().any { it.ruleId == ownedDateRule }) delay(50)
            }
            ownedDateRule = null
            click("memory-controls-back")
            awaitTag("moment-screen")
            position(3)
            assertEquals(
                rows.first().mediaStoreId,
                moments.summaries().first().first { it.moment.momentId == id }.coverMediaStoreId,
            )
            capture("story-restored")
            videosBefore = memoryVideoIds()
            click("moment-make-video")
            awaitTag("memory-video-screen")
            click("memory-video-seconds-1")
            capture("video-draft")
            click("memory-video-export")
            val exportDeadline = android.os.SystemClock.elapsedRealtime() + 180_000
            var saved = false
            while (!saved && android.os.SystemClock.elapsedRealtime() < exportDeadline) {
                assertFalse(
                    "Memory video export failed",
                    device.hasObject(By.res("memory-video-error")),
                )
                saved = device.hasObject(By.res("memory-video-saved"))
                if (!saved) {
                    device
                        .findObjects(By.scrollable(true))
                        .maxByOrNull { it.visibleBounds.width() }
                        ?.scroll(Direction.DOWN, .5f)
                    android.os.SystemClock.sleep(200)
                }
            }
            assertTrue("Memory video did not publish", saved)
            capture("video-saved")
            val newVideos = memoryVideoIds() - requireNotNull(videosBefore)
            assertEquals("Exactly the foreground owned export must be new", 1, newVideos.size)
            val output = ContentUris.withAppendedId(videoCollection, newVideos.single())
            ownedVideo = output
            context.contentResolver
                .query(
                    output,
                    arrayOf(
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )!!
                .use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertTrue(it.getLong(1) > 0)
                    assertEquals("video/mp4", it.getString(2))
                }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, output, null)
                assertEquals(1, extractor.trackCount)
                assertEquals("video/avc", extractor.getTrackFormat(0).getString("mime"))
            } finally {
                extractor.release()
            }
            val retriever = MediaMetadataRetriever()
            var duration = 0L
            try {
                retriever.setDataSource(context, output)
                duration =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                        .toLong()
                assertTrue(
                    "Expected 3-second owned video, got $duration ms",
                    duration in 2750L..3250L,
                )
                assertEquals(
                    "1280",
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                )
                assertEquals(
                    "720",
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                )
                repeat(3) { index ->
                    val frame =
                        retriever.getFrameAtTime(
                            index * 1_000_000L + 500_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        )!!
                    try {
                        val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                        assertTrue(
                            "Ordered frame red $index",
                            kotlin.math.abs(
                                android.graphics.Color.red(pixel) - (40 + index * 60)
                            ) <= 25,
                        )
                        assertTrue(
                            "Ordered frame green $index",
                            kotlin.math.abs(android.graphics.Color.green(pixel) - 100) <= 25,
                        )
                        assertTrue(
                            "Ordered frame blue $index",
                            kotlin.math.abs(android.graphics.Color.blue(pixel) - 160) <= 25,
                        )
                    } finally {
                        frame.recycle()
                    }
                }
            } finally {
                retriever.release()
            }
            File(evidence, "video-result.json")
                .writeText(
                    """{"status":"PASS","uri":"$output","durationMs":$duration,"codec":"video/avc","width":1280,"height":720,"orderedFrames":3,"silent":true,"published":true}"""
                )
            find(
                    By.desc(
                        context.getString(
                            com.ugallery.feature.videoeditor.R.string.memory_video_back
                        )
                    )
                )
                .click()
            device.waitForIdle()
            awaitTag("moment-screen")
            position(3)
            find(By.desc(context.getString(com.ugallery.feature.collections.R.string.memory_back)))
                .click()
            device.waitForIdle()
            val expanded = context.resources.configuration.screenWidthDp >= 840
            if (expanded) {
                awaitTag("rail-memory-controls")
                click("rail-memory-controls")
                awaitTag("memory-controls-screen")
                capture("rail-entry")
                click("memory-controls-back")
                // Rail is a root entry, not a stale return to a previous story.
                assertTrue(
                    device.wait(
                        Until.hasObject(By.text(context.getString(R.string.nav_collections))),
                        15_000,
                    )
                )
                assertTrue(device.wait(Until.gone(By.res("moment-screen")), 15_000))
            }
            // Settings navigation smoke only. SAF backup/restore workflow has separate native
            // coverage.
            find(
                    By.text(
                        context.getString(
                            com.ugallery.feature.collections.R.string.collections_local_analysis
                        )
                    )
                )
                .click()
            device.waitForIdle()
            assertTrue(
                device.wait(
                    Until.hasObject(
                        By.text(
                            context.getString(com.ugallery.feature.settings.R.string.settings_title)
                        )
                    ),
                    15_000,
                )
            )
            find(By.text(context.getString(com.ugallery.feature.settings.R.string.settings_backup)))
                .click()
            device.waitForIdle()
            // These production settings controls do not export test tags as Android resource IDs.
            find(
                    By.text(
                        context.getString(com.ugallery.feature.settings.R.string.local_backup_title)
                    )
                )
                .click()
            device.waitForIdle()
            assertTrue(
                device.wait(
                    Until.hasObject(
                        By.text(
                            context.getString(
                                com.ugallery.feature.settings.R.string.local_backup_organization_scope
                            )
                        )
                    ),
                    15_000,
                )
            )
            capture("settings-local-backup")
            find(
                    By.text(
                        context.getString(com.ugallery.feature.settings.R.string.local_backup_back)
                    )
                )
                .click()
            device.waitForIdle()
            assertTrue(
                device.wait(
                    Until.hasObject(
                        By.text(
                            context.getString(com.ugallery.feature.settings.R.string.settings_title)
                        )
                    ),
                    15_000,
                )
            )
            capture("settings-back")
            assertEquals(hashes, sources.map(::hash))
            File(evidence, "result.json")
                .writeText(
                    """{"status":"PASS","collections":true,"storyRoundTrip":true,"dateUi":true,"liveEmpty":true,"undoAfterNavigation":true,"restoredCover":true,"realVideoUi":true,"settingsBackupNavigation":true,"rail":$expanded,"sourceHashes":true}"""
                )
        } catch (failure: Throwable) {
            device.findObject(By.res("memory-video-cancel"))?.let {
                it.click()
                device.wait(Until.gone(By.res("memory-video-progress")), 20_000)
            }
            capture("failure")
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            // Before/after diff is confined to the acceptance APK owner and this exporter path.
            // No other UI/export may run concurrently on this device while this test executes.
            if (ownedVideo == null && videosBefore != null) {
                val created = memoryVideoIds() - requireNotNull(videosBefore)
                if (created.size == 1)
                    ownedVideo = ContentUris.withAppendedId(videoCollection, created.single())
            }
            ownedVideo?.let {
                context.contentResolver.delete(it, null, null)
                db.libraryDao().deleteMedia("external_primary", ContentUris.parseId(it))
            }
            ownedDateRule?.let { exclusions.removeDate(it) }
            db.momentDao().delete(id)
            sources.forEach {
                context.contentResolver.delete(it, null, null)
                db.libraryDao().deleteMedia("external_primary", ContentUris.parseId(it))
            }
            db.close()
        }
    }
}
