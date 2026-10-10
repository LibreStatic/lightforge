package com.librestatic.lightforge.core.thumbnail

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Minimal, defensive HEIF/ISOBMFF container parser. It exists because some platform HEIF parsers
 * reject valid files (e.g. iOS HDR gain-map HEICs); the HEVC payload itself decodes fine through
 * MediaCodec once the items are located here. Pure JVM so it can be unit tested.
 */

/** Random-access, read-only byte source. All reads either fill the range or throw [IOException]. */
interface HeifByteSource {
    val size: Long

    @Throws(IOException::class)
    fun readFully(position: Long, destination: ByteArray, offset: Int, length: Int)
}

class ByteArrayHeifSource(private val bytes: ByteArray) : HeifByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, destination: ByteArray, offset: Int, length: Int) {
        if (position < 0 || length < 0 || position + length > bytes.size) throw EOFException("Read out of range")
        System.arraycopy(bytes, position.toInt(), destination, offset, length)
    }
}

/** Positional reads; never moves the channel position and never reads more than requested. */
class ChannelHeifSource(private val channel: FileChannel) : HeifByteSource {
    override val size: Long get() = channel.size()

    override fun readFully(position: Long, destination: ByteArray, offset: Int, length: Int) {
        if (position < 0 || length < 0 || position + length > size) throw EOFException("Read out of range")
        val buffer = ByteBuffer.wrap(destination, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read <= 0) throw EOFException("Unexpected end of file")
            at += read
        }
    }
}

class HeifFormatException(message: String) : IOException(message)

