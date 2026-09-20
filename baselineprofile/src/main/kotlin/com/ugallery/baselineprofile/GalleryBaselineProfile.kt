package com.ugallery.baselineprofile

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import org.json.JSONObject
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TargetPackage = "com.ugallery.app"
private const val ProductionTimelineExtra = "com.ugallery.app.extra.PRODUCTION_TIMELINE"
private const val ItemCountExtra = "com.ugallery.app.extra.BENCHMARK_ITEM_COUNT"

@RunWith(AndroidJUnit4::class)
class GalleryBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndTimeline() = rule.collect(
        packageName = TargetPackage,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait { it.putExtra(ItemCountExtra, 100_000) }
        check(device.wait(Until.hasObject(By.res("timeline_grid")), 5_000)) {
            "Timeline grid was not exposed"
        }
        repeat(8) {
            val grid = device.findObject(By.res("timeline_grid"))
                ?: error("Timeline grid disappeared during profile collection")
            grid.fling(Direction.DOWN)
            device.waitForIdle(1_000)
        }
    }

    /** Real indexed media, native decoded viewer, then submitted local search and result re-entry.
     * Run only against the assigned empty emulator target. Root grants image access beforehand;
     * this test neither clears app data nor grants/revokes broad media permissions.
     */
    @Test
    fun productionViewerAndSearch() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        check(context.packageManager.checkPermission(imagePermission, TargetPackage) == PackageManager.PERMISSION_GRANTED) {
            "Assigned target needs image access before profile collection"
        }
        val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")) {
            "Pass the assigned canonical fixtureUuid"
        }
        require(UUID.fromString(uuid).toString() == uuid)
        val fixture = ProfilePhoto(context, uuid)
        try {
            fixture.publish()
            rule.collect(packageName = TargetPackage, includeInStartupProfile = false) {
                killProcess()
                startActivityAndWait { it.putExtra(ProductionTimelineExtra, true) }
                val cell = By.res("media_external_primary_${ContentUris.parseId(fixture.uri)}")
                awaitNode(device, cell, 30_000).click()
                val photo = By.clazz("android.widget.ImageView").desc(targetString(context, "viewer_photo_description"))
                awaitNode(device, photo) // Ready's actual decoded native ImageView, not the thumbnail/fallback.
                device.pressBack()
                awaitNode(device, cell)
                openSearch(device, context)
                val input = By.clazz("android.widget.EditText")
                awaitNode(device, input)
                check(device.wait(Until.gone(By.text(targetString(context, "search_partial_index"))), 30_000)) {
                    "Production search index is still incomplete"
                }
                awaitNode(device, input).click()
                val focused = input.focused(true)
                setFocusedSearchText(device, focused, fixture.query)
                awaitNode(device, By.clazz("android.widget.EditText").focused(true).text(fixture.query))
                device.waitForIdle(1_000) // Let the real text/snapshotFlow frame settle before IME submission.
                device.pressEnter() // Same real focused-input/IME path as LocalModelsAppDeviceTest.
                val result = By.desc(fixture.name)
                awaitNode(device, result, 30_000).click()
                awaitNode(device, photo)
                device.pressBack()
                awaitNode(device, result) // Viewer returns to the same real search result, not Photos.
            }
        } finally {
            fixture.cleanupIfUnchanged()
        }
    }

}


private fun awaitNode(device: UiDevice, selector: BySelector, timeout: Long = 10_000) =
    checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing profile route node: $selector" }

// Text-field expansion can replace the node between findObject and setText. Reacquire only
// for that specific UIAutomator observer failure; never swallow route/application errors.
private fun setFocusedSearchText(device: UiDevice, selector: BySelector, query: String) {
    repeat(3) { attempt ->
        device.waitForIdle(1_000)
        try {
            awaitNode(device, selector).text = query
            return
        } catch (stale: StaleObjectException) {
            if (attempt == 2) throw stale
        }
    }
}

private fun targetString(context: Context, name: String): String {
    val resources = context.packageManager.getResourcesForApplication(TargetPackage)
    val id = resources.getIdentifier(name, "string", TargetPackage)
    check(id != 0) { "Missing target string $name" }
    return resources.getString(id)
}

