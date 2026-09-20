package com.ugallery.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.feature.places.OfflinePlacesController
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.maps.MapView

/** Real app route, real DocumentsUI grant, real publisher tiles, and real JPEG EXIF discovery. */
class OfflinePlacesAppDeviceTest {
    @Test
    fun regionalSafImportReviewRenderRecreateAndLocalPhotoLocations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val resolver = context.contentResolver
        val name = "ugallery-maps-app-${UUID.randomUUID()}"
        val mapName = "$name.pmtiles"
        val evidence = File(context.filesDir, name).apply { mkdirs() }
        val ownedUris = mutableListOf<Uri>()
        var importedId: String? = null
        val initiallyAllowed =
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        fun capture(label: String) {
            device.takeScreenshot(File(evidence, "$label.png"))
            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        }
        fun waitFor(label: String, timeout: Long = 30000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            capture("timeout-$label")
            error("Timed out: $label")
        }
        fun scrollPlaces(direction: Direction): Boolean {
            fun findList(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.viewIdResourceName == "places-list") return node
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let {
                        findList(it)?.let { found ->
                            return found
                        }
                    }
                }
                return null
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            val list = findList(root) ?: return false
            // Accessibility scroll targets LazyColumn's semantics directly. A synthetic touch
            // drag over the same parent can instead pan its native MapView child.
            return list.performAction(
                if (direction == Direction.UP) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            )
        }
        fun find(selector: BySelector, direction: Direction = Direction.DOWN): UiObject2 {
            val end = SystemClock.elapsedRealtime() + 20000
            var travel = direction
            while (SystemClock.elapsedRealtime() < end) {
                try {
                    device.wait(Until.findObject(selector), 750)?.let {
                        if (!it.visibleBounds.isEmpty) return it
                    }
                    if (device.hasObject(By.res("places-screen"))) {
                        if (!scrollPlaces(travel))
                            travel = if (travel == Direction.UP) Direction.DOWN else Direction.UP
                    } else {
                        device
                            .findObjects(By.scrollable(true))
                            .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
                            ?.scroll(travel, .6f)
                    }
                    device.waitForIdle()
                } catch (_: StaleObjectException) {}
            }
            capture("missing-control")
            error("Missing $selector")
        }
        fun click(selector: BySelector, direction: Direction = Direction.DOWN) {
            val target = find(selector.enabled(true), direction)
            val resource = target.resourceName
            val targetText = target.text
            val placesAction =
                resource?.startsWith("places-") == true ||
                    targetText ==
                        context.getString(com.ugallery.feature.places.R.string.places_install)
            if (placesAction) {
                fun match(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    val equal =
                        if (!resource.isNullOrBlank()) node.viewIdResourceName == resource
                        else node.text?.toString() == targetText
                    if (equal && node.isVisibleToUser && node.isEnabled) return node
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let {
                            match(it)?.let { found ->
                                return found
                            }
                        }
                    }
                    return null
                }
                var node = instrumentation.uiAutomation.rootInActiveWindow?.let(::match)
                var ancestors = 0
                while (node != null && !node.isClickable && ancestors++ < 8) node = node.parent
                check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "Accessible Places action failed: $selector"
                }
            } else {
                // Keep the platform DocumentsUI/permission workflow a real UI interaction.
                target.click()
            }
            device.waitForIdle()
        }
        fun label(id: Int) = By.text(context.getString(id))
        fun hash(uri: Uri) =
            resolver.openInputStream(uri)!!.use { stream ->
                MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            }
        fun findMap(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        fun packs(): List<JSONObject> {
            val file = File(context.filesDir, "offline-maps/state.json")
            if (!file.exists()) return emptyList()
            val array = JSONObject(file.readText()).getJSONArray("packs")
            return List(array.length()) { array.getJSONObject(it) }
        }
        fun importTask(): JSONObject? {
            val file = File(context.filesDir, "offline-maps/state.json")
            if (!file.exists()) return null
            val array = JSONObject(file.readText()).getJSONArray("tasks")
            return (0 until array.length())
                .map { array.getJSONObject(it) }
                .singleOrNull { it.getString("name") == mapName }
        }
        try {
            val mapUri =
                resolver
                    .insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, mapName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$name/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                    .also { ownedUris += it }
            instrumentation.context.assets.open("maps/monaco.pmtiles").use { input ->
                resolver.openOutputStream(mapUri)!!.use { input.copyTo(it) }
            }
            resolver.update(
                mapUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            val mapHash = hash(mapUri)
            assertEquals(
                "7b614ad9ddcf7a8caea11fba6bd56143106eba25fc4bdc82f0d9cb69ee02879a",
                mapHash,
            )
            val photoUri =
                resolver
                    .insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                            put(
                                MediaStore.Images.ImageColumns.DATE_TAKEN,
                                Instant.parse("2026-06-01T12:00:00Z").toEpochMilli(),
                            )
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                    .also { ownedUris += it }
            val bitmap =
                Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.rgb(80, 130, 190))
                }
            resolver.openOutputStream(photoUri)!!.use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
            bitmap.recycle()
            resolver.openFileDescriptor(photoUri, "rw")!!.use { fd ->
                ExifInterface(fd.fileDescriptor).apply {
                    setLatLong(43.738, 7.425)
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:01 12:00:00")
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                    saveAttributes()
                }
            }
            resolver.update(
                photoUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            val photoHash = hash(photoUri)
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                try {
                    val galleryGrant =
                        label(com.ugallery.feature.photos.R.string.grant_access_action)
                    if (device.wait(Until.hasObject(galleryGrant), 1500)) {
                        click(galleryGrant)
                        click(
                            By.res(
                                Pattern.compile(
                                    ".*permissioncontroller:id/permission_allow(?:_all)?_button"
                                )
                            )
                        )
                    }
                    val decline =
                        label(com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline)
                    if (device.wait(Until.hasObject(decline), 1500)) click(decline)
                    click(
                        By.desc(
                            context.getString(com.ugallery.feature.photos.R.string.open_settings)
                        )
                    )
                    click(label(com.ugallery.feature.settings.R.string.settings_backup))
                    click(label(com.ugallery.feature.settings.R.string.offline_places_entry))
                    assertTrue(device.wait(Until.hasObject(By.res("places-screen")), 15000))
                    if (!initiallyAllowed) {
                        find(By.res("places-location-access"), Direction.UP)
                        capture("location-permission-banner")
                        click(By.res("places-location-access"))
                        val allow =
                            By.res(
                                Pattern.compile(
                                    ".*permissioncontroller:id/permission_allow(?:_foreground_only)?_button"
                                )
                            )
                        if (device.wait(Until.hasObject(allow), 2000)) click(allow)
                        waitFor("location-permission-granted") {
                            context.checkSelfPermission(
                                Manifest.permission.ACCESS_MEDIA_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                    click(By.res("places-import"))
                    val doc = By.res("android:id/title").text(mapName)
                    if (!device.wait(Until.hasObject(doc), 1500)) {
                        click(By.desc("Show roots"))
                        click(By.res("android:id/title").text("Downloads"))
                        click(By.res("android:id/title").text(name))
                    }
                    click(doc)
                    waitFor("import-review", 60000) {
                        importTask()?.getString("status") == "ReadyForReview"
                    }
                    val id = importTask()!!.getString("id")
                    importedId = id
                    assertTrue(packs().none { it.getString("id") == id })
                    click(By.res("places-review-$id"))
                    waitFor("review-dialog-visible") {
                        device.hasObject(label(com.ugallery.feature.places.R.string.places_install))
                    }
                    capture("regional-review")
                    click(label(com.ugallery.feature.places.R.string.places_install))
                    waitFor("installed", 60000) { packs().any { it.getString("id") == id } }
                    val installed = packs().single { it.getString("id") == id }
                    assertEquals(mapHash, installed.getString("sha"))
                    click(By.res("places-select-$id"))
                    find(By.res("places-map-ready"), Direction.UP)
                    var rendered = 0
                    waitFor("real-geometry", 30000) {
                        scenario.onActivity { activity ->
                            findMap(activity.window.decorView)?.getMapAsync { map ->
                                rendered =
                                    map.queryRenderedFeatures(
                                            RectF(0f, 0f, 4000f, 4000f),
                                            "water",
                                            "roads",
                                            "buildings",
                                        )
                                        .size
                            }
                        }
                        rendered > 0
                    }
                    capture("regional-render")
                    scenario.recreate()
                    find(By.res("places-map-ready"), Direction.UP)
                    var restored = 0
                    waitFor("recreated-geometry", 30000) {
                        scenario.onActivity { activity ->
                            findMap(activity.window.decorView)?.getMapAsync { map ->
                                restored =
                                    map.queryRenderedFeatures(
                                            RectF(0f, 0f, 4000f, 4000f),
                                            "water",
                                            "roads",
                                            "buildings",
                                        )
                                        .size
                            }
                        }
                        restored > 0
                    }
                    // Background indexing, not a direct call to metadata.exifDetails and not a
                    // seeded DAO.
                    val db = GalleryDatabaseFactory.open(context)
                    try {
                        waitFor("gallery-indexed", 60000) {
                            runBlocking {
                                db.libraryDao()
                                    .media("external_primary", ContentUris.parseId(photoUri)) !=
                                    null
                            }
                        }
                        click(By.res("places-location-refresh"), Direction.UP)
                        waitFor("real-exif-indexed", 60000) {
                            runBlocking {
                                db.libraryDao()
                                    .exif("external_primary", ContentUris.parseId(photoUri))
                                    ?.let {
                                        it.latitude != null &&
                                            it.longitude != null &&
                                            it.locationReadWithPermission
                                    } == true
                            }
                        }
                    } finally {
                        db.close()
                    }
                    click(By.res("places-year-2026"), Direction.UP)
                    waitFor("year-2026-selected") {
                        device.findObject(By.res("places-year-2026"))?.let {
                            it.isChecked || it.isSelected
                        } == true
                    }
                    // Let the debounced Room/year query finish before looking below the map.
                    SystemClock.sleep(1000)
                    capture("year-selected-before-group")
                    val group = By.res(Pattern.compile("places-group-.*"))
                    click(group)
                    click(By.res("places-photo-external_primary-${ContentUris.parseId(photoUri)}"))
                    waitFor("viewer-open") { !device.hasObject(By.res("places-screen")) }
                    capture("photo-opened")
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.res("places-screen")), 15000))
                    assertEquals(mapHash, hash(mapUri))
                    assertEquals(photoHash, hash(photoUri))
                    capture("returned-map")
                    File(evidence, "result.json")
                        .writeText(
                            JSONObject()
                                .put("status", "PASS")
                                .put("realDocumentsUi", true)
                                .put("packId", id)
                                .put("mapSha256", mapHash)
                                .put("photoSha256", photoHash)
                                .put("renderedFeatures", rendered)
                                .put("restoredFeatures", restored)
                                .put("permissionBannerCovered", !initiallyAllowed)
                                .put("realExifLocallyIndexed", true)
                                .put("analysisEnableRequested", false)
                                .toString(2)
                        )
                } catch (failure: Throwable) {
                    capture("failure-before-close")
                    File(evidence, "failure.txt").writeText(failure.stackTraceToString())
                    throw failure
                }
            }
        } finally {
            importedId?.let { id ->
                val controller = OfflinePlacesController(context)
                try {
                    runBlocking {
                        if (controller.packs.value.any { it.id == id }) {
                            controller.removePackage(id)
                        } else if (controller.tasks.value.any { it.id == id }) {
                            controller.cancel(id)
                            controller.run(id)
                        }
                    }
                } finally {
                    controller.close()
                }
            }
            ownedUris.forEach { resolver.delete(it, null, null) }
        }
    }
}
