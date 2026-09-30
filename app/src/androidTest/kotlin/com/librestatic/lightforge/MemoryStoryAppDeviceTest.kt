package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.librestatic.lightforge.core.data.MemoryDateRange
import com.librestatic.lightforge.core.data.MemoryExclusionRepository
import com.librestatic.lightforge.core.database.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MemoryStoryAppDeviceTest {
    @Test
    fun memoryPlaybackTracksLiveEditsAndMembershipWithoutChangingOriginals(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val db = GalleryDatabaseFactory.open(context)
        val id = "memory-fixture-${UUID.randomUUID()}"
        val title = "Lightforge memory ${UUID.randomUUID()}"
        val evidence = File(context.filesDir, id).apply { mkdirs() }
        val sources = mutableListOf<Uri>()
        var ownedDateRule: String? = null
        fun hash(uri: Uri) =
            context.contentResolver.openInputStream(uri)!!.use {
                java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                } catch (_: StaleObjectException) {}
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width() }
                    ?.scroll(Direction.DOWN, .65f)
                device.waitForIdle()
            }
            error("Missing memory control $selector")
        }
        fun click(tag: String) {
            find(By.res(tag)).click()
            device.waitForIdle()
        }
        fun position(n: Int, total: Int) {
            val container = device.findObject(By.res("moment-list"))
            container?.scroll(Direction.UP, 1f)
            device.waitForIdle()
            find(By.res("moment-position"))
            assertTrue(
                "Expected memory position $n/$total",
                device.wait(
                    Until.hasObject(
                        By.text(
                            context.getString(
                                com.librestatic.lightforge.feature.collections.R.string.moment_story_position,
                                n,
                                total,
                            )
                        )
                    ),
                    15000,
                ),
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
            val grant = context.getString(com.librestatic.lightforge.feature.photos.R.string.grant_access_action)
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
                    com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline
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
            find(By.text(context.getString(R.string.nav_collections))).click()
            device.waitForIdle()
            find(By.text(title)).click()
            device.waitForIdle()
            position(1, 3)
            capture("opened")
            val exclusions = MemoryExclusionRepository(db)
            val days =
                rows.map {
                    Instant.ofEpochMilli(it.timelineSortMillis)
                        .atZone(ZoneId.of("UTC"))
                        .toLocalDate()
                }
            val hiddenDates =
                exclusions.addDate(MemoryDateRange(days.min(), days.max(), "UTC")).rule
            ownedDateRule = hiddenDates.ruleId
            find(By.res("moment-empty"))
            capture("date-rule-empty")
            assertEquals(3, db.momentDao().allMembers(id).size)
            assertTrue(exclusions.removeDate(hiddenDates.ruleId))
            ownedDateRule = null
            position(1, 3)
            capture("date-rule-restored")

            click("moment-next")
            position(2, 3)
            assertEquals(
                rows.map { it.mediaStoreId },
                db.momentDao().allMembers(id).map { it.mediaStoreId },
            )
            click("moment-move-later")
            withTimeout(15000) {
                while (
                    db.momentDao().allMembers(id).last().mediaStoreId != rows[1].mediaStoreId
                ) delay(50)
            }
            position(3, 3)
            val renamed = "$title renamed"
            db.momentDao().rename(id, renamed, System.currentTimeMillis())
            assertTrue(device.wait(Until.hasObject(By.text(renamed)), 15000))
            db.libraryDao().upsertMedia(listOf(rows[1].copy(isAccessible = false)))
            position(1, 2)
            capture("live-removed")
            db.libraryDao().upsertMedia(rows.map { it.copy(isAccessible = false) })
            find(By.res("moment-empty"))
            capture("empty")
            assertEquals(3, db.momentDao().allMembers(id).size)
            db.libraryDao().upsertMedia(rows)
            position(1, 3)
            val first = rows[0]
            find(
                By.res(
                    "moment-image-loaded-${first.volumeName}:${first.mediaStoreId}-${first.generationModified}"
                )
            )
            capture("restored")
            click("moment-delete")
            assertNotNull(db.momentDao().moment(id))
            click("moment-delete-confirm")
            withTimeout(15000) { while (db.momentDao().moment(id) != null) delay(50) }
            assertEquals(hashes, sources.map(::hash))
            File(evidence, "result.json")
                .writeText(
                    """{"status":"PASS","playbackDoesNotReorder":true,"reorderLive":true,"renameLive":true,"visibilityLive":true,"emptyRestore":true,"dateRuleLive":true,"thumbnail":true,"sourceHashes":true}"""
                )
        } catch (failure: Throwable) {
            capture("failure")
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            ownedDateRule?.let { MemoryExclusionRepository(db).removeDate(it) }
            db.momentDao().delete(id)
            sources.forEach {
                context.contentResolver.delete(it, null, null)
                db.libraryDao().deleteMedia("external_primary", ContentUris.parseId(it))
            }
            db.close()
        }
    }
}