// Existing LocalModelsAppDeviceTest navigation contract: compact Search, expanded Ask.
// Limit candidates to the actual dock/rail rather than similarly named discovery chips.
private fun openSearch(device: UiDevice, context: Context) {
    val labels = listOf("nav_search", "nav_ask").map { Pattern.quote(targetString(context, it)) }
    val selector = By.text(Pattern.compile(labels.joinToString("|"))).enabled(true)
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (SystemClock.elapsedRealtime() < deadline) {
        try {
            val rail = device.findObjects(By.scrollable(true)).firstOrNull {
                val bounds = it.visibleBounds
                bounds.left == 0 && bounds.width() < device.displayWidth / 3
            }
            val candidates = if (rail != null) rail.findObjects(selector) else
                device.findObjects(selector).filter { it.visibleBounds.top > device.displayHeight * 4 / 5 }
            candidates.firstOrNull { !it.visibleBounds.isEmpty }?.let { it.click(); return }
            rail?.scroll(Direction.UP, .8f)
        } catch (_: StaleObjectException) { /* Reacquire nodes after an actual layout transition. */ }
        SystemClock.sleep(150)
    }
    error("Real Search navigation was not exposed")
}

/** One owner-UID PNG. Exact URI receipt is retained, including on failure; never deletes by folder/name. */
private class ProfilePhoto(private val context: Context, uuid: String) {
    val query = "ugalleryprofile" + uuid.replace("-", "")
    val name = "$query.png"
    private val relativePath = "Pictures/UGalleryProfile-$uuid/"
    private val resolver = context.contentResolver
    private val receipt = File(context.filesDir, "baseline-profile-$uuid.json")
    private var inserted: Uri? = null
    val uri: Uri get() = checkNotNull(inserted)
    private var published: List<String>? = null
    private val bytes = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(30, 120, 180))
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } finally { bitmap.recycle() }
    }
    private val hash = digest(bytes)
    private val columns = arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.GENERATION_ADDED,
        MediaStore.MediaColumns.GENERATION_MODIFIED,
    )

    fun publish() {
        check(receipt.createNewFile()) { "Existing UUID receipt must be reviewed before reuse" }
        record("intent")
        inserted = checkNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        record("inserted")
        ParcelFileDescriptor.AutoCloseOutputStream(checkNotNull(resolver.openFileDescriptor(uri, "w"))).use { output ->
            output.write(bytes); output.flush(); output.fd.sync()
        }
        check(readDigest() == hash)
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            "${MediaStore.MediaColumns.IS_PENDING} = ?", arrayOf("1")) == 1)
        val row = snapshot()
        check(row.take(7) == listOf(context.packageName, name, relativePath, "image/png", "0", "0", bytes.size.toString()))
        check(readDigest() == hash)
        published = row
        record("published")
    }

    fun cleanupIfUnchanged() {
        // A setup failure keeps its exact receipt and URI for explicit review, including partial bytes.
        val expected = published ?: return
        check(snapshot() == expected && readDigest() == hash) { "Changed profile source retained for review" }
        check(resolver.delete(uri, columns.joinToString(" AND ") { "$it = ?" }, expected.toTypedArray()) == 1) {
            "Profile source changed before exact cleanup"
        }
        checkNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)).use {
            check(it.count == 0) { "Exact profile URI survived cleanup" }
        }
        record("cleaned")
    }

    private fun snapshot(): List<String> =
        checkNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst())
            columns.indices.map { cursor.getString(it).orEmpty() }
        }

    private fun readDigest(): String = checkNotNull(resolver.openInputStream(uri)).use { stream ->
        val actual = ByteArray(bytes.size)
        var offset = 0
        while (offset < actual.size) {
            val count = stream.read(actual, offset, actual.size - offset)
            check(count > 0) { "Profile source was truncated" }
            offset += count
        }
        check(stream.read() == -1) { "Profile source grew" }
        digest(actual)
    }

    private fun record(state: String) {
        val body = JSONObject().put("state", state).put("owner", context.packageName)
            .put("uri", inserted?.toString()).put("name", name).put("relativePath", relativePath)
            .put("sha256", hash).put("size", bytes.size).put("snapshot", published?.joinToString("|"))
        FileOutputStream(receipt).use { output ->
            output.write(body.toString(2).toByteArray()); output.flush(); output.fd.sync()
        }
    }
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
