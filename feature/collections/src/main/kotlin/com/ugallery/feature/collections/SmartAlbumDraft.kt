package com.ugallery.feature.collections

import androidx.compose.runtime.saveable.listSaver
import com.ugallery.core.data.SmartAlbumRule
import com.ugallery.core.database.SmartAlbumEntity
import com.ugallery.core.model.MediaKey
import java.time.ZoneId

/** Only small edit state is saveable; paged photos and bitmaps are never serialized. */
internal data class SmartAlbumDraft(
    val name: String = "",
    val topic: String? = null,
    val personId: String? = null,
    val personVersion: String? = null,
    val year: String = "",
    val zoneId: String = ZoneId.systemDefault().id,
    val favorites: Boolean = false,
    val excluded: List<MediaKey> = emptyList(),
    val albumId: String? = null,
    val revision: String? = null,
) {
    val validYear
        get() = year.isEmpty() || (year.all { it in '0'..'9' } && year.toIntOrNull() in 1..9998)

    val validName
        get() = runCatching { SmartAlbumRule.normalizeName(name) }.isSuccess

    fun rule(): SmartAlbumRule? =
        if (!validYear) null
        else SmartAlbumRule(topic, personId, personVersion, year.toIntOrNull(), zoneId, favorites)

    fun exclude(key: MediaKey) =
        if (key in excluded || excluded.size >= 200) this else copy(excluded = excluded + key)

    fun include(key: MediaKey) = copy(excluded = excluded - key)

    companion object {
        fun from(album: SmartAlbumEntity) =
            SmartAlbumDraft(
                album.name,
                album.topic,
                album.personClusterId,
                album.personAlgorithmVersion,
                album.year?.toString().orEmpty(),
                album.zoneId,
                album.favoritesOnly,
                albumId = album.albumId,
                revision = album.revision,
            )

        val Saver =
            listSaver<SmartAlbumDraft, Any>(
                save = {
                    listOf(
                        it.name,
                        it.topic.orEmpty(),
                        it.personId.orEmpty(),
                        it.personVersion.orEmpty(),
                        it.year,
                        it.zoneId,
                        it.favorites,
                        ArrayList(
                            it.excluded.flatMap { key ->
                                listOf(key.volumeName, key.mediaStoreId.toString())
                            }
                        ),
                        it.albumId.orEmpty(),
                        it.revision.orEmpty(),
                    )
                },
                restore = { a ->
                    SmartAlbumDraft(
                        a[0] as String,
                        (a[1] as String).ifEmpty { null },
                        (a[2] as String).ifEmpty { null },
                        (a[3] as String).ifEmpty { null },
                        a[4] as String,
                        a[5] as String,
                        a[6] as Boolean,
                        (a[7] as List<*>).chunked(2).map {
                            MediaKey(it[0] as String, (it[1] as String).toLong())
                        },
                        (a[8] as String).ifEmpty { null },
                        (a[9] as String).ifEmpty { null },
                    )
                },
            )
    }
}
