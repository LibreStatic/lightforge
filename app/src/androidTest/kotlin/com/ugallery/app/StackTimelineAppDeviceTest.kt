package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.data.GalleryPhotoStackRepository
import com.ugallery.core.database.GalleryDatabaseFactory
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual native Photos -> saved stack -> cover/compare -> separation in the isolated acceptance
 * app.
 */
class StackTimelineAppDeviceTest {
    @Test
    fun collapsedTimelineOpensStackSelectsAllVisibleMembersAndExpandsForIndividualActions(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            check(context.packageName == "com.ugallery.app.pdfacceptance")
            val device = UiDevice.getInstance(instrumentation)
            val gallery = GalleryDatabaseFactory.open(context)
            val label = "UGallery-StackTimeline-${UUID.randomUUID()}"
            val evidence =
                File(context.filesDir, "stack-timeline-screen-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val sources = mutableListOf<Uri>()
            val repository = GalleryPhotoStackRepository(gallery)
            var ownedStack: String? = null
            fun hash(uri: Uri) =
                context.contentResolver.openInputStream(uri)!!.use {
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(it.readBytes())
                        .toList()
                }
            fun clickTag(tag: String) {
                repeat(16) {
                    try {
                        val node = device.findObject(By.res(tag))
                        if (node != null && !node.visibleBounds.isEmpty) {
                            node.click()
                            device.waitForIdle()
                            return
                        }
                    } catch (_: StaleObjectException) {}
                    device.findObject(By.res("photo-stacks-list"))?.scroll(Direction.DOWN, 0.65f)
                    device.waitForIdle()
                }
                error("Missing stack control: $tag")
            }
            fun awaitImage(id: Long, size: Int = 1024) {
                assertTrue(
                    "Original source image did not render: $id / $size",
                    device.wait(
                        Until.hasObject(By.res("stack-image-loaded-external_primary:$id-$size")),
                        15_000,
                    ),
                )
            }
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
                            if (!node.visibleBounds.isEmpty) {
                                node.click()
                                clicked = true
                            }
                        }
                    } catch (_: StaleObjectException) {
                        // Android 15 may invalidate the label node during the document transition.
                        // Refetch before input; never reuse the stale UiObject2 instance.
                    }
                    if (!clicked) {
                        device.waitForIdle()
                        android.os.SystemClock.sleep(50)
                    }
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
                            MediaStore.Images.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY
                            ),
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
                val grant =
                    context.getString(com.ugallery.feature.photos.R.string.grant_access_action)
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
                        com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline
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
                        By.desc(context.getString(com.ugallery.feature.viewer.R.string.viewer_more))
                    )
                    .click()
                val originalHashes = sources.map(::hash)
                click(context.getString(com.ugallery.feature.collections.R.string.stacks_create))
                val id =
                    withTimeout(15_000) {
                        while (true) {
                            val membership =
                                gallery
                                    .photoStackDao()
                                    .membership("external_primary", ContentUris.parseId(sources[0]))
                            if (membership != null) return@withTimeout membership.stackId
                            delay(50)
                        }
                        error("unreachable")
                    }
                ownedStack = id
                assertEquals(2, gallery.photoStackDao().rawMembers(id).size)
                assertTrue(device.wait(Until.hasObject(By.res("photo-stacks-screen")), 15_000))
                val previous = gallery.photoStackDao().get(id)!!
                awaitImage(previous.coverMediaStoreId)
                fun cell(photoId: Long) = By.res("media_external_primary_$photoId")
                val description =
                    context.getString(
                        com.ugallery.feature.photos.R.string.timeline_stack_description,
                        2,
                    )
                fun backToPhotos() {
                    // Back first returns from the detail to Saved. Observe that state before
                    // the second Back; the Photos pager may load after the route transition.
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.res("saved-stack-$id")), 10000))
                    device.pressBack()
                    assertTrue(device.wait(Until.gone(By.res("photo-stacks-screen")), 10000))
                    assertTrue(device.wait(Until.hasObject(By.res("timeline_grid")), 15000))
                }
                backToPhotos()
                assertTrue(device.wait(Until.hasObject(By.desc(description)), 15000))
                val hidden =
                    sources.map(ContentUris::parseId).first { it != previous.coverMediaStoreId }
                assertFalse(
                    "Non-cover is still a duplicate timeline cell",
                    device.hasObject(cell(hidden)),
                )
                capture("collapsed")
                val centerCover = device.findObject(cell(previous.coverMediaStoreId)).visibleCenter
                device.executeShellCommand(
                    "input swipe ${centerCover.x} ${centerCover.y} ${centerCover.x} ${centerCover.y} ${android.view.ViewConfiguration.getLongPressTimeout()+800}"
                )
                assertTrue(
                    device.wait(
                        Until.hasObject(By.text(context.getString(R.string.selection_count, 2))),
                        15000,
                    )
                )
                for (tag in tags) assertTrue(device.wait(Until.hasObject(By.res(tag)), 15000))
                assertTrue(device.wait(Until.gone(By.desc(description)), 15000))
                assertTrue(
                    device.hasObject(
                        By.text(
                            context.getString(
                                com.ugallery.feature.photos.R.string.timeline_stack_selection_hint
                            )
                        )
                    )
                )
                capture("expanded-selected")
                device.findObject(cell(hidden)).click()
                assertTrue(
                    device.wait(
                        Until.hasObject(By.text(context.getString(R.string.selection_count, 1))),
                        10000,
                    )
                )
                assertTrue(device.hasObject(cell(hidden)))
                capture("one-deselected")
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.desc(description)), 15000))
                device.findObject(cell(previous.coverMediaStoreId)).click()
                assertTrue(device.wait(Until.hasObject(By.res("photo-stacks-screen")), 10000))
                awaitImage(previous.coverMediaStoreId)
                clickTag("stack-photo-external_primary:$hidden")
                clickTag("stack-set-cover")
                withTimeout(10000) {
                    while (gallery.photoStackDao().get(id)?.coverMediaStoreId != hidden) delay(50)
                }
                backToPhotos()
                assertTrue(device.wait(Until.hasObject(By.desc(description)), 15000))
                assertTrue(device.hasObject(cell(hidden)))
                assertFalse(device.hasObject(cell(previous.coverMediaStoreId)))
                assertTrue(
                    device.wait(
                        Until.hasObject(By.res("timeline-image-loaded-external_primary_$hidden")),
                        15000,
                    )
                )
                capture("changed-cover")
                repository.dissolve(id, gallery.photoStackDao().get(id)!!.revision)
                for (tag in tags) assertTrue(device.wait(Until.hasObject(By.res(tag)), 15000))
                assertTrue(device.wait(Until.gone(By.desc(description)), 15000))
                capture("unstacked")
                assertEquals(originalHashes, sources.map(::hash))
                assertTrue(
                    sources.all {
                        gallery
                            .photoStackDao()
                            .membership("external_primary", ContentUris.parseId(it)) == null
                    }
                )
                capture("sources-preserved")
                File(evidence, "result.json")
                    .writeText(
                        """{"status":"PASS","collapsed":true,"opensStack":true,"selectedVisibleMembers":2,"expandedForSelection":true,"deselectedOne":true,"newCoverInTimeline":true,"unstackInvalidates":true,"sourceHashes":true}"""
                    )
            } catch (e: Throwable) {
                capture("failure")
                throw e
            } finally {
                ownedStack?.let { id ->
                    gallery.photoStackDao().get(id)?.let { repository.dissolve(id, it.revision) }
                }
                for (uri in sources) {
                    context.contentResolver.delete(uri, null, null)
                    gallery.libraryDao().deleteMedia("external_primary", ContentUris.parseId(uri))
                }
                gallery.close()
            }
        }
}
