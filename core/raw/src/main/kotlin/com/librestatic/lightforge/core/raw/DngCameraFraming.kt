package com.librestatic.lightforge.core.raw

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The region of the sensor image a DNG says the photographer framed (DefaultCropOrigin and
 * DefaultCropSize). The file still holds the whole [fullWidth] x [fullHeight] image, so anyone who
 * receives the original can recover what lies outside the frame.
 */
data class DngCameraFraming(
    val fullWidth: Int,
    val fullHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
) {
    /**
     * True when the crop hides more than 1% of the width or height. Cameras and DngCreator keep a
     * few pixels of margin for demosaicing, which is not a framing decision.
     */
    val isMeaningful: Boolean
        get() = (fullWidth - cropWidth) * 100L > fullWidth.toLong() ||
            (fullHeight - cropHeight) * 100L > fullHeight.toLong()
}

/** Bounded random access, so the parser never needs the whole file in memory. */
interface DngByteSource {
    val size: Long

    /** Exactly [length] bytes at [offset], or null when that range is not entirely inside the source. */
    fun read(offset: Long, length: Int): ByteArray?
}

class ByteArrayDngSource(private val bytes: ByteArray) : DngByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

class FileChannelDngSource(private val channel: FileChannel, override val size: Long) : DngByteSource {
    override fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count <= 0) return null
            position += count
        }
        return buffer.array()
    }
}

/** Reads DNG framing from a content URI with a handful of small seeks; never reads the whole file. */
object DngCameraFramingReader {
    suspend fun read(resolver: ContentResolver, uri: Uri): DngCameraFraming? = withContext(Dispatchers.IO) {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return@withContext null
        descriptor.use { pfd -> readFrom(pfd, uri) }
    }

    private fun readFrom(pfd: ParcelFileDescriptor, uri: Uri): DngCameraFraming? {
        val size = pfd.statSize
        if (size <= 0) return null
        val key = "$uri#$size"
        cache.get(key)?.let { return it.value }
        // The descriptor stays owned by the caller; the channel is never closed on its own.
        val framing = runCatching {
            parseDngCameraFraming(FileChannelDngSource(FileInputStream(pfd.fileDescriptor).channel, size))
        }.getOrNull()
        cache.put(key, CachedFraming(framing))
        return framing
    }

    private class CachedFraming(val value: DngCameraFraming?)

    private val cache = object : LinkedHashMap<String, CachedFraming>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFraming>?) = size > 64
    }.let(java.util.Collections::synchronizedMap)
}

private const val TagNewSubfileType = 254
private const val TagImageWidth = 256
private const val TagImageLength = 257
private const val TagPhotometric = 262
private const val TagSubIfds = 330
private const val TagDngVersion = 50706
private const val TagDefaultCropOrigin = 50719
private const val TagDefaultCropSize = 50720

private const val PhotometricCfa = 32803L
private const val PhotometricLinearRaw = 34892L

private const val MaxIfds = 16
private const val MaxDepth = 3
private const val MaxEntries = 2_048
private const val MaxValues = 16
private const val MaxDimension = 1_000_000L

private class IfdSummary(
    val newSubfileType: Long,
    val photometric: Long?,
    val width: Long?,
    val height: Long?,
    val cropOrigin: List<Long>?,
    val cropSize: List<Long>?,
    val hasDngVersion: Boolean,
    val subIfds: List<Long>,
    val next: Long,
)

/** Returns null for anything that is not a well-formed DNG; it never throws on bad data. */
fun parseDngCameraFraming(source: DngByteSource): DngCameraFraming? = try {
    parse(source)
} catch (_: RuntimeException) {
    null
}

private fun parse(source: DngByteSource): DngCameraFraming? {
    val header = source.read(0, 8) ?: return null
    val order = when {
        header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
        header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
        else -> return null
    }
    val headerBuffer = ByteBuffer.wrap(header).order(order)
    if (headerBuffer.getShort(2).toInt() and 0xFFFF != 42) return null
    val firstIfd = headerBuffer.getInt(4).toLong() and 0xFFFFFFFFL

    val ifds = ArrayList<IfdSummary>()
    val visited = HashSet<Long>()
    // IFD0 and its chain first, then SubIFDs breadth-first; depth 0 is the top-level chain.
    var level = listOf(firstIfd)
    var depth = 0
    var ifd0: IfdSummary? = null
    while (level.isNotEmpty() && depth <= MaxDepth && ifds.size < MaxIfds) {
        val nextLevel = ArrayList<Long>()
        for (start in level) {
            var offset = start
            while (offset != 0L && ifds.size < MaxIfds && visited.add(offset)) {
                val summary = readIfd(source, order, offset) ?: break
                if (ifd0 == null) ifd0 = summary
                ifds += summary
                nextLevel += summary.subIfds
                offset = if (depth == 0) summary.next else 0L
            }
        }
        level = nextLevel
        depth++
    }
    val first = ifd0 ?: return null
    if (!first.hasDngVersion) return null

    val main = ifds.firstOrNull {
        it.newSubfileType == 0L && (it.photometric == PhotometricCfa || it.photometric == PhotometricLinearRaw)
    } ?: ifds.filter { it.newSubfileType == 0L && it.width != null && it.height != null }
        .maxByOrNull { it.width!! * it.height!! }
        ?: return null
    val width = main.width ?: first.width ?: return null
    val height = main.height ?: first.height ?: return null
    if (width !in 1..MaxDimension || height !in 1..MaxDimension) return null

    val origin = main.cropOrigin ?: first.cropOrigin
    val size = main.cropSize ?: first.cropSize
    var left = 0L
    var top = 0L
    var cropWidth = width
    var cropHeight = height
    if (origin != null && size != null) {
        val candidateLeft = origin[0]
        val candidateTop = origin[1]
        val candidateWidth = size[0]
        val candidateHeight = size[1]
        // An impossible crop is ignored the way a reader without DNG support would ignore it.
        if (candidateLeft >= 0 && candidateTop >= 0 && candidateWidth > 0 && candidateHeight > 0 &&
            candidateLeft + candidateWidth <= width && candidateTop + candidateHeight <= height
        ) {
            left = candidateLeft
            top = candidateTop
            cropWidth = candidateWidth
            cropHeight = candidateHeight
        }
    }
    return DngCameraFraming(
        width.toInt(), height.toInt(), left.toInt(), top.toInt(), cropWidth.toInt(), cropHeight.toInt(),
    )
}

