package com.librestatic.lightforge.feature.places

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import org.json.JSONObject

data class OfflineMapInspection(
    val format: OfflineMapFormat,
    val bounds: PlaceBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val schema: String,
    val attribution: String,
)

/**
 * Read only, after a bounded private copy. No SQL, file paths or styles are executed from metadata.
 */
object OfflineMapValidator {
    const val MaxMetadata = 4 * 1024 * 1024

    fun inspect(file: File, cancellation: CancellationSignal? = null): OfflineMapInspection {
        cancellation?.throwIfCanceled()
        require(file.isFile && file.length() in 127..OfflineMapCatalog.MaxPackageBytes) { "FORMAT" }
        RandomAccessFile(file, "r").use { f ->
            val magic = ByteArray(16)
            f.readFully(magic)
            return when {
                magic.copyOfRange(0, 7).toString(Charsets.US_ASCII) == "PMTiles" -> pmtiles(f)
                magic.toString(Charsets.US_ASCII) == "SQLite format 3\u0000" ->
                    mbtiles(file, cancellation)
                else -> error("FORMAT")
            }
        }
    }

    private fun expand(data: ByteArray, compression: Int, cap: Int = MaxMetadata): ByteArray {
        require(compression == 1 || compression == 2) { "COMPRESSION" }
        val input =
            if (compression == 2) GZIPInputStream(ByteArrayInputStream(data))
            else ByteArrayInputStream(data)
        return input.use {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                require(result.size() + n <= cap) { "LIMIT" }
                result.write(buffer, 0, n)
            }
            result.toByteArray()
        }
    }

    private fun read(f: RandomAccessFile, offset: Long, length: Long, cap: Int): ByteArray {
        require(offset >= 0 && length in 1..cap.toLong() && offset <= f.length() - length) {
            "BOUNDS"
        }
        f.seek(offset)
        return ByteArray(length.toInt()).also(f::readFully)
    }

    private fun pmtiles(f: RandomAccessFile): OfflineMapInspection {
        f.seek(0)
        val h = ByteArray(127)
        f.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        require(h[7].toInt() == 3) { "VERSION" }
        val root = b.getLong(8)
        val rootBytes = b.getLong(16)
        val meta = b.getLong(24)
        val metaBytes = b.getLong(32)
        val leaves = b.getLong(40)
        val leavesBytes = b.getLong(48)
        val tiles = b.getLong(56)
        val tileBytes = b.getLong(64)
        listOf(root to rootBytes, meta to metaBytes, leaves to leavesBytes, tiles to tileBytes)
            .forEach { (o, n) -> require(o >= 127 && n >= 0 && o <= f.length() - n) { "BOUNDS" } }
        require(b.getLong(72) > 0 && b.getLong(88) > 0 && tileBytes > 0) { "EMPTY" }
        val compression = h[97].toInt()
        val tileCompression = h[98].toInt()
        val kind = h[99].toInt()
        require(kind in 1..4) { "FORMAT" }
        val min = h[100].toInt()
        val max = h[101].toInt()
        require(min in 0..24 && max in min..24) { "ZOOM" }
        val bounds =
            PlaceBounds(
                b.getInt(102) / 1e7,
                b.getInt(106) / 1e7,
                b.getInt(110) / 1e7,
                b.getInt(114) / 1e7,
            )
        val metadata =
            JSONObject(
                expand(read(f, meta, metaBytes, MaxMetadata), compression).toString(Charsets.UTF_8)
            )
        // Validate the first referenced data tile, following a leaf when the root only contains
        // pointers.
        var directory = expand(read(f, root, rootBytes, MaxMetadata), compression)
        var found = false
        repeat(4) {
            if (!found) {
                val entry = firstEntry(directory)
                if (entry.second == 0L)
                    directory =
                        expand(
                            read(f, leaves + entry.third.first, entry.third.second, MaxMetadata),
                            compression,
                        )
                else {
                    require(
                        entry.third.first >= 0 &&
                            entry.third.second <= tileBytes - entry.third.first
                    ) {
                        "BOUNDS"
                    }
                    val raw =
                        read(f, tiles + entry.third.first, entry.third.second, 16 * 1024 * 1024)
                    validateTile(expand(raw, tileCompression, 16 * 1024 * 1024), kind == 1)
                    found = true
                }
            }
        }
        require(found) { "DIRECTORY" }
        return OfflineMapInspection(
            if (kind == 1) OfflineMapFormat.PMTilesVector else OfflineMapFormat.PMTilesRaster,
            bounds,
            min,
            max,
            if (kind == 1) schema(metadata) else "raster",
            attribution(metadata),
        )
    }

    /** PMTiles directory columns are delta IDs, run lengths, byte lengths and offset+1. */
    private fun firstEntry(bytes: ByteArray): Triple<Long, Long, Pair<Long, Long>> {
        var pos = 0
        fun number(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(pos < bytes.size && shift < 63) { "DIRECTORY" }
                val c = bytes[pos++].toInt() and 255
                value = value or ((c and 127).toLong() shl shift)
                if (c and 128 == 0) return value
                shift += 7
            }
        }
        val count = number()
        require(count in 1..100000) { "DIRECTORY" }
        var id = 0L
        var run = 0L
        var length = 0L
        var offset = 0L
        repeat(count.toInt()) {
            val v = number()
            if (it == 0) id = v
        }
        repeat(count.toInt()) {
            val v = number()
            if (it == 0) run = v
        }
        repeat(count.toInt()) {
            val v = number()
            if (it == 0) length = v
        }
        repeat(count.toInt()) {
            val v = number()
            if (it == 0) {
                require(v > 0)
                offset = v - 1
            }
        }
        require(pos == bytes.size && length > 0) { "DIRECTORY" }
        return Triple(id, run, offset to length)
    }

    private fun mbtiles(file: File, cancellation: CancellationSignal?): OfflineMapInspection {
        SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            .use { db ->
                db.rawQuery("PRAGMA quick_check(1)", null, cancellation).use {
                    require(it.moveToFirst() && it.getString(0) == "ok") { "CORRUPT" }
                }
                val metadata = JSONObject()
                var metadataBytes = 0L
                db.rawQuery("SELECT name,value FROM metadata LIMIT 257", null, cancellation).use { c
                    ->
                    require(c.count <= 256) { "LIMIT" }
                    while (c.moveToNext()) {
                        val k = c.getString(0)
                        val v = c.getString(1)
                        require(k.length <= 256 && v.length <= MaxMetadata) { "LIMIT" }
                        metadataBytes +=
                            k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size
                        require(metadataBytes <= MaxMetadata) { "LIMIT" }
                        metadata.put(k, v)
                    }
                }
                val fmt = metadata.optString("format")
                val vector = fmt == "pbf"
                require(vector || fmt in listOf("png", "jpg", "jpeg", "webp")) { "FORMAT" }
                var min = 0
                var max = 0
                db.rawQuery("SELECT MIN(zoom_level),MAX(zoom_level) FROM tiles", null, cancellation)
                    .use {
                        require(it.moveToFirst() && !it.isNull(0)) { "EMPTY" }
                        min = it.getInt(0)
                        max = it.getInt(1)
                    }
                require(min in 0..24 && max in min..24) { "ZOOM" }
                db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null, cancellation).use { c ->
                    require(c.moveToFirst())
                    val data = c.getBlob(0)
                    require(data.size in 1..16 * 1024 * 1024)
                    validateTile(
                        if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte())
                            expand(data, 2, 16 * 1024 * 1024)
                        else data,
                        vector,
                    )
                }
                val bounds = metadata.optString("bounds").split(',').map { it.toDouble() }
                require(bounds.size == 4)
                val layers = if (vector) JSONObject(metadata.getString("json")) else metadata
                return OfflineMapInspection(
                    if (vector) OfflineMapFormat.MBTilesVector else OfflineMapFormat.MBTilesRaster,
                    PlaceBounds(bounds[0], bounds[1], bounds[2], bounds[3]),
                    min,
                    max,
                    if (vector) schema(layers) else "raster",
                    attribution(metadata),
                )
            }
    }

    private fun schema(metadata: JSONObject): String {
        val layers = metadata.optJSONArray("vector_layers") ?: error("SCHEMA")
        require(layers.length() in 1..256) { "SCHEMA" }
        val names =
            (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }.toSet()
        return when {
            names.containsAll(setOf("water", "roads", "places")) -> "protomaps-v4"
            names.containsAll(setOf("water", "transportation", "place")) -> "openmaptiles"
            else -> error("SCHEMA")
        }
    }

    private fun attribution(metadata: JSONObject): String =
        metadata.optString("attribution").take(4096).ifBlank {
            "Map attribution not supplied; review source license"
        }

    private fun validateTile(bytes: ByteArray, vector: Boolean) {
        require(bytes.isNotEmpty()) { "TILE" }
        if (vector) {
            require(bytes[0].toInt() and 7 in 0..5) { "TILE" }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth in 1..4096 && options.outHeight in 1..4096) { "TILE" }
        }
    }
}
