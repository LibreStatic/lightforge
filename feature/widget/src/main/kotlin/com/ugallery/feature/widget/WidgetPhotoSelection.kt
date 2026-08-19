package com.ugallery.feature.widget

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Selects photos for the home-screen widget.
 * Excludes private/trash items, only queries accessible photos.
 * Rotates through results using a persisted index.
 * No network, no cloud, battery-reasonable (lightweight MediaStore query).
 */
class WidgetPhotoSelection(private val context: Context) {

    companion object {
        private const val TAG = "WidgetPhotoSelection"
        private const val PREFS_NAME = "ugallery_widget"
        private const val KEY_INDEX = "photo_index"
        private const val URIS_CACHE_KEY = "cached_uris"
        private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L // 30 minutes
        private const val QUERY_LIMIT = 100
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getNextPhotoUri(): Uri? {
        val uris = getCachedOrQuery()
        if (uris.isEmpty()) return null

        val index = prefs.getInt(KEY_INDEX, 0)
        val nextIndex = (index + 1) % uris.size
        prefs.edit().putInt(KEY_INDEX, nextIndex).apply()

        return uris[nextIndex]
    }

    private fun getCachedOrQuery(): List<Uri> {
        val cacheTime = prefs.getLong(URIS_CACHE_KEY + "_time", 0)
        val now = System.currentTimeMillis()

        if (now - cacheTime < CACHE_MAX_AGE_MS) {
            val cached = prefs.getString(URIS_CACHE_KEY, null)
            if (cached != null) {
                return cached.split("\n")
                    .filter { it.isNotEmpty() }
                    .map { Uri.parse(it) }
            }
        }

        return queryMediaStore().also { uris ->
            prefs.edit()
                .putString(URIS_CACHE_KEY, uris.joinToString("\n") { it.toString() })
                .putLong(URIS_CACHE_KEY + "_time", now)
                .apply()
        }
    }

    private fun queryMediaStore(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = MediaStore.Images.Media.IS_TRASHED + " = 0"
        val sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT " + QUERY_LIMIT

        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id
                    )
                    uris.add(uri)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
        }

        return uris
    }

    fun clearCache() {
        prefs.edit().clear().apply()
    }
}