/** HEVC decoder configuration (hvcC). */
class HevcConfig(val lengthSize: Int, val nalUnits: List<ByteArray>) {
    /** VPS/SPS/PPS joined with Annex-B start codes. */
    fun parameterSetsAnnexB(): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nalUnits) {
            out.write(START_CODE)
            out.write(nal)
        }
        return out.toByteArray()
    }

    companion object {
        internal val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

/** Converts length-prefixed NAL units (as stored in HEIF items) into an Annex-B byte stream. */
@Throws(IOException::class)
fun lengthPrefixedToAnnexB(data: ByteArray, lengthSize: Int): ByteArray {
    if (lengthSize !in 1..4) throw HeifFormatException("Invalid NAL length size")
    val out = ByteArrayOutputStream(data.size + 64)
    var pos = 0
    while (pos < data.size) {
        if (pos + lengthSize > data.size) throw HeifFormatException("Truncated NAL length")
        var length = 0L
        repeat(lengthSize) { length = (length shl 8) or (data[pos + it].toLong() and 0xFF) }
        pos += lengthSize
        if (length > data.size - pos) throw HeifFormatException("NAL exceeds item data")
        out.write(HevcConfig.START_CODE)
        out.write(data, pos, length.toInt())
        pos += length.toInt()
    }
    return out.toByteArray()
}

sealed interface HeifProperty {
    data class Ispe(val width: Int, val height: Int) : HeifProperty
    data class Hvcc(val config: HevcConfig) : HeifProperty
    data class Colour(
        val matrixCoefficients: Int,
        val fullRange: Boolean,
        val colourPrimaries: Int = 2,
        val transfer: Int = 2,
    ) : HeifProperty
    /** Raw ICC profile from a `colr` box of type `prof` or `rICC`. */
    class IccProfile(val bytes: ByteArray) : HeifProperty
    data class Irot(val ccwDegrees: Int) : HeifProperty
    /** [horizontalFlip] true mirrors left-right (imir axis 0), false mirrors top-bottom (axis 1). */
    data class Imir(val horizontalFlip: Boolean) : HeifProperty
    data class Clap(
        val widthNum: Long, val widthDen: Long,
        val heightNum: Long, val heightDen: Long,
        val horizOffNum: Long, val horizOffDen: Long,
        val vertOffNum: Long, val vertOffDen: Long,
    ) : HeifProperty
    data object Other : HeifProperty
}

class HeifExtent(val offset: Long, val length: Long)

class HeifItemLocation(val constructionMethod: Int, val baseOffset: Long, val extents: List<HeifExtent>)

class HeifItem(
    val id: Int,
    val type: String,
    val properties: List<HeifProperty>,
    internal val location: HeifItemLocation?,
) {
    val width: Int get() = properties.filterIsInstance<HeifProperty.Ispe>().firstOrNull()?.width ?: 0
    val height: Int get() = properties.filterIsInstance<HeifProperty.Ispe>().firstOrNull()?.height ?: 0
    val hevcConfig: HevcConfig? get() = properties.filterIsInstance<HeifProperty.Hvcc>().firstOrNull()?.config
    val colour: HeifProperty.Colour? get() = properties.filterIsInstance<HeifProperty.Colour>().firstOrNull()
    val iccProfile: ByteArray? get() = properties.filterIsInstance<HeifProperty.IccProfile>().firstOrNull()?.bytes

    /** Geometric transforms in association (= application) order. */
    val transforms: List<HeifProperty>
        get() = properties.filter { it is HeifProperty.Irot || it is HeifProperty.Imir || it is HeifProperty.Clap }
}

class HeifGrid(val rows: Int, val columns: Int, val outputWidth: Int, val outputHeight: Int)

/** A decodable image: one hvc1 item, or a grid with its ordered hvc1 tiles. */
class HeifImage(val item: HeifItem, val grid: HeifGrid?, val tiles: List<HeifItem>) {
    val id: Int get() = item.id
    val width: Int get() = grid?.outputWidth ?: item.width
    val height: Int get() = grid?.outputHeight ?: item.height
    val transforms: List<HeifProperty> get() = item.transforms
    val colour: HeifProperty.Colour? get() = item.colour ?: tiles.firstNotNullOfOrNull { it.colour }
    val iccProfile: ByteArray? get() = item.iccProfile ?: tiles.firstNotNullOfOrNull { it.iccProfile }
}

class HeifContainer private constructor(
    private val source: HeifByteSource,
    private val meta: ByteArray,
    private val idatRange: IntRange?,
    val brands: List<String>,
    private val items: Map<Int, HeifItem>,
    private val references: List<Reference>,
    val primaryItemId: Int,
) {
    private class Reference(val type: String, val from: Int, val to: List<Int>)

    /** The primary image, or null when it is not an hvc1 item / hvc1 grid (e.g. overlays). */
    val primary: HeifImage? by lazy { items[primaryItemId]?.let(::buildImage) }

    /** hvc1 thumbnails whose `thmb` reference points at the primary item. */
    val thumbnails: List<HeifImage> by lazy {
        references.filter { it.type == "thmb" && primaryItemId in it.to }
            .mapNotNull { items[it.from] }
            .filter { it.type == "hvc1" }
            .mapNotNull(::buildImage)
    }

    private fun buildImage(item: HeifItem): HeifImage? = when (item.type) {
        "hvc1" -> if (item.hevcConfig != null) HeifImage(item, null, listOf(item)) else null
        "grid" -> buildGrid(item)
        else -> null
    }

    private fun buildGrid(item: HeifItem): HeifImage? {
        val payload = try {
            readItem(item, MAX_GRID_PAYLOAD)
        } catch (_: IOException) {
            return null
        }
        if (payload.size < 8) return null
        val flags = payload[1].toInt() and 0xFF
        val rows = (payload[2].toInt() and 0xFF) + 1
        val columns = (payload[3].toInt() and 0xFF) + 1
        val wide = flags and 1 != 0
        if (payload.size < 4 + (if (wide) 8 else 4)) return null
        fun read(at: Int, n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or (payload[at + it].toLong() and 0xFF) }
            return v
        }
        val fieldSize = if (wide) 4 else 2
        val outW = read(4, fieldSize)
        val outH = read(4 + fieldSize, fieldSize)
        if (rows * columns > MAX_TILES || outW !in 1..MAX_DIMENSION || outH !in 1..MAX_DIMENSION) return null
        val tileIds = references.firstOrNull { it.type == "dimg" && it.from == item.id }?.to ?: return null
        if (tileIds.size != rows * columns) return null
        val tiles = tileIds.map { items[it]?.takeIf { tile -> tile.type == "hvc1" && tile.hevcConfig != null } ?: return null }
        return HeifImage(item, HeifGrid(rows, columns, outW.toInt(), outH.toInt()), tiles)
    }

    /** Reads the raw (length-prefixed) payload of an item. */
    @Throws(IOException::class)
    fun readItem(item: HeifItem, maxBytes: Int = MAX_ITEM_BYTES): ByteArray {
        val location = item.location ?: throw HeifFormatException("Item ${item.id} has no location")
        var total = 0L
        val resolved = location.extents.map { extent ->
            val start = location.baseOffset + extent.offset
            val limit = if (location.constructionMethod == 1) {
                (idatRange?.let { it.last - it.first + 1 } ?: 0).toLong()
            } else {
                source.size
            }
            val length = if (extent.length == 0L) limit - start else extent.length
            if (start < 0 || length < 0 || start > limit || length > limit - start) {
                throw HeifFormatException("Extent out of range")
            }
            total += length
            if (total > maxBytes) throw HeifFormatException("Item too large")
            start to length
        }
        val out = ByteArray(total.toInt())
        var written = 0
        for ((start, length) in resolved) {
            when (location.constructionMethod) {
                0 -> source.readFully(start, out, written, length.toInt())
                1 -> System.arraycopy(meta, idatRange!!.first + start.toInt(), out, written, length.toInt())
                else -> throw HeifFormatException("Unsupported construction method")
            }
            written += length.toInt()
        }
        return out
    }

    companion object {
        private const val MAX_META_BYTES = 16 * 1024 * 1024
        private const val MAX_ITEM_BYTES = 64 * 1024 * 1024
        private const val MAX_GRID_PAYLOAD = 64
        private const val MAX_ITEMS = 8192
        private const val MAX_PROPERTIES = 8192
        private const val MAX_EXTENTS = 1024
        private const val MAX_TILES = 1024
        private const val MAX_DIMENSION = 100_000L
        private const val MAX_BOXES = 100_000
        private const val MAX_NALS = 64
        private const val MAX_ICC_BYTES = 1024 * 1024

        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

        fun isHeifBrands(brands: List<String>): Boolean = brands.any { it in HEIF_BRANDS }

        /** Reads the `ftyp` brands (major first) or null when the source does not start with one. */
        fun readBrands(source: HeifByteSource): List<String>? = try {
            readFtyp(source)?.second
        } catch (_: IOException) {
            null
        }

        /** Returns null for non-HEIF input or when no usable `meta` exists; throws [IOException] if malformed. */
        @Throws(IOException::class)
        fun parse(source: HeifByteSource): HeifContainer? {
            try {
                return parseInternal(source)
            } catch (e: IOException) {
                throw e
            } catch (e: RuntimeException) {
                throw HeifFormatException("Malformed HEIF: ${e.javaClass.simpleName}")
            }
        }

        private fun readFtyp(source: HeifByteSource): Pair<Long, List<String>>? {
            if (source.size < 16) return null
            val header = ByteArray(8)
            source.readFully(0, header, 0, 8)
            if (String(header, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
            val size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
            if (size < 16 || size > 4096 || size > source.size) return null
            val body = ByteArray((size - 8).toInt())
            source.readFully(8, body, 0, body.size)
            val brands = mutableListOf(String(body, 0, 4, Charsets.ISO_8859_1))
            var at = 8
            while (at + 4 <= body.size) {
                brands += String(body, at, 4, Charsets.ISO_8859_1)
                at += 4
            }
            return size to brands
        }

        private fun parseInternal(source: HeifByteSource): HeifContainer? {
            val (ftypSize, brands) = readFtyp(source) ?: return null
            var position = ftypSize
            var metaBytes: ByteArray? = null
            var boxes = 0
            val header = ByteArray(16)
            while (position + 8 <= source.size && boxes++ < MAX_BOXES) {
                source.readFully(position, header, 0, 8)
                var size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                    ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                var headerSize = 8L
                if (size == 1L) {
                    if (position + 16 > source.size) throw HeifFormatException("Truncated box header")
                    source.readFully(position + 8, header, 8, 8)
                    size = 0
                    repeat(8) { size = (size shl 8) or (header[8 + it].toLong() and 0xFF) }
                    headerSize = 16
                } else if (size == 0L) {
                    size = source.size - position
                }
                if (size < headerSize || size < 0 || size > source.size - position) {
                    throw HeifFormatException("Invalid box size")
                }
                if (type == "meta") {
                    val payload = size - headerSize
                    if (payload > MAX_META_BYTES) throw HeifFormatException("meta too large")
                    metaBytes = ByteArray(payload.toInt()).also { source.readFully(position + headerSize, it, 0, it.size) }
                    break
                }
                position += size
            }
            return parseMeta(source, metaBytes ?: return null, brands)
        }

        private fun parseMeta(source: HeifByteSource, meta: ByteArray, brands: List<String>): HeifContainer {
            val root = Cursor(meta, 0, meta.size)
            root.skip(4) // FullBox version + flags
            var primaryId = -1
            val types = LinkedHashMap<Int, String>()
            var locations = emptyMap<Int, HeifItemLocation>()
            val references = mutableListOf<Reference>()
            var properties = emptyList<HeifProperty>()
            val associations = HashMap<Int, MutableList<Int>>()
            var idat: IntRange? = null

            forEachBox(root) { type, box ->
                when (type) {
                    "pitm" -> {
                        val version = box.u8()
                        box.skip(3)
                        primaryId = if (version == 0) box.u16() else box.u32Int()
                    }
                    "iinf" -> parseIinf(box, types)
                    "iloc" -> locations = parseIloc(box)
                    "iref" -> parseIref(box, references)
                    "iprp" -> {
                        forEachBox(box) { childType, child ->
                            when (childType) {
                                "ipco" -> properties = parseIpco(child)
                                "ipma" -> parseIpma(child, associations)
                            }
                        }
                    }
                    "idat" -> idat = box.pos until box.end
                }
            }
            if (primaryId < 0) throw HeifFormatException("Missing pitm")
            val items = LinkedHashMap<Int, HeifItem>()
            for ((id, type) in types) {
                val props = associations[id].orEmpty().mapNotNull { index -> properties.getOrNull(index - 1) }
                items[id] = HeifItem(id, type, props, locations[id])
            }
            return HeifContainer(source, meta, idat, brands, items, references, primaryId)
        }

        private fun parseIinf(box: Cursor, types: MutableMap<Int, String>) {
            val version = box.u8()
            box.skip(3)
            val count = if (version == 0) box.u16() else box.u32Int()
            if (count > MAX_ITEMS) throw HeifFormatException("Too many items")
            forEachBox(box) { type, infe ->
                if (type != "infe" || types.size >= MAX_ITEMS) return@forEachBox
                val infeVersion = infe.u8()
                infe.skip(3)
                if (infeVersion < 2) return@forEachBox
                val id = if (infeVersion == 2) infe.u16() else infe.u32Int()
                infe.skip(2) // protection index
                types[id] = infe.fourCc()
            }
        }

        private fun parseIloc(box: Cursor): Map<Int, HeifItemLocation> {
            val version = box.u8()
            box.skip(3)
            val sizes = box.u8()
            val offsetSize = sizes ushr 4
            val lengthSize = sizes and 0xF
            val sizes2 = box.u8()
            val baseSize = sizes2 ushr 4
            val indexSize = if (version == 1 || version == 2) sizes2 and 0xF else 0
            for (s in intArrayOf(offsetSize, lengthSize, baseSize, indexSize)) {
                if (s != 0 && s != 4 && s != 8) throw HeifFormatException("Invalid iloc field size")
            }
            val count = if (version < 2) box.u16() else box.u32Int()
            if (count > MAX_ITEMS) throw HeifFormatException("Too many iloc entries")
            val result = LinkedHashMap<Int, HeifItemLocation>()
            repeat(count) {
                val id = if (version < 2) box.u16() else box.u32Int()
                val method = if (version == 1 || version == 2) box.u16() and 0xF else 0
                box.skip(2) // data reference index
                val base = box.uint(baseSize)
                val extentCount = box.u16()
                if (extentCount > MAX_EXTENTS) throw HeifFormatException("Too many extents")
                val extents = ArrayList<HeifExtent>(extentCount)
                repeat(extentCount) {
                    if (indexSize > 0) box.skip(indexSize)
                    extents += HeifExtent(box.uint(offsetSize), box.uint(lengthSize))
                }
                result[id] = HeifItemLocation(method, base, extents)
            }
            return result
        }

        private fun parseIref(box: Cursor, out: MutableList<Reference>) {
            val version = box.u8()
            box.skip(3)
            forEachBox(box) { type, ref ->
                val from = if (version == 0) ref.u16() else ref.u32Int()
                val count = ref.u16()
                if (count > MAX_TILES) throw HeifFormatException("Too many references")
                val to = List(count) { if (version == 0) ref.u16() else ref.u32Int() }
                out += Reference(type, from, to)
            }
        }

        private fun parseIpco(box: Cursor): List<HeifProperty> {
            val list = ArrayList<HeifProperty>()
            forEachBox(box) { type, prop ->
                if (list.size >= MAX_PROPERTIES) throw HeifFormatException("Too many properties")
                list += parseProperty(type, prop)
            }
            return list
        }

        private fun parseProperty(type: String, box: Cursor): HeifProperty = try {
            when (type) {
                "ispe" -> {
                    box.skip(4)
                    val w = box.u32Long()
                    val h = box.u32Long()
                    if (w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION) HeifProperty.Ispe(w.toInt(), h.toInt())
                    else HeifProperty.Other
                }
                "irot" -> HeifProperty.Irot((box.u8() and 3) * 90)
                "imir" -> HeifProperty.Imir(horizontalFlip = (box.u8() and 1) == 0)
                "clap" -> HeifProperty.Clap(
                    box.u32Long(), box.u32Long(), box.u32Long(), box.u32Long(),
                    box.u32Long().toInt().toLong(), box.u32Long(), box.u32Long().toInt().toLong(), box.u32Long(),
                )
                "colr" -> when (box.fourCc()) {
                    "nclx" -> {
                        val primaries = box.u16()
                        val transfer = box.u16()
                        val matrix = box.u16()
                        HeifProperty.Colour(matrix, (box.u8() and 0x80) != 0, primaries, transfer)
                    }
                    "prof", "rICC" ->
                        if (box.remaining in 1..MAX_ICC_BYTES) HeifProperty.IccProfile(box.bytes(box.remaining))
                        else HeifProperty.Other
                    else -> HeifProperty.Other
                }
                "hvcC" -> parseHvcc(box)
                else -> HeifProperty.Other
            }
        } catch (_: HeifFormatException) {
            HeifProperty.Other
        }

        private fun parseHvcc(box: Cursor): HeifProperty {
            box.skip(21)
            val lengthSize = (box.u8() and 3) + 1
            val arrays = box.u8()
            val nals = ArrayList<ByteArray>()
            repeat(arrays) {
                box.skip(1)
                val count = box.u16()
                repeat(count) {
                    val length = box.u16()
                    if (nals.size >= MAX_NALS) throw HeifFormatException("Too many NAL units")
                    nals += box.bytes(length)
                }
            }
            return if (nals.isEmpty()) HeifProperty.Other else HeifProperty.Hvcc(HevcConfig(lengthSize, nals))
        }

        private fun parseIpma(box: Cursor, out: MutableMap<Int, MutableList<Int>>) {
            val version = box.u8()
            val flags = (box.u8() shl 16) or box.u16()
            val entries = box.u32Int()
            if (entries > MAX_ITEMS) throw HeifFormatException("Too many ipma entries")
            repeat(entries) {
                val id = if (version < 1) box.u16() else box.u32Int()
                val count = box.u8()
                val list = out.getOrPut(id) { mutableListOf() }
                repeat(count) {
                    // The essential bit is ignored: the decoder rejects anything it cannot handle.
                    list += if (flags and 1 != 0) box.u16() and 0x7FFF else box.u8() and 0x7F
                }
            }
        }

        private fun forEachBox(parent: Cursor, block: (String, Cursor) -> Unit) {
            var boxes = 0
            while (parent.remaining >= 8) {
                if (boxes++ > MAX_BOXES) throw HeifFormatException("Too many boxes")
                val start = parent.pos
                var size = parent.u32Long()
                val type = parent.fourCc()
                if (size == 1L) size = parent.u64()
                else if (size == 0L) size = (parent.end - start).toLong()
                val headerSize = parent.pos - start
                if (size < headerSize || size > parent.end - start) throw HeifFormatException("Invalid child box size")
                val end = start + size.toInt()
                if (type == "uuid") parent.skip(16)
                block(type, Cursor(parent.buf, parent.pos, end))
                parent.pos = end
            }
        }
    }

    /** Bounds-checked big-endian reader over a window of [buf]. */
    private class Cursor(val buf: ByteArray, var pos: Int, val end: Int) {
        val remaining: Int get() = end - pos

        fun skip(n: Int) {
            if (n < 0 || n > remaining) throw HeifFormatException("Read past end of box")
            pos += n
        }

        fun u8(): Int {
            if (remaining < 1) throw HeifFormatException("Read past end of box")
            return buf[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32Long(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun u32Int(): Int {
            val v = u32Long()
            if (v > Int.MAX_VALUE) throw HeifFormatException("Value too large")
            return v.toInt()
        }

        fun u64(): Long {
            val hi = u32Long()
            val lo = u32Long()
            if (hi > 0x7FFFFFFFL) throw HeifFormatException("Value too large")
            return (hi shl 32) or lo
        }

        fun uint(bytes: Int): Long = when (bytes) {
            0 -> 0L
            4 -> u32Long()
            8 -> u64()
            else -> throw HeifFormatException("Unsupported integer size")
        }

        fun fourCc(): String {
            if (remaining < 4) throw HeifFormatException("Read past end of box")
            return String(buf, pos, 4, Charsets.ISO_8859_1).also { pos += 4 }
        }

        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > remaining) throw HeifFormatException("Read past end of box")
            return buf.copyOfRange(pos, pos + n).also { pos += n }
        }
    }
}
