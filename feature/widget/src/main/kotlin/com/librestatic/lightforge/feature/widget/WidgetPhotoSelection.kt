package com.librestatic.lightforge.feature.widget

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory

/** Bounded persistent cache policy shared by production and JVM checks. */
internal object WidgetSelectionPolicy {
    const val QUERY_LIMIT = 100
    const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L
    private const val MAX_URI_LENGTH = 96
    private val ownedFormat = Regex("content://media/external/images/media/(0|[1-9][0-9]*)")

    fun mediaId(value: String): Long? {
        if (value.length > MAX_URI_LENGTH) return null
        return ownedFormat.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun cachedUris(raw: String?, cachedAt: Long, now: Long): List<String>? {
        if (raw == null || raw.length > QUERY_LIMIT * (MAX_URI_LENGTH + 1) ||
            cachedAt < 0 || now < cachedAt || now - cachedAt >= CACHE_MAX_AGE_MS) return null
        if (raw.isEmpty()) return null
        val values = raw.split('\n')
        if (values.size > QUERY_LIMIT || values.any { mediaId(it) == null } || values.distinct().size != values.size) return null
        return values
    }

    fun nextIndex(previous: Int, size: Int): Int? {
        if (size !in 1..QUERY_LIMIT) return null
        val current = previous.takeIf { it in 0 until size } ?: 0
        return (current + 1) % size
    }
}

/** Rotates accessible local images; cached addresses are never authorization to display them. */
class WidgetPhotoSelection(private val context: Context) {
    companion object {
        private const val TAG = "WidgetPhotoSelection"
        private const val PREFS_NAME = "lightforge_widget"
        private const val KEY_INDEX = "photo_index"
        private const val URIS_CACHE_KEY = "cached_uris"
        @Volatile private var database: GalleryDatabase? = null

        private fun database(context: Context): GalleryDatabase =
            database ?: synchronized(this) {
                database ?: GalleryDatabaseFactory.open(context.applicationContext).also { database = it }
            }
    }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getNextPhotoUri(): Uri? {
        val (uris, fromCache) = getCachedOrQuery()
        selectCurrent(uris)?.let { return it }
        discardUnavailableCache()
        if (!fromCache) return null
        // Exactly one fresh collection query after stale candidates; never loop refreshes.
        val result = selectCurrent(queryAndCache())
        if (result == null) discardUnavailableCache()
        return result
    }

    /** The app's own Archive hides items from Photos; the widget follows it, cache or not. */
    private fun archivedIds(): Set<Long> = try {
        database(context).libraryDao().archivedMediaStoreIds().toHashSet()
    } catch (failure: Exception) {
        Log.w(TAG, "Archive state is unavailable", failure)
        emptySet()
    }

    private fun selectCurrent(uris: List<Uri>): Uri? {
        val previous = try { prefs.getInt(KEY_INDEX, 0) } catch (_: ClassCastException) { 0 }
        val start = WidgetSelectionPolicy.nextIndex(previous, uris.size) ?: return null
        val archived = archivedIds()
        for (offset in uris.indices) {
            val index = (start + offset) % uris.size
            val uri = uris[index]
            if (WidgetSelectionPolicy.mediaId(uri.toString()) in archived) continue
            if (canReadCurrentPhoto(uri)) {
                prefs.edit().putInt(KEY_INDEX, index).apply()
                return uri
            }
        }
        return null
    }

    private fun discardUnavailableCache() {
        prefs.edit().remove(URIS_CACHE_KEY).remove(URIS_CACHE_KEY + "_time").remove(KEY_INDEX).apply()
    }

    private fun getCachedOrQuery(): Pair<List<Uri>, Boolean> {
        val cached = try {
            WidgetSelectionPolicy.cachedUris(prefs.getString(URIS_CACHE_KEY, null),
                prefs.getLong(URIS_CACHE_KEY + "_time", 0), System.currentTimeMillis())
        } catch (_: ClassCastException) { null }
        return if (cached != null) cached.map(Uri::parse) to true else queryAndCache() to false
    }

    private fun queryAndCache(): List<Uri> = queryMediaStore().also { uris ->
        prefs.edit().putString(URIS_CACHE_KEY, uris.joinToString("\n"))
            .putLong(URIS_CACHE_KEY + "_time", System.currentTimeMillis()).apply()
    }

    private fun excludedStates() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.MediaColumns.IS_TRASHED}=0 AND ${MediaStore.MediaColumns.IS_PENDING}=0")
    }

    private fun canReadCurrentPhoto(uri: Uri): Boolean {
        val expectedId = WidgetSelectionPolicy.mediaId(uri.toString()) ?: return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.IS_PENDING), excludedStates(), null)?.use { cursor ->
                    cursor.moveToFirst() && cursor.getLong(0) == expectedId && cursor.getInt(1) == 0 &&
                        cursor.getInt(2) == 0 && !cursor.moveToNext()
                } == true
            } == true
        } catch (failure: Exception) {
            Log.w(TAG, "Cached widget photo is no longer readable", failure)
            false
        }
    }

    private fun queryMediaStore(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = excludedStates().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.MediaColumns._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, WidgetSelectionPolicy.QUERY_LIMIT)
        }
        try {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { cursor ->
                var rows = 0
                while (rows < WidgetSelectionPolicy.QUERY_LIMIT && cursor.moveToNext()) {
                    rows++ // Also bound reading if a provider ignores the limit or repeats malformed IDs.
                    val id = cursor.getLong(0)
                    if (id >= 0) ContentUris.withAppendedId(collection, id).let { if (it !in uris) uris.add(it) }
                }
            }
        } catch (failure: Exception) {
            Log.w(TAG, "MediaStore query failed", failure)
        }
        return uris
    }

    fun clearCache() {
        prefs.edit().clear().apply()
    }
}