private fun readIfd(source: DngByteSource, order: ByteOrder, offset: Long): IfdSummary? {
    if (offset < 8 || offset + 2 > source.size) return null
    val countBytes = source.read(offset, 2) ?: return null
    val count = ByteBuffer.wrap(countBytes).order(order).getShort(0).toInt() and 0xFFFF
    if (count == 0 || count > MaxEntries) return null
    val body = source.read(offset + 2, count * 12 + 4) ?: return null
    val buffer = ByteBuffer.wrap(body).order(order)

    var newSubfileType = 0L
    var photometric: Long? = null
    var width: Long? = null
    var height: Long? = null
    var cropOrigin: List<Long>? = null
    var cropSize: List<Long>? = null
    var hasDngVersion = false
    var subIfds = emptyList<Long>()

    for (index in 0 until count) {
        val base = index * 12
        val tag = buffer.getShort(base).toInt() and 0xFFFF
        val type = buffer.getShort(base + 2).toInt() and 0xFFFF
        val valueCount = buffer.getInt(base + 4).toLong() and 0xFFFFFFFFL
        when (tag) {
            TagNewSubfileType -> newSubfileType = values(source, order, buffer, base, type, valueCount)?.firstOrNull() ?: 0L
            TagImageWidth -> width = values(source, order, buffer, base, type, valueCount)?.firstOrNull()
            TagImageLength -> height = values(source, order, buffer, base, type, valueCount)?.firstOrNull()
            TagPhotometric -> photometric = values(source, order, buffer, base, type, valueCount)?.firstOrNull()
            TagSubIfds -> subIfds = values(source, order, buffer, base, type, valueCount).orEmpty()
            TagDngVersion -> hasDngVersion = true
            TagDefaultCropOrigin -> cropOrigin = values(source, order, buffer, base, type, valueCount)
                ?.takeIf { it.size >= 2 }
            TagDefaultCropSize -> cropSize = values(source, order, buffer, base, type, valueCount)
                ?.takeIf { it.size >= 2 }
        }
    }
    val next = buffer.getInt(count * 12).toLong() and 0xFFFFFFFFL
    return IfdSummary(newSubfileType, photometric, width, height, cropOrigin, cropSize, hasDngVersion, subIfds, next)
}

/** Numeric values of one entry (rationals rounded to the nearest integer), or null when unreadable. */
private fun values(
    source: DngByteSource,
    order: ByteOrder,
    entries: ByteBuffer,
    base: Int,
    type: Int,
    count: Long,
): List<Long>? {
    val unit = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> return null
    }
    if (count <= 0 || count > MaxValues) return null
    val total = unit * count.toInt()
    val data: ByteBuffer = if (total <= 4) {
        ByteBuffer.wrap(entries.array(), base + 8, 4).slice().order(order)
    } else {
        val pointer = entries.getInt(base + 8).toLong() and 0xFFFFFFFFL
        ByteBuffer.wrap(source.read(pointer, total) ?: return null).order(order)
    }
    return List(count.toInt()) { i ->
        when (type) {
            1, 2, 7 -> (data.get(i).toLong() and 0xFF)
            6 -> data.get(i).toLong()
            3 -> (data.getShort(i * 2).toLong() and 0xFFFF)
            8 -> data.getShort(i * 2).toLong()
            4 -> (data.getInt(i * 4).toLong() and 0xFFFFFFFFL)
            9, 13 -> data.getInt(i * 4).toLong()
            11 -> Math.round(data.getFloat(i * 4).toDouble())
            12 -> Math.round(data.getDouble(i * 8))
            5 -> rational(data.getInt(i * 8).toLong() and 0xFFFFFFFFL, data.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL)
            else -> rational(data.getInt(i * 8).toLong(), data.getInt(i * 8 + 4).toLong())
        }
    }
}

private fun rational(numerator: Long, denominator: Long): Long =
    if (denominator == 0L) 0L else Math.round(numerator.toDouble() / denominator.toDouble())
