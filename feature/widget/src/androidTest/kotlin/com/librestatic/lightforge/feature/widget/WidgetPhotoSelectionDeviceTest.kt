package com.librestatic.lightforge.feature.widget

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real owner-only MediaStore reads. A failed fixture is retained for exact independent readback. */
@RunWith(AndroidJUnit4::class)
class WidgetPhotoSelectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "com.librestatic.lightforge.feature.widget.test")
    }
    private val id get() = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
        require(UUID.fromString(it).toString() == it)
    }
    private val prefsName get() = "lightforge_widget_fixture_$id"
    private val name get() = "widget-selection-$id.png"
    private val relativePath get() = "Pictures/widget-selection-$id/"
    private val receipt get() = AtomicFile(File(context.filesDir, "widget-selection-$id.json"))
    private val collection get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val prefsContext get() = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "lightforge_widget")
            return context.getSharedPreferences(prefsName, mode)
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun marker(value: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "WIDGET_SELECTION $id $value\n")
    })
    private fun read(): JSONObject = receipt.openRead().use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8))
    }.also {
        check(it.getString("uuid") == id && it.getString("package") == context.packageName)
        check(it.getString("name") == name && it.getString("relativePath") == relativePath)
    }
    private fun write(record: JSONObject) {
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        val output = receipt.startWrite()
        try { output.write(bytes); output.fd.sync(); receipt.finishWrite(output) }
        catch (failure: Throwable) { receipt.failWrite(output); throw failure }
        check(receipt.openRead().use { it.readBytes() }.contentEquals(bytes))
        val fd = Os.open(requireNotNull(receipt.baseFile.parentFile).path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
    private data class Source(val uri: String, val mediaId: Long, val owner: String, val name: String,
        val path: String, val added: Long, val modified: Long, val pending: Long, val trashed: Long,
        val size: Long, val hash: String) {
        fun json() = JSONObject().put("uri",uri).put("mediaId",mediaId).put("owner",owner)
            .put("name",name).put("path",path).put("added",added).put("modified",modified)
            .put("pending",pending).put("trashed",trashed).put("size",size).put("hash",hash)
    }
    private fun source(record: JSONObject) = Source(record.getString("uri"),record.getLong("mediaId"),
        record.getString("owner"),record.getString("name"),record.getString("path"),
        record.getLong("added"),record.getLong("modified"),record.getLong("pending"),
        record.getLong("trashed"),record.getLong("size"),record.getString("hash"))
    private fun includeOwnedStates() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    private fun query(uri: Uri): Source? {
        check(uri.toString() == ContentUris.withAppendedId(collection, ContentUris.parseId(uri)).toString())
        val columns = arrayOf("_id","owner_package_name","_display_name","relative_path",
            "generation_added","generation_modified","is_pending","is_trashed","_size")
        val row = context.contentResolver.query(uri,columns,includeOwnedStates(),null)!!.use { cursor ->
            if (!cursor.moveToFirst()) null else Source(uri.toString(),cursor.getLong(0),cursor.getString(1),
                cursor.getString(2),cursor.getString(3),cursor.getLong(4),cursor.getLong(5),
                cursor.getLong(6),cursor.getLong(7),if(cursor.isNull(8)) -1L else cursor.getLong(8),"").also { check(!cursor.moveToNext()) }
        } ?: return null
        check(row.owner == context.packageName && row.name == name && row.path == relativePath)
        check(row.mediaId == ContentUris.parseId(uri) && row.added >= 0 && row.modified >= 0)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        check(bytes.size.toLong() == row.size || (row.pending == 1L && row.size == -1L))
        // Recheck metadata after the exact-URI byte read, without recursively hashing.
        context.contentResolver.query(uri,columns,includeOwnedStates(),null)!!.use { cursor ->
            check(cursor.moveToFirst())
            check(cursor.getLong(0)==row.mediaId && cursor.getString(1)==row.owner && cursor.getString(2)==row.name &&
                cursor.getString(3)==row.path && cursor.getLong(4)==row.added && cursor.getLong(5)==row.modified &&
                cursor.getLong(6)==row.pending && cursor.getLong(7)==row.trashed &&
                (if(cursor.isNull(8)) -1L else cursor.getLong(8))==row.size)
            check(!cursor.moveToNext())
        }
        return row.copy(hash=sha(bytes))
    }
    private fun cas(expected: Source) = includeOwnedStates().apply {
        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
            "_id=? AND owner_package_name=? AND _display_name=? AND relative_path=? AND " +
                "generation_added=? AND generation_modified=? AND is_pending=? AND is_trashed=? AND " +
                if (expected.size == -1L) "_size IS NULL" else "_size=?")
        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(expected.mediaId.toString(),
            expected.owner,expected.name,expected.path,expected.added.toString(),expected.modified.toString(),
            expected.pending.toString(),expected.trashed.toString()) +
            if (expected.size == -1L) emptyArray() else arrayOf(expected.size.toString()))
    }
    private fun requireNoBroadGrant() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        for (permission in permissions) check(context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
        if (Build.VERSION.SDK_INT >= 34) check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED)
    }

    @Test fun ownedPhotoColdQueryAndCachedTrashAreRevalidated() {
        requireNoBroadGrant()
        check(!receipt.baseFile.exists() && !File(receipt.baseFile.path+".bak").exists())
        check(context.getSharedPreferences(prefsName,Context.MODE_PRIVATE).all.isEmpty())
        val pixels = Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)
        val bytes = try { pixels.eraseColor(Color.CYAN); ByteArrayOutputStream().use {
            check(pixels.compress(Bitmap.CompressFormat.PNG,100,it)); it.toByteArray()
        } } finally { pixels.recycle() }
        var record = JSONObject().put("uuid",id).put("package",context.packageName).put("name",name)
            .put("relativePath",relativePath).put("expectedHash",sha(bytes)).put("expectedSize",bytes.size)
            .put("phase","before-insert").put("cleanupComplete",false)
        write(record)
        val uri = requireNotNull(context.contentResolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.RELATIVE_PATH,relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png");put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        record.put("uri",uri.toString()).put("phase","before-write");write(record)
        context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) }
        val pending = requireNotNull(query(uri));check(pending.hash==sha(bytes) && pending.pending==1L)
        record.put("source",pending.json()).put("phase","pending");write(record)
        val selector = WidgetPhotoSelection(prefsContext)
        val beforePublish = selector.getNextPhotoUri()
        record.put("pendingUri",beforePublish?.toString() ?: JSONObject.NULL).put("phase","pending-observed");write(record)
        marker("PENDING ${beforePublish ?: "null"}")
        assertNull("Pending source must never be selected", beforePublish)
        record.put("phase","before-publish");write(record)
        check(context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},cas(pending))==1)
        val published = requireNotNull(query(uri));check(published.hash==pending.hash && published.added==pending.added && published.pending==0L && published.trashed==0L)
        record.put("source",published.json()).put("published",published.json()).put("phase","published");write(record)
        // With no broad or selected grants, only our own visible rows may participate.
        val visible = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),
            "is_trashed=0 AND is_pending=0",null,"date_added DESC")!!.use { cursor ->
            buildList { while(cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        check(visible == listOf(published.mediaId)) { "Other visible own fixtures retained; no adoption" }
        val expected = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,published.mediaId)
        val cold = selector.getNextPhotoUri()
        record.put("coldUri",cold?.toString() ?: JSONObject.NULL).put("phase","cold-observed");write(record)
        marker("COLD ${cold ?: "null"}")
        assertEquals("Cold production query must return the accessible owned photo",expected,cold)
        val hot = selector.getNextPhotoUri()
        record.put("hotUri",hot?.toString() ?: JSONObject.NULL).put("phase","hot-observed");write(record)
        assertEquals(expected,hot)
        check(query(uri)==published)
        record.put("phase","before-trash");write(record)
        check(context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_TRASHED,1)},cas(published))==1)
        val trashed = requireNotNull(query(uri));check(trashed.hash==published.hash && trashed.added==published.added && trashed.trashed==1L)
        record.put("source",trashed.json()).put("trashed",trashed.json()).put("phase","trashed");write(record)
        val cachedAfterTrash = selector.getNextPhotoUri()
        record.put("cachedAfterTrash",cachedAfterTrash?.toString() ?: JSONObject.NULL).put("phase","cached-trash-observed");write(record)
        marker("CACHED_AFTER_TRASH ${cachedAfterTrash ?: "null"}")
        assertNull("Cached URI must be revalidated after real owned trash",cachedAfterTrash)
        check(query(uri)==trashed);requireNoBroadGrant()
        record.put("verified",true);write(record)
        cleanupOwnedSelectionFixture()
        marker("PASS")
    }

    /** Resume only a byte-verified pending write intent; never adopt a published or changed source. */
    @Test fun anchorAndCleanupPendingWrite() {
        val record = read()
        check(record.getString("phase") == "before-write" && !record.has("source") && !record.getBoolean("cleanupComplete"))
        val uri = Uri.parse(record.getString("uri"))
        val current = requireNotNull(query(uri))
        check(current.pending == 1L && current.trashed == 0L)
        check(current.hash == record.getString("expectedHash"))
        check(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size } == record.getInt("expectedSize"))
        record.put("source", current.json()).put("phase", "anchored-pending-cleanup"); write(record)
        cleanupOwnedSelectionFixture()
    }

    /** Explicit recovery of this UUID only. Failure never adopts new metadata or erases evidence. */
    @Test fun cleanupOwnedSelectionFixture() {
        val record = read()
        check(!record.getBoolean("cleanupComplete"))
        check(record.has("source")) { "Unanchored insert/write retained for coordinator inspection" }
        val expected = source(record.getJSONObject("source"))
        check(expected.uri == record.getString("uri") && expected.hash == record.getString("expectedHash"))
        check(expected.owner==context.packageName && expected.name==name && expected.path==relativePath)
        val uri = Uri.parse(expected.uri)
        val current = query(uri)
        if (current != null) {
            check(current == expected) { "Changed source retained" }
            record.put("phase","before-cleanup-delete");write(record)
            check(context.contentResolver.delete(uri,cas(expected))==1)
        }
        check(query(uri)==null)
        // Drain this preference object's prior apply writes before deleting only its UUID file.
        check(context.getSharedPreferences(prefsName,Context.MODE_PRIVATE).edit().commit())
        check(context.deleteSharedPreferences(prefsName))
        val prefsFile = File(context.applicationInfo.dataDir,"shared_prefs/$prefsName.xml")
        check(!prefsFile.exists() && !File(prefsFile.path+".bak").exists())
        record.put("phase","cleanup-complete").put("cleanupComplete",true);write(record)
        marker("CLEANUP_COMPLETE")
    }
}
