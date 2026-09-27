package com.librestatic.lightforge.feature.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Inter-UID test fixture only. No grants, MediaStore mutations or arbitrary provider IO. */
class GalleryWidgetPermissionFixtureProvider : ContentProvider() {
    private val lock = Any()
    private val owner get() = requireNotNull(context)
    private val recipient = "com.librestatic.lightforge.core.mediastore.test"
    private val permission = "com.librestatic.lightforge.feature.widget.test.permission.RUNTIME_FIXTURE"
    private val prefsName = "lightforge_widget"
    private val canonical = Regex("content://media/external/images/media/(0|[1-9][0-9]*)")
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Authenticate before dropping Binder identity: signature alone would admit other test apps.
        check(owner.packageName == "com.librestatic.lightforge.feature.widget.test")
        val callerUid = Binder.getCallingUid()
        val packages = owner.packageManager.getPackagesForUid(callerUid)?.toSet().orEmpty()
        check(packages == setOf(recipient) && callingPackage == recipient) { "Unexpected fixture caller" }
        check(owner.packageManager.checkSignatures(callerUid, Process.myUid()) == PackageManager.SIGNATURE_MATCH)
        check(owner.checkPermission(permission, Binder.getCallingPid(), callerUid) == PackageManager.PERMISSION_GRANTED)
        val uuid = requireNotNull(arg).also { require(UUID.fromString(it).toString() == it) }
        require(method in setOf("prepareCache", "probeRead", "cleanup"))
        val identity = Binder.clearCallingIdentity()
        return try {
            synchronized(lock) {
                when (method) {
                    "prepareCache" -> prepare(uuid, requireNotNull(extras))
                    "probeRead" -> probe(uuid, requireNotNull(extras))
                    else -> { require(extras == null || extras.isEmpty); cleanup(uuid) }
                }
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun prefsFile() = File(owner.applicationInfo.dataDir, "shared_prefs/$prefsName.xml")
    private fun prefsAbsent() = !prefsFile().exists() && !File(prefsFile().path + ".bak").exists()
    private fun receipt(uuid: String) = AtomicFile(File(owner.filesDir, "widget-runtime-$uuid.json"))
    private fun granted() = owner.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    private fun noOtherAccess() {
        for (p in listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.READ_EXTERNAL_STORAGE)) check(owner.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
    }
    private fun requireNoWidgets() {
        check(AppWidgetManager.getInstance(owner).getAppWidgetIds(ComponentName(owner, GalleryWidgetProvider::class.java)).isEmpty())
    }
    private fun readReceipt(uuid: String): JSONObject = receipt(uuid).openRead().use {
        check(it.available() <= 64 * 1024)
        JSONObject(it.readBytes().toString(Charsets.UTF_8))
    }.also {
        check(it.getString("fixtureUuid") == uuid && it.getString("package") == owner.packageName &&
            it.getString("sourceOwner") == recipient && it.getBoolean("prefsOriginallyAbsent"))
    }
    private fun write(uuid: String, value: JSONObject) {
        val file = receipt(uuid)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        check(file.openRead().use { it.readBytes() }.contentEquals(bytes))
        val fd = Os.open(file.baseFile.parentFile!!.path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun response(uuid: String, record: JSONObject) = Bundle().apply {
        putString("fixtureUuid", uuid); putString("phase", record.getString("phase"))
        putString("receiptName", receipt(uuid).baseFile.name)
        putBoolean("prefsAbsent", prefsAbsent()); putBoolean("runtimeGranted", granted())
        putBoolean("cleanupComplete", record.optBoolean("cleanupComplete", false))
        putInt("uid", Process.myUid()); putInt("pid", Process.myPid()); putString("package", owner.packageName)
    }

    private fun prepare(uuid: String, extras: Bundle): Bundle {
        require(extras.keySet() == setOf("uris", "sha256"))
        val uris = requireNotNull(extras.getStringArrayList("uris"))
        val hashes = requireNotNull(extras.getStringArrayList("sha256"))
        require(uris.size == 2 && uris.toSet().size == 2 && hashes.size == 2)
        uris.forEach { require(canonical.matches(it)) }
        hashes.forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
        check(granted()); noOtherAccess(); requireNoWidgets()
        check(!receipt(uuid).baseFile.exists() && !File(receipt(uuid).baseFile.path + ".bak").exists())
        // One live fixture only; a previous unresolved receipt is not another UUID's cleanup target.
        owner.filesDir.listFiles()?.filter { it.name.startsWith("widget-runtime-") && it.name.endsWith(".json") }
            ?.forEach { file -> check(JSONObject(file.readText()).optBoolean("cleanupComplete", false)) }
        val idle = awaitIdle()
        check(prefsAbsent())
        check(owner.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.isEmpty())
        val sources = JSONArray()
        for (index in 0..1) {
            val snapshot = snapshot(uuid, index, Uri.parse(uris[index]))
            check(snapshot.getString("sha256") == hashes[index])
            sources.put(snapshot)
        }
        val stamp = System.currentTimeMillis()
        val record = JSONObject().put("fixtureUuid", uuid).put("package", owner.packageName)
            .put("sourceOwner", recipient).put("prefsOriginallyAbsent", true)
            .put("sources", sources).put("seedTimestamp", stamp).put("workerPrepare", idle)
            .put("phase", "before-cache-commit").put("cleanupComplete", false)
        write(uuid, record)
        check(owner.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("cached_uris", uris.joinToString("\n")).putLong("cached_uris_time", stamp)
            .putInt("photo_index", 0).commit())
        validatePrefs(record)
        record.put("phase", "cache-prepared"); write(uuid, record)
        return response(uuid, record).apply {
            putStringArrayList("uris", ArrayList(uris)); putStringArrayList("sha256", ArrayList(hashes))
            putLong("seedTimestamp", stamp); putInt("photoIndex", 0)
        }
    }

    private fun probe(uuid: String, extras: Bundle): Bundle {
        require(extras.keySet() == setOf("uri"))
        val record = readReceipt(uuid)
        check(!record.getBoolean("cleanupComplete"))
        val uri = requireNotNull(extras.getString("uri"))
        val sources = record.getJSONArray("sources")
        val index = (0 until sources.length()).single { sources.getJSONObject(it).getString("uri") == uri }
        noOtherAccess()
        var readable = false
        var denied = false
        var matches = false
        var failureClass = ""
        try {
            // Executed after clearCallingIdentity in the widget process, never in the source owner UID.
            val actual = snapshot(uuid, index, Uri.parse(uri))
            readable = true
            matches = actual.toString() == sources.getJSONObject(index).toString()
        } catch (failure: SecurityException) { denied = true; failureClass = failure.javaClass.name }
        catch (failure: Exception) { failureClass = failure.javaClass.name }
        val probe = JSONObject().put("uri", uri).put("runtimeGranted", granted())
            .put("readable", readable).put("securityException", denied).put("sha256Match", matches)
            .put("failureClass", failureClass).put("uid", Process.myUid()).put("pid", Process.myPid())
        record.put("lastProbe", probe); write(uuid, record)
        return response(uuid, record).apply {
            putString("uri", uri); putBoolean("readable", readable); putBoolean("securityException", denied)
            putBoolean("sha256Match", matches); putString("failureClass", failureClass)
        }
    }

    private fun cleanup(uuid: String): Bundle {
        noOtherAccess()
        val record = readReceipt(uuid)
        requireNoWidgets()
        val idle = awaitIdle()
        record.put("workerCleanup", idle)
        if (record.getBoolean("cleanupComplete")) {
            check(prefsAbsent()); return response(uuid, record)
        }
        validatePrefs(record)
        record.put("phase", "before-cache-delete"); write(uuid, record)
        check(owner.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().commit())
        if (!prefsAbsent()) check(owner.deleteSharedPreferences(prefsName))
        check(prefsAbsent())
        record.put("phase", "cleanup-complete").put("cleanupComplete", true); write(uuid, record)
        return response(uuid, record)
    }

    private fun validatePrefs(record: JSONObject) {
        val all = owner.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all
        val sourceUris = (0 until record.getJSONArray("sources").length())
            .map { record.getJSONArray("sources").getJSONObject(it).getString("uri") }
        check(all.keys.all { it in setOf("cached_uris", "cached_uris_time", "photo_index") })
        if (all.isEmpty()) return // Real selector discards unavailable cache before fresh denied query.
        val text = all["cached_uris"] as? String
        check(text == sourceUris.joinToString("\n") || text == "") { "Changed cache retained" }
        val stamp = all["cached_uris_time"] as? Long
        check(stamp != null && stamp >= record.getLong("seedTimestamp") && stamp <= System.currentTimeMillis())
        val index = all["photo_index"]
        check(index == null || index is Int && index in 0..1)
    }

    private fun snapshot(uuid: String, index: Int, uri: Uri): JSONObject {
        require(canonical.matches(uri.toString()))
        val expectedName = "widget-runtime-$uuid-$index.png"
        val expectedPath = "Pictures/widget-runtime-$uuid/"
        val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path", "generation_added",
            "generation_modified", "is_pending", "is_trashed", "_size", "mime_type")
        fun metadata(): JSONObject = owner.contentResolver.query(uri, columns, Bundle(), null)!!.use { c ->
            check(c.moveToFirst())
            JSONObject().put("uri", uri.toString()).put("id", c.getLong(0)).put("owner", c.getString(1))
                .put("name", c.getString(2)).put("relativePath", c.getString(3)).put("generationAdded", c.getLong(4))
                .put("generationModified", c.getLong(5)).put("pending", c.getInt(6)).put("trashed", c.getInt(7))
                .put("bytes", c.getLong(8)).put("mimeType", c.getString(9)).also { check(!c.moveToNext()) }
        }
        // Opening first distinguishes true READ denial from a query which merely hides the row.
        owner.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val before = metadata()
            check(before.getLong("id") == ContentUris.parseId(uri) && before.getString("owner") == recipient &&
                before.getString("name") == expectedName && before.getString("relativePath") == expectedPath &&
                before.getInt("pending") == 0 && before.getInt("trashed") == 0 && before.getString("mimeType") == "image/png")
            val size = before.getLong("bytes")
            check(size in 1..65536)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    count += n; check(count <= size); digest.update(buffer, 0, n)
                }
            }
            check(count == size && before.toString() == metadata().toString())
            return before.put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    /** Read-only observer of actual singleton state; mismatch is an observer failure, not idle. */
    private fun awaitIdle(): String {
        val singleton = GalleryWidgetProvider::class.java.getDeclaredField("updates").apply { isAccessible = true }.get(null)
        fun field(name: String) = singleton.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val monitor = requireNotNull(field("lock").get(singleton))
        val draining = field("draining"); val active = field("active"); val pending = field("pending")
        val deadline = SystemClock.uptimeMillis() + 12_000
        var state: String
        while (true) {
            synchronized(monitor) {
                val d = draining.getBoolean(singleton); val a = active.get(singleton) != null; val p = pending.get(singleton) != null
                state = "draining=$d,active=$a,pending=$p"
                if (!d && !a && !p) return state
            }
            check(SystemClock.uptimeMillis() < deadline) { "Real widget dispatcher not idle: $state" }
            SystemClock.sleep(40)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException("call only")
    override fun getType(uri: Uri): String? = throw UnsupportedOperationException("call only")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("call only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("call only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("call only")
}
