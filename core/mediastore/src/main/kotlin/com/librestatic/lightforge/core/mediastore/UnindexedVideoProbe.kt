package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.inspector.MediaExtractorCompat
import com.librestatic.lightforge.core.legacyvideo.LegacyExtractorsFactory
import com.librestatic.lightforge.core.model.MediaKind
import java.io.IOException

/**
 * MediaProvider leaves duration and size empty for containers its retriever cannot parse (FLV
 * above all), so tiles show no duration. Fill the gaps from Media3's extractors, which the player
 * uses too. Results are cached per row version so rescans do not reopen the same file.
 */
internal class UnindexedVideoProbe(private val resolver: ContentResolver) {
    fun complete(record: MediaStoreRecord): MediaStoreRecord {
        if (record.kind != MediaKind.Video) return record
        if (record.durationMillis > 0 && record.width > 0 && record.height > 0) return record
        val cacheKey = "${record.key.volumeName}/${record.key.mediaStoreId}@${record.dateModifiedSeconds}"
        val probed = cache[cacheKey] ?: (probe(record) ?: Probed.Empty).also { cache[cacheKey] = it }
        if (probed === Probed.Empty) return record
        return record.copy(
            durationMillis = record.durationMillis.takeIf { it > 0 } ?: probed.durationMillis,
            width = record.width.takeIf { it > 0 } ?: probed.width,
            height = record.height.takeIf { it > 0 } ?: probed.height,
        )
    }

    @OptIn(UnstableApi::class)
    private fun probe(record: MediaStoreRecord): Probed? {
        val extractor = MediaExtractorCompat(LegacyExtractorsFactory.create(), FileDataSource.Factory())
        return try {
            resolver.openFileDescriptor(MediaStoreUriFactory.uriFor(record.key), "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                var durationUs = 0L
                var width = 0
                var height = 0
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                    if (width <= 0 && format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                        height = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                    }
                }
                Probed((durationUs / 1_000).coerceAtLeast(0), width.coerceAtLeast(0), height.coerceAtLeast(0))
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            extractor.release()
        }
    }

    private data class Probed(val durationMillis: Long, val width: Int, val height: Int) {
        companion object {
            val Empty = Probed(0, 0, 0)
        }
    }

    private companion object {
        val cache: MutableMap<String, Probed> = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Probed>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Probed>): Boolean = size > 512
            },
        )
    }
}
