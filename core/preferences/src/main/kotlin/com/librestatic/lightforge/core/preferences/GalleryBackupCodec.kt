package com.librestatic.lightforge.core.preferences

import org.json.JSONArray
import org.json.JSONObject

data class FavoriteBackupRecord(
    val volumeName: String,
    val mediaStoreId: Long,
    val mediaKind: String,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateTakenMillis: Long?,
    val dateModifiedSeconds: Long,
    val relativePath: String?,
)

data class GalleryBackupPayload(
    val settings: JSONObject,
    val favorites: List<FavoriteBackupRecord>,
)

object GalleryBackupCodec {
    const val Format = "com.librestatic.lightforge.backup"
    const val CurrentVersion = 1

    fun encode(
        settings: JSONObject,
        favorites: List<FavoriteBackupRecord>,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): JSONObject = JSONObject().apply {
        put("format", Format)
        put("schemaVersion", CurrentVersion)
        put("createdAtMillis", createdAtMillis)
        put("settings", settings)
        put("favorites", JSONArray().apply {
            favorites.forEach { favorite ->
                put(JSONObject().apply {
                    put("volumeName", favorite.volumeName)
                    put("mediaStoreId", favorite.mediaStoreId)
                    put("mediaKind", favorite.mediaKind)
                    putNullable("displayName", favorite.displayName)
                    putNullable("mimeType", favorite.mimeType)
                    put("sizeBytes", favorite.sizeBytes)
                    putNullable("dateTakenMillis", favorite.dateTakenMillis)
                    put("dateModifiedSeconds", favorite.dateModifiedSeconds)
                    putNullable("relativePath", favorite.relativePath)
                })
            }
        })
    }

    fun decode(root: JSONObject): GalleryBackupPayload {
        // Version-1 settings-only exports remain importable.
        if (root.optString("format") != Format) return GalleryBackupPayload(root, emptyList())
        require(root.optInt("schemaVersion", 0) in 1..CurrentVersion) { "Unsupported backup version" }
        val settings = root.optJSONObject("settings") ?: error("Backup settings are missing")
        val encodedFavorites = root.optJSONArray("favorites") ?: JSONArray()
        val favorites = buildList {
            for (index in 0 until encodedFavorites.length()) {
                val item = encodedFavorites.optJSONObject(index) ?: continue
                val kind = item.optString("mediaKind")
                if (kind != "image" && kind != "video") continue
                val volume = item.optString("volumeName")
                val id = item.optLong("mediaStoreId", -1L)
                val size = item.optLong("sizeBytes", -1L)
                if (volume.isBlank() || id < 0L || size < 0L) continue
                add(
                    FavoriteBackupRecord(
                        volumeName = volume,
                        mediaStoreId = id,
                        mediaKind = kind,
                        displayName = item.nullableString("displayName"),
                        mimeType = item.nullableString("mimeType"),
                        sizeBytes = size,
                        dateTakenMillis = item.nullableLong("dateTakenMillis"),
                        dateModifiedSeconds = item.optLong("dateModifiedSeconds", 0L),
                        relativePath = item.nullableString("relativePath"),
                    ),
                )
            }
        }.distinctBy { Triple(it.volumeName, it.mediaStoreId, it.mediaKind) }
        return GalleryBackupPayload(settings, favorites)
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)
}
