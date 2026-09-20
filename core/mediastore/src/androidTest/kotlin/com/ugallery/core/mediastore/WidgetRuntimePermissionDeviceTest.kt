package com.ugallery.core.mediastore

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

/** Real AppWidget service, broadcasts, production decoder/selector and touch PendingIntent. */
@RunWith(AndroidJUnit4::class)
class WidgetRuntimePermissionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val manager = AppWidgetManager.getInstance(context)
    private val widgetPackage = "com.ugallery.feature.widget.test"
    private val provider = ComponentName(widgetPackage, "com.ugallery.feature.widget.GalleryWidgetProvider")
    private val helperUri = Uri.parse("content://com.ugallery.feature.widget.test.permissionfixture")
    private var helperPrepared = false
    private var permissionTouched = false
    private val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
        require(UUID.fromString(it).toString() == it)
    }
    // Runner verifies exact hostId absence in dumpsys appwidget before this UUID is launched.
    // AppWidgetHost.appWidgetIds alone cannot distinguish an absent host from a pre-existing empty host.
    private val hostInventorySha = requireNotNull(InstrumentationRegistry.getArguments().getString("hostInventorySha256")).also {
        require(it.matches(Regex("[0-9a-f]{64}")))
    }
    private val hostId = (UUID.fromString(uuid).hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    private val receipt = AtomicFile(File(context.filesDir, "widget-runtime-$uuid.json"))
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relativePath = "Pictures/widget-runtime-$uuid/"
    private val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/ugallery_widget.xml")
    private var activity: WidgetRuntimeHostActivity? = null
    private var widgetId: Int? = null
    private val sources = mutableListOf<OwnedImage>()
    private lateinit var record: JSONObject

    @Test fun runtimeRevokeDeniesForeignPhotoAndTouchClearsRetainedBitmap() {
        check(Build.VERSION.SDK_INT == 35)
        check(context.packageName == "com.ugallery.core.mediastore.test")
        check(instrumentation.context.packageName == context.packageName)
        noMediaGrant()
        requireNoAdoptedIdentity()
        check(!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists())
        check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
        check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
        check(!prefsFile.exists() && !File(prefsFile.path + ".bak").exists())
        check(context.getSharedPreferences("ugallery_widget", Context.MODE_PRIVATE).all.isEmpty())
        val beforeMedia = visibleIds()
        check(beforeMedia.isEmpty()) { "Existing accessible fixture media retained; no adoption" }
        check(!runtimeGranted())
        val beforeFiles = inventory()
        record = JSONObject().put("uuid", uuid).put("package", context.packageName)
            .put("hostId", hostId).put("hostInventorySha256", hostInventorySha).put("beforeMedia", JSONArray(beforeMedia))
            .put("beforeWidgets", JSONArray()).put("beforeFiles", JSONObject(beforeFiles)).put("profileMarkerBefore", profileMarker())
            .put("phase", "before-sources-or-binding").put("cleanupComplete", false)
        persist()
        var failure: Throwable? = null
        try {
            makeSource(0, Color.RED)
            makeSource(1, Color.BLUE)
            check(visibleIds().toSet() == sources.map { ContentUris.parseId(it.uri!!) }.toSet())
            phase("before-runtime-grant")
            permissionTouched = true
            automation.grantRuntimePermission(widgetPackage, Manifest.permission.READ_MEDIA_IMAGES)
            await("runtime-granted") { runtimeGranted() }
            record.put("granted", true)
            val args = Bundle().apply {
                putStringArrayList("uris", ArrayList(sources.map { alias(it) }))
                putStringArrayList("sha256", ArrayList(sources.map { sha(it.bytes) }))
            }
            helperPrepared = true // A call may commit before its reply is lost; preserve recoverability.
            record.put("prepareCache", bundleJson(helper("prepareCache", args)))
            phase("cache-prepared")
            phase("before-host-start")
            activity = instrumentation.startActivitySync(Intent(context, WidgetRuntimeHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fixtureUuid", uuid).putExtra("hostId", hostId)) as WidgetRuntimeHostActivity
            widgetId = onMain { requireNotNull(activity).host.allocateAppWidgetId() }
            record.put("widgetId", widgetId)
            phase("before-bind")
            try {
                automation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
                check(manager.bindAppWidgetIdIfAllowed(widgetId!!, provider)) { "Exact own widget binding denied" }
            } finally {
                automation.dropShellPermissionIdentity()
            }
            requireNoAdoptedIdentity()
            check(AppWidgetHost(context, hostId).appWidgetIds.contentEquals(intArrayOf(widgetId!!)))
            val info = requireNotNull(manager.getAppWidgetInfo(widgetId!!))
            check(info.provider == provider)
            onMain { requireNotNull(activity).mount(widgetId!!, info) }
            await("initial-decoded-delivery") { sample()?.let { colorOf(it.pixel) != null && it.clickable } == true }
            instrumentation.waitForIdleSync()
            val first = requireNotNull(sample())
            check(first.imageInsideHost && first.hasImage)
            record.put("first", first.json())
            val beforeProbe = helper("probeRead", Bundle().apply { putString("uri", alias(sources[0])) })
            record.put("beforeProbe", bundleJson(beforeProbe))
            check(beforeProbe.getBoolean("runtimeGranted") && beforeProbe.getBoolean("readable") && beforeProbe.getBoolean("sha256Match"))
            phase("before-runtime-revoke")
            automation.revokeRuntimePermission(widgetPackage, Manifest.permission.READ_MEDIA_IMAGES)
            await("runtime-revoked") { !runtimeGranted() }
            val denied = helper("probeRead", Bundle().apply { putString("uri", alias(sources[0])) })
            record.put("afterProbe", bundleJson(denied))
            check(!denied.getBoolean("runtimeGranted") && !denied.getBoolean("readable") && denied.getBoolean("securityException"))
            check(beforeProbe.getInt("uid") == denied.getInt("uid") && denied.getInt("uid") != android.os.Process.myUid())
            val retained = requireNotNull(sample())
            record.put("beforeTouchAfterRevoke", retained.json())
            phase("before-real-touch-after-revoke")
            touch(retained.centerX, retained.centerY)
            await("touch-empty-after-revoke") {
                sample()?.let { it.deliveries > retained.deliveries && !it.hasImage && it.drawableCleared && it.emptyVisible && it.emptyText.isNotBlank() } == true
            }
            val empty = requireNotNull(sample())
            record.put("empty", empty.json())
            sources.forEach { check(read(it) == it.anchored) }
            phase("contract-observed")
        } catch (caught: Throwable) {
            failure = caught
            record.put("failure", caught.javaClass.name).put("failureMessage", caught.message)
        } finally {
            var cleanupFailed = false
            fun cleanupStep(label: String, action: () -> Unit) {
                try { action() } catch (caught: Throwable) {
                    cleanupFailed = true
                    record.put("cleanupComplete", false)
                    record.put("cleanupFailure-$label", caught.javaClass.name + ": " + caught.message)
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            cleanupStep("shell-identity") {
                automation.dropShellPermissionIdentity()
                requireNoAdoptedIdentity()
            }
            cleanupStep("delete-widget") {
                widgetId?.let { id ->
                    val host = AppWidgetHost(context, hostId)
                    check(id in host.appWidgetIds)
                    val bound = manager.getAppWidgetInfo(id)
                    check(bound == null || bound.provider == provider)
                    phase("before-own-widget-delete")
                    host.deleteAppWidgetId(id)
                    check(id !in host.appWidgetIds && manager.getAppWidgetInfo(id) == null)
                }
                check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
                check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
            }
            cleanupStep("host-activity") {
                activity?.let { current -> onMain { current.stopHost(); current.finish() } }
                instrumentation.waitForIdleSync()
            }
            cleanupStep("delete-own-host") {
                val host = AppWidgetHost(context, hostId)
                check(host.appWidgetIds.isEmpty())
                phase("before-own-host-delete")
                host.deleteHost()
                record.put("hostDeleteReturned", true)
                check(host.appWidgetIds.isEmpty())
                persist()
            }
            var helperCleaned = !helperPrepared
            cleanupStep("runtime-permission") {
                if (permissionTouched && runtimeGranted()) automation.revokeRuntimePermission(widgetPackage, Manifest.permission.READ_MEDIA_IMAGES)
                check(!runtimeGranted())
                record.put("runtimePermissionRestoredDenied", true)
            }
            cleanupStep("helper-preferences") {
                if (helperPrepared) {
                    val cleaned = helper("cleanup", null)
                    record.put("helperCleanup", bundleJson(cleaned))
                    check(cleaned.getBoolean("prefsAbsent"))
                }
                helperCleaned = true
            }
            if (helperCleaned) for (source in sources) cleanupStep("source-${source.index}") { deleteOwned(source) }
            cleanupStep("inventory") {
                val afterMedia = visibleIds()
                val afterFiles = inventory()
                record.put("profileMarkerAfter", profileMarker()).put("afterMedia", JSONArray(afterMedia)).put("afterFiles", JSONObject(afterFiles))
                    .put("afterWidgets", JSONArray(AppWidgetHost(context, hostId).appWidgetIds.toList()))
                check(afterMedia == beforeMedia && afterFiles == beforeFiles)
                check(AppWidgetHost(context, hostId).appWidgetIds.isEmpty())
                noMediaGrant()
                check(helperCleaned && !cleanupFailed)
                record.put("cleanupComplete", true)
            }
            record.put("phase", if (failure == null) "pass-cleaned" else "failed-preserved")
            cleanupStep("receipt") { persist() }
        }
        failure?.let { throw it }
        check(record.getBoolean("cleanupComplete"))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "WIDGET_RUNTIME $uuid PASS real-runtime-revoke/touch-empty cleanup-complete\n")
        })
    }

    /** Exact recovery for a retained fixture after Android killed its observer dependency. */
    @Test fun cleanupOwnedRuntimeFixtureAfterObserverDeath() {
        check(context.packageName == "com.ugallery.core.mediastore.test")
        noMediaGrant(); requireNoAdoptedIdentity(); check(!runtimeGranted())
        record = receipt.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        check(record.getString("uuid") == uuid && record.getString("package") == context.packageName)
        check(record.getInt("hostId") == hostId && record.getString("hostInventorySha256") == hostInventorySha)
        check(!record.getBoolean("cleanupComplete") && record.getString("phase") == "before-runtime-revoke")
        for (index in 0..1) {
            val entry = record.getJSONObject("source-$index")
            val uri = Uri.parse(entry.getString("uri"))
            check(uri == ContentUris.withAppendedId(collection, ContentUris.parseId(uri)))
            check(entry.getString("name") == "widget-runtime-$uuid-$index.png" && entry.getString("path") == relativePath)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            check(bytes.size == entry.getInt("size") && sha(bytes) == entry.getString("hash"))
            val source = OwnedImage(index, entry.getString("name"), bytes).also {
                it.uri = uri; it.anchored = entry.getJSONObject("published").toString()
            }
            check(read(source) == source.anchored); sources.add(source)
        }
        val host = AppWidgetHost(context, hostId)
        val id = record.getInt("widgetId")
        check(host.appWidgetIds.contentEquals(intArrayOf(id)))
        check(manager.getAppWidgetInfo(id)?.provider == provider)
        host.deleteAppWidgetId(id); check(host.appWidgetIds.isEmpty()); host.deleteHost()
        record.put("hostDeleteReturned", true)
        val cleaned = helper("cleanup", null)
        check(cleaned.getBoolean("prefsAbsent") && cleaned.getBoolean("cleanupComplete"))
        record.put("helperCleanup", bundleJson(cleaned))
        sources.forEach(::deleteOwned)
        val files = inventory()
        check(JSONObject(files).toString() == record.getJSONObject("beforeFiles").toString())
        check(visibleIds().isEmpty() && host.appWidgetIds.isEmpty() && !runtimeGranted())
        record.put("afterMedia", JSONArray()).put("afterWidgets", JSONArray()).put("afterFiles", JSONObject(files))
            .put("runtimePermissionRestoredDenied", true).put("cleanupComplete", true)
            .put("phase", "observer-death-cleaned").put("originalResult", "PROCESS_CRASHED")
        persist()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "EXACT_RUNTIME_CLEANUP $uuid COMPLETE original=PROCESS_CRASHED\n") })
    }

    private class OwnedImage(val index: Int, val name: String, val bytes: ByteArray) {
        var uri: Uri? = null
        var initial: JSONObject? = null
        var anchored: String? = null
    }

    private fun makeSource(index: Int, color: Int): OwnedImage {
        val bitmap = Bitmap.createBitmap(48, 40, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(color)
            // UUID-specific edge pixels preserve a large uniform decoded center.
            uuid.toByteArray().forEachIndexed { x, value -> bitmap.setPixel(x, 0, Color.rgb(value.toInt() and 255, index, 37)) }
            ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
        val source = OwnedImage(index, "widget-runtime-$uuid-$index.png", bytes)
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

    private fun profileMarker(): JSONObject {
        val file = File(context.filesDir, "profileInstalled")
        return JSONObject().put("exists", file.exists()).apply {
            if (file.exists()) { check(file.length() <= 1024); put("bytes", file.length()); put("sha256", sha(file.readBytes())) }
        }
    }

    private fun inventory(): Map<String, String> = buildMap {
        var bytes = 0L
        for (root in listOf(context.filesDir, prefsFile.parentFile!!)) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                if (file == receipt.baseFile || file.path == receipt.baseFile.path + ".bak") return@forEach
                // ProfileVerifier owns this reproducible compilation-status cache, not gallery data.
                // Preserve it and record its exact bytes separately, since installation changes it asynchronously.
                if (file == File(context.filesDir, "profileInstalled")) return@forEach
                bytes += file.length(); check(bytes <= 64L * 1024 * 1024) { "Pre-existing inventory too large" }
                put(file.relativeTo(File(context.applicationInfo.dataDir)).path, sha(file.readBytes()))
            }
        }
    }

    private fun runtimeGranted() = context.packageManager.checkPermission(Manifest.permission.READ_MEDIA_IMAGES, widgetPackage) == PackageManager.PERMISSION_GRANTED
    private fun alias(source: OwnedImage) = "content://media/external/images/media/" + ContentUris.parseId(requireNotNull(source.uri))
    private fun helper(method: String, extras: Bundle?): Bundle =
        requireNotNull(context.contentResolver.acquireUnstableContentProviderClient(helperUri)).use { client ->
            requireNotNull(client.call(method, uuid, extras))
        }
    private fun bundleJson(bundle: Bundle) = JSONObject().apply {
        for (key in bundle.keySet()) put(key, bundle.get(key))
    }

    private fun sample() = onMain { activity?.sample() }
    private fun colorOf(pixel: Int): String? = when {
        Color.red(pixel) > 220 && Color.blue(pixel) < 35 && Color.green(pixel) < 35 -> "red"
        Color.blue(pixel) > 220 && Color.red(pixel) < 35 && Color.green(pixel) < 35 -> "blue"
        else -> null
    }
    private fun WidgetRuntimeHostActivity.Sample.json() = JSONObject()
        .put("deliveries", deliveries).put("pixel", pixel).put("color", colorOf(pixel))
        .put("width", width).put("height", height).put("centerX", centerX).put("centerY", centerY)
        .put("clickable", clickable).put("description", description).put("imageInsideHost", imageInsideHost)
        .put("hasImage", hasImage).put("drawableCleared", drawableCleared).put("emptyVisible", emptyVisible).put("emptyText", emptyText)
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
