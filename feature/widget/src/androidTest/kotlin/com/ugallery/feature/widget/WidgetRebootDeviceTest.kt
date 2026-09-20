package com.ugallery.feature.widget

import android.Manifest
import android.app.UiAutomation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Two-phase actual reboot acceptance. Root performs reboot between the two methods.
 * Observes retained/boot-delivered RemoteViews without sending update or changing size after reboot.
 * ImageView.draw samples local rendered content, not a screenshot or launcher-OEM proof.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRebootDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val manager = AppWidgetManager.getInstance(context)
    private val provider = ComponentName(context, GalleryWidgetProvider::class.java)
    private val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
        require(UUID.fromString(it).toString() == it)
    }
    // Runner verifies exact hostId absence in dumpsys appwidget before this UUID is launched.
    // AppWidgetHost.appWidgetIds alone cannot distinguish an absent host from a pre-existing empty host.
    private val hostInventorySha = requireNotNull(InstrumentationRegistry.getArguments().getString("hostInventorySha256")).also {
        require(it.matches(Regex("[0-9a-f]{64}")))
    }
    private val hostId = (UUID.fromString(uuid).hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    private val receipt = AtomicFile(File(context.filesDir, "widget-reboot-$uuid.json"))
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relativePath = "Pictures/widget-reboot-$uuid/"
    private val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/ugallery_widget.xml")
    private var activity: GalleryWidgetHostProbeActivity? = null
    private var widgetId: Int? = null
    private val sources = mutableListOf<OwnedImage>()
    private lateinit var record: JSONObject


    /** Root reboots only after this method returns PASS and independently reopens its receipt. */
    @Test fun prepareWidgetBeforeReboot() {
        preflightIdentity()
        check(!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists())
        check(manager.getAppWidgetIds(provider).isEmpty())
        check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
        check(!prefsFile.exists() && !File(prefsFile.path + ".bak").exists())
        check(context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).all.isEmpty())
        val beforeMedia = visibleIds()
        check(beforeMedia.isEmpty()) { "Existing accessible media retained; no fixture adoption" }
        val boot = bootId()
        val beforeFiles = inventory()
        record = JSONObject().put("uuid", uuid).put("package", context.packageName)
            .put("hostId", hostId).put("hostInventorySha256", hostInventorySha)
            .put("bootBefore", boot).put("beforeMedia", JSONArray(beforeMedia))
            .put("beforeFiles", JSONObject(beforeFiles)).put("beforeWidgets", JSONArray())
            .put("prefsOriginallyAbsent", true).put("profileBefore", profileMarker())
            .put("phase", "before-sources-or-binding").put("cleanupComplete", false)
        persist()
        var failure: Throwable? = null
        try {
            makeSource(0, Color.RED); makeSource(1, Color.BLUE)
            check(visibleIds().toSet() == sources.map { ContentUris.parseId(it.uri!!) }.toSet())
            openActivity()
            widgetId = onMain { requireNotNull(activity).host.allocateAppWidgetId() }
            record.put("widgetId", widgetId); phase("before-bind")
            try {
                automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
                check(manager.bindAppWidgetIdIfAllowed(widgetId!!, provider))
            } finally { automation.dropShellPermissionIdentity() }
            requireNoAdoptedIdentity()
            val info = requireNotNull(manager.getAppWidgetInfo(widgetId!!))
            check(info.provider == provider)
            onMain { requireNotNull(activity).mount(widgetId!!, info) }
            awaitDecoded("prepare-decoded")
            awaitIdleWorker("prepare")
            instrumentation.waitForIdleSync()
            val first = requireNotNull(sample())
            check(first.imageInsideHost && first.clickable && colorOf(first.pixel) != null)
            check(first.description == context.getString(R.string.widget_content_description))
            record.put("beforeImage", first.json()).put("optionsBefore", options(manager.getAppWidgetOptions(widgetId!!)))
            check(context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).edit().commit())
            validateFixtureCache()
            record.put("prefsBeforeReboot", prefsSnapshot())
            sources.forEach { check(read(it) == it.anchored) }
            check(bootId() == boot)
            stopActivity()
            awaitIdleWorker("prepared-stopped")
            check(manager.getAppWidgetIds(provider).contentEquals(intArrayOf(widgetId!!)))
            check(AppWidgetHost(context, hostId).appWidgetIds.contentEquals(intArrayOf(widgetId!!)))
            record.put("profilePrepared", profileMarker())
            phase("prepared-awaiting-real-reboot")
        } catch (caught: Throwable) {
            failure = caught
            record.put("failure", caught.javaClass.name).put("failureMessage", caught.message)
        } finally {
            if (failure != null) failure = cleanup(failure)
        }
        failure?.let { throw it }
        marker("PREPARED boot=$boot widget=$widgetId host=$hostId sources=2")
    }

    /** No allocation, binding, resize or explicit update before observing the retained widget. */
    @Test fun verifyWidgetAfterRebootAndCleanup() {
        preflightIdentity()
        record = receipt.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        check(record.getString("uuid") == uuid && record.getString("package") == context.packageName)
        check(record.getInt("hostId") == hostId && record.getString("hostInventorySha256") == hostInventorySha)
        check(record.getString("phase") == "prepared-awaiting-real-reboot" && !record.getBoolean("cleanupComplete"))
        check(record.getBoolean("prefsOriginallyAbsent"))
        // Fail before mutations if root has not rebooted: this leaves the valid prepared fixture usable.
        val afterBoot = bootId()
        check(afterBoot != record.getString("bootBefore")) { "A real changed kernel boot_id is required" }
        widgetId = record.getInt("widgetId")
        loadSources()
        record.put("bootAfter", afterBoot).put("profileAfterBoot", profileMarker())
        var failure: Throwable? = null
        try {
            phase("verifying-existing-widget")
            check(manager.getAppWidgetIds(provider).contentEquals(intArrayOf(widgetId!!)))
            check(AppWidgetHost(context, hostId).appWidgetIds.contentEquals(intArrayOf(widgetId!!)))
            val info = requireNotNull(manager.getAppWidgetInfo(widgetId!!))
            check(info.provider == provider)
            sources.forEach { check(read(it) == it.anchored) }
            check(visibleIds().toSet() == sources.map { ContentUris.parseId(it.uri!!) }.toSet())
            val originalOptions = record.getJSONObject("optionsBefore").toString()
            check(options(manager.getAppWidgetOptions(widgetId!!)).toString() == originalOptions)
            awaitIdleWorker("after-boot-before-host")
            validateFixtureCache()
            record.put("prefsAfterBoot", prefsSnapshot())
            openActivity()
            onMain { requireNotNull(activity).mountExistingWithoutResize(widgetId!!, info) }
            awaitDecoded("existing-widget-after-boot")
            awaitIdleWorker("after-boot-decoded")
            instrumentation.waitForIdleSync()
            val first = requireNotNull(sample())
            check(first.imageInsideHost && first.clickable && colorOf(first.pixel) != null)
            check(first.description == context.getString(R.string.widget_content_description))
            check(options(manager.getAppWidgetOptions(widgetId!!)).toString() == originalOptions)
            val initialIndex = context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).getInt("photo_index", -1)
            record.put("afterBootImage", first.json()); phase("before-post-boot-touch")
            touch(first.centerX, first.centerY)
            await("post-boot-touch-next") {
                sample()?.let { it.deliveries > first.deliveries && colorOf(it.pixel) != null &&
                    colorOf(it.pixel) != colorOf(first.pixel) } == true
            }
            awaitIdleWorker("after-post-boot-touch")
            instrumentation.waitForIdleSync()
            val second = requireNotNull(sample())
            val nextIndex = context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).getInt("photo_index", -1)
            check(initialIndex in 0..1 && nextIndex == (initialIndex + 1) % 2)
            check(second.imageInsideHost && second.clickable && colorOf(second.pixel) != colorOf(first.pixel))
            check(options(manager.getAppWidgetOptions(widgetId!!)).toString() == originalOptions)
            sources.forEach { check(read(it) == it.anchored) }
            check(bootId() == afterBoot)
            record.put("afterTouchImage", second.json()).put("indicesAfterBoot", JSONArray(listOf(initialIndex, nextIndex)))
                .put("optionsAfter", options(manager.getAppWidgetOptions(widgetId!!)))
            phase("reboot-contract-observed")
        } catch (caught: Throwable) {
            failure = caught
            record.put("failure", caught.javaClass.name).put("failureMessage", caught.message)
        } finally { failure = cleanup(failure) }
        failure?.let { throw it }
        check(record.getBoolean("cleanupComplete"))
        marker("PASS real-reboot/same-widget/touch-next cleanup-complete")
    }

    private fun preflightIdentity() {
        check(Build.VERSION.SDK_INT == 35 && context.packageName == "com.ugallery.feature.widget.test")
        check(instrumentation.context.packageName == context.packageName)
        noMediaGrant(); requireNoAdoptedIdentity()
    }
    private fun openActivity() {
        activity = instrumentation.startActivitySync(Intent(context, GalleryWidgetHostProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixtureUuid", uuid).putExtra("hostId", hostId)) as GalleryWidgetHostProbeActivity
    }
    private fun stopActivity() {
        activity?.let { current -> onMain { current.stopHost(); current.finish() } }
        instrumentation.waitForIdleSync()
        activity = null
    }
    private fun awaitDecoded(label: String) = await(label) {
        sample()?.let { colorOf(it.pixel) != null && it.clickable } == true
    }
    private fun bootId(): String {
        // Fixed read-only shell observation, never an injected parameter or reboot operation.
        val value = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("cat /proc/sys/kernel/random/boot_id")).use {
            it.bufferedReader().readText().trim()
        }
        require(UUID.fromString(value).toString() == value)
        return value
    }
    private fun profileMarker(): JSONObject {
        val file = File(context.filesDir, "profileInstalled")
        return JSONObject().put("exists", file.exists()).apply {
            if (file.exists()) { check(file.length() <= 1024); put("bytes", file.length()); put("sha256", sha(file.readBytes())) }
        }
    }
    private fun prefsSnapshot(): JSONObject {
        val prefs = context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE)
        return JSONObject(prefs.all).put("_fileExists", prefsFile.exists()).apply {
            if (prefsFile.exists()) { check(prefsFile.length() <= 65536); put("_fileSha256", sha(prefsFile.readBytes())) }
        }
    }
    private fun validateFixtureCache() {
        val all = context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).all
        val expected = sources.map { "content://media/external/images/media/" + ContentUris.parseId(it.uri!!) }.toSet()
        check(all.keys == setOf("cached_uris", "cached_uris_time", "photo_index"))
        val actual = (all["cached_uris"] as String).split('\n')
        check(actual.size == 2 && actual.toSet() == expected)
        check(all["photo_index"] is Int && all["photo_index"] as Int in 0..1)
        check(all["cached_uris_time"] is Long && all["cached_uris_time"] as Long in 0..System.currentTimeMillis())
    }
    private fun loadSources() {
        check(sources.isEmpty())
        for (index in 0..1) {
            val stored = record.getJSONObject("source-$index")
            val bytes = imageBytes(index, if (index == 0) Color.RED else Color.BLUE)
            check(stored.getString("name") == "widget-reboot-$uuid-$index.png" && stored.getString("path") == relativePath)
            check(stored.getString("hash") == sha(bytes) && stored.getInt("size") == bytes.size)
            val uri = Uri.parse(stored.getString("uri"))
            check(uri == ContentUris.withAppendedId(collection, ContentUris.parseId(uri)))
            sources.add(OwnedImage(index, stored.getString("name"), bytes).also {
                it.uri = uri; it.initial = stored.getJSONObject("initial"); it.anchored = stored.getJSONObject("published").toString()
            })
        }
    }
    private fun cleanup(originalFailure: Throwable?): Throwable? {
        var failure = originalFailure
        var cleanupFailed = false
        fun step(label: String, action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                cleanupFailed = true
                record.put("cleanupComplete", false).put("cleanupFailure-$label", caught.javaClass.name + ": " + caught.message)
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        step("identity") { automation.dropShellPermissionIdentity(); requireNoAdoptedIdentity() }
        step("delete-widget") {
            widgetId?.let { id ->
                val host = AppWidgetHost(context, hostId)
                check(id in host.appWidgetIds)
                val info = manager.getAppWidgetInfo(id)
                check(info == null || info.provider == provider)
                phase("before-own-widget-delete")
                host.deleteAppWidgetId(id)
                check(id !in host.appWidgetIds && manager.getAppWidgetInfo(id) == null)
            }
            check(manager.getAppWidgetIds(provider).isEmpty())
        }
        step("stop-activity") { stopActivity() }
        step("delete-host") {
            val host = AppWidgetHost(context, hostId)
            check(host.appWidgetIds.isEmpty()); host.deleteHost()
            check(host.appWidgetIds.isEmpty()); record.put("hostDeleteReturned", true)
        }
        var idle = false
        step("quiescence") {
            check(manager.getAppWidgetIds(provider).isEmpty())
            awaitIdleWorker("cleanup"); idle = true
        }
        if (idle) {
            // Validate sources against recorded snapshots before deleting; never delete by UUID name alone.
            sources.forEach { source -> step("source-${source.index}") { deleteOwned(source) } }
            step("preferences") {
                if (context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).all.isNotEmpty()) validateFixtureCache()
                check(context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).edit().commit())
                if (prefsFile.exists() || File(prefsFile.path + ".bak").exists()) check(context.deleteSharedPreferences("ugallery_widget"))
                check(!prefsFile.exists() && !File(prefsFile.path + ".bak").exists())
            }
        }
        step("inventory") {
            val afterFiles = inventory(); val afterMedia = visibleIds()
            record.put("afterFiles", JSONObject(afterFiles)).put("afterMedia", JSONArray(afterMedia))
                .put("afterWidgets", JSONArray(manager.getAppWidgetIds(provider).toList())).put("profileAfterCleanup", profileMarker())
            val beforeFiles = record.getJSONObject("beforeFiles")
            check(afterFiles == beforeFiles.keys().asSequence().associateWith { beforeFiles.getString(it) })
            check(afterMedia.isEmpty() && manager.getAppWidgetIds(provider).isEmpty())
            check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
            noMediaGrant(); requireNoAdoptedIdentity()
            check(idle && !cleanupFailed)
            record.put("cleanupComplete", true)
        }
        record.put("phase", if (failure == null) "pass-cleaned" else "failed-preserved")
        step("receipt") { persist() }
        return failure
    }
    private fun marker(value: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "WIDGET_REBOOT $uuid $value\n")
    })

    private class OwnedImage(val index: Int, val name: String, val bytes: ByteArray) {
        var uri: Uri? = null
        var initial: JSONObject? = null
        var anchored: String? = null
    }

    private fun imageBytes(index: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 40, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            // UUID-specific edge pixels preserve a large uniform decoded center.
            uuid.toByteArray().forEachIndexed { x, value -> bitmap.setPixel(x, 0, Color.rgb(value.toInt() and 255, index, 37)) }
            ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }

    private fun makeSource(index: Int, color: Int): OwnedImage {
        val bytes = imageBytes(index, color)
        val source = OwnedImage(index, "widget-reboot-$uuid-$index.png", bytes)
        // Track this source BEFORE insertion, so every later exception reaches finally cleanup.
        sources.add(source)
        val state = JSONObject().put("name", source.name).put("path", relativePath)
            .put("hash", sha(bytes)).put("size", bytes.size).put("phase", "before-insert")
        record.put("source-$index", state); persist()
        source.uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        check(source.uri == ContentUris.withAppendedId(collection, ContentUris.parseId(source.uri!!)))
        state.put("uri", source.uri.toString()).put("phase", "before-write"); persist()
        source.initial = metadata(source)
        state.put("initial", source.initial); persist()
        context.contentResolver.openOutputStream(source.uri!!, "w")!!.use { it.write(bytes) }
        val pending = JSONObject(read(source))
        check(pending.getString("sha") == sha(bytes) && pending.getLong("pending") == 1L)
        state.put("pending", pending).put("phase", "before-publish"); persist()
        check(context.contentResolver.update(source.uri!!, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, cas(pending)) == 1)
        source.anchored = read(source)
        val published = JSONObject(source.anchored!!)
        check(published.getString("sha") == sha(bytes) && published.getLong("pending") == 0L)
        state.put("published", published).put("phase", "published"); persist()
        return source
    }

    private fun includeStates() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    private fun visibleIds(): List<Long> = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), includeStates(), null)!!.use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0)) }.sorted()
    }

    private fun metadata(source: OwnedImage): JSONObject {
        val uri = requireNotNull(source.uri)
        val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path",
            "generation_added", "generation_modified", "is_pending", "is_trashed", "_size")
        return context.contentResolver.query(uri, columns, includeStates(), null)!!.use { c ->
            check(c.moveToFirst())
            JSONObject().put("id", c.getLong(0)).put("owner", c.getString(1)).put("name", c.getString(2))
                .put("path", c.getString(3)).put("added", c.getLong(4)).put("modified", c.getLong(5))
                .put("pending", c.getLong(6)).put("trashed", c.getLong(7))
                .put("size", if (c.isNull(8)) -1L else c.getLong(8)).also { check(!c.moveToNext()) }
        }
    }

    /** Exact URI, owner, UUID name/path and metadata before/after bytes. Never scans to adopt an insert. */
    private fun read(source: OwnedImage): String {
        val uri = requireNotNull(source.uri)
        val before = metadata(source)
        check(before.getLong("id") == ContentUris.parseId(uri) && before.getString("owner") == context.packageName &&
            before.getString("name") == source.name && before.getString("path") == relativePath)
        check(before.getLong("trashed") == 0L)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= source.bytes.size) { "Changed source is larger; retained" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(before.toString() == metadata(source).toString())
        check(before.getLong("size") == bytes.size.toLong() ||
            (before.getLong("pending") == 1L && before.getLong("size") == -1L))
        check(bytes.contentEquals(source.bytes.copyOf(bytes.size))) { "Changed source bytes retained" }
        return before.put("sha", sha(bytes)).put("actualSize", bytes.size).toString()
    }

    private fun cas(row: JSONObject) = includeStates().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
            "_id=? AND owner_package_name=? AND _display_name=? AND relative_path=? AND generation_added=? AND " +
                "generation_modified=? AND is_pending=? AND is_trashed=? AND " +
                if (row.getLong("size") < 0) "_size IS NULL" else "_size=?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            arrayOf("id", "owner", "name", "path", "added", "modified", "pending", "trashed")
                .map { row.get(it).toString() }.toTypedArray() +
                if (row.getLong("size") < 0) emptyArray() else arrayOf(row.getLong("size").toString()))
    }

    private fun deleteOwned(source: OwnedImage) {
        val uri = source.uri ?: return // Unknown insert outcome is retained, never adopted by name.
        val current = read(source)
        if (source.anchored != null) check(current == source.anchored)
        else {
            val row = JSONObject(current)
            source.initial?.let { check(row.getLong("added") == it.getLong("added")) }
            check(row.getLong("pending") == 1L || row.getString("sha") == sha(source.bytes))
        }
        record.getJSONObject("source-${source.index}").put("cleanupSnapshot", JSONObject(current)); persist()
        check(context.contentResolver.delete(uri, cas(JSONObject(current))) == 1)
        context.contentResolver.query(uri, arrayOf("_id"), includeStates(), null)!!.use { check(!it.moveToFirst()) }
    }

    private fun requireNoAdoptedIdentity() {
        // Public on the runtime test API but absent from public compile stubs. Never infer empty
        // identity from a failed observer, and never replace an already adopted caller identity.
        val value = try {
            UiAutomation::class.java.getMethod("getAdoptedShellPermissions").invoke(automation)
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("Shell identity observer unavailable before fixture mutation", failure)
        }
        check(value is Set<*> && value.isEmpty()) { "Shell identity observer is not empty: $value" }
    }

    private fun noMediaGrant() {
        for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.READ_EXTERNAL_STORAGE))
            check(context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
    }

    private fun inventory(): Map<String, String> = buildMap {
        var bytes = 0L
        for (root in listOf(context.filesDir, prefsFile.parentFile!!)) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                if (file == receipt.baseFile || file.path == receipt.baseFile.path + ".bak") return@forEach
                if (file == File(context.filesDir, "profileInstalled")) return@forEach
                bytes += file.length(); check(bytes <= 64L * 1024 * 1024) { "Pre-existing inventory too large" }
                put(file.relativeTo(File(context.applicationInfo.dataDir)).path, sha(file.readBytes()))
            }
        }
    }

    private fun awaitIdleWorker(label: String) {
        // Read-only observer of the ACTUAL dispatch state, under its own production lock.
        // Reflection mismatch fails the observer; it never closes/substitutes the singleton.
        val field = GalleryWidgetProvider::class.java.getDeclaredField("updates").apply { isAccessible = true }
        val dispatcher = field.get(null)
        fun field(name: String) = dispatcher.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val lock = requireNotNull(field("lock").get(dispatcher))
        val draining = field("draining"); val active = field("active"); val pending = field("pending")
        var observed = ""
        try {
            await("worker-idle-$label") {
                synchronized(lock) {
                    val d = draining.getBoolean(dispatcher)
                    val a = active.get(dispatcher) != null
                    val p = pending.get(dispatcher) != null
                    observed = "draining=$d,active=$a,pending=$p"
                    !d && !a && !p
                }
            }
        } finally { record.put("worker-$label", observed); persist() }
    }

    private fun sample() = onMain { activity?.sample() }
    private fun colorOf(pixel: Int): String? = when {
        Color.red(pixel) > 220 && Color.blue(pixel) < 35 && Color.green(pixel) < 35 -> "red"
        Color.blue(pixel) > 220 && Color.red(pixel) < 35 && Color.green(pixel) < 35 -> "blue"
        else -> null
    }
    private fun GalleryWidgetHostProbeActivity.Sample.json() = JSONObject()
        .put("deliveries", deliveries).put("pixel", pixel).put("color", colorOf(pixel))
        .put("width", width).put("height", height).put("centerX", centerX).put("centerY", centerY)
        .put("clickable", clickable).put("description", description).put("imageInsideHost", imageInsideHost)
    private fun options(bundle: Bundle) = JSONObject().put("minWidth", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
        .put("minHeight", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        .put("maxWidth", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
        .put("maxHeight", bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    private fun touch(x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; check(automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            SystemClock.sleep(60)
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timeout: $label" }
            SystemClock.sleep(40)
        }
    }
    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun phase(value: String) { record.put("phase", value); persist() }
    private fun persist() {
        val bytes = record.toString().toByteArray()
        val output = receipt.startWrite()
        try { output.write(bytes); output.fd.sync(); receipt.finishWrite(output) }
        catch (failure: Throwable) { receipt.failWrite(output); throw failure }
        check(receipt.openRead().use { it.readBytes() }.contentEquals(bytes))
        val fd = Os.open(receipt.baseFile.parentFile!!.path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
}
