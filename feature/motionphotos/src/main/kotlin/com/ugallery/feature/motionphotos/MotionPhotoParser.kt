package com.ugallery.feature.motionphotos

import java.io.DataInput
import java.io.DataInputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.io.RandomAccessFile
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Ranges are absolute file offsets, not an OEM identity inferred from a namespace. */
data class MotionPhotoInfo(
    val isMotionPhoto: Boolean,
    val motionVideoOffset: Long,
    val motionVideoLength: Long,
    val motionPhotoVersion: Int? = null,
    val source: MotionPhotoSource = MotionPhotoSource.UNKNOWN,
    val presentationTimeUs: Long? = null,
    val primaryEnd: Long = 0,
)

enum class MotionPhotoSource {
    SAMSUNG,
    GOOGLE,
    UNKNOWN,
}

/** JPEG Motion Photo 1.0 + legacy MicroVideo. No HEIC/AVIF or OEM private trailer guesses. */
object MotionPhotoParser {
    private const val Camera = "http://ns.google.com/photos/1.0/camera/"
    private const val Container = "http://ns.google.com/photos/1.0/container/"
    private const val Item = "http://ns.google.com/photos/1.0/container/item/"
    private const val MaxHeader = 2 * 1024 * 1024L
    private val absent
        get() = MotionPhotoInfo(false, 0, 0)

    fun parse(file: RandomAccessFile): MotionPhotoInfo = parse(file.channel)

    /** Reads an already-authorized descriptor without reopening its /proc path (scoped storage). */
    internal fun parse(channel: FileChannel): MotionPhotoInfo = parse(SeekableInput(channel))

    private class SeekableInput(private val channel: FileChannel) :
        DataInput by DataInputStream(Channels.newInputStream(channel)) {
        val filePointer: Long get() = channel.position()
        fun length(): Long = channel.size()
        fun seek(position: Long) { channel.position(position) }
    }

    private fun parse(file: SeekableInput): MotionPhotoInfo {
        return try {
            file.seek(0)
            if (file.length() < 4 || file.readUnsignedShort() != 0xffd8) absent
            else {
                var xmp: String? = null
                while (file.filePointer < minOf(file.length(), MaxHeader)) {
                    if (file.readUnsignedByte() != 0xff) break
                    var marker = file.readUnsignedByte()
                    while (marker == 0xff) {
                        if (file.filePointer >= minOf(file.length(), MaxHeader)) return absent
                        marker = file.readUnsignedByte()
                    }
                    if (marker == 0xda || marker == 0xd9) break
                    if (marker == 0x01 || marker in 0xd0..0xd7) continue
                    val size = file.readUnsignedShort() - 2
                    if (size < 0 || file.filePointer + size > minOf(file.length(), MaxHeader)) break
                    val bytes = ByteArray(size)
                    file.readFully(bytes)
                    val prefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
                    if (
                        marker == 0xe1 &&
                            bytes.size > prefix.size &&
                            bytes.copyOfRange(0, prefix.size).contentEquals(prefix)
                    ) {
                        if (xmp != null) return absent // Ambiguous duplicated primary XMP.
                        xmp = bytes.copyOfRange(prefix.size, bytes.size).toString(Charsets.UTF_8)
                    }
                }
                val info = xmp?.let { parseXmp(it, file.length()) } ?: absent
                if (
                    !info.isMotionPhoto || info.primaryEnd < file.filePointer || info.primaryEnd < 4
                )
                    absent
                else {
                    file.seek(info.primaryEnd - 2)
                    if (
                        file.readUnsignedShort() != 0xffd9 ||
                            !validMp4(file, info.motionVideoOffset, info.motionVideoLength)
                    )
                        absent
                    else info
                }
            }
        } catch (_: Exception) {
            absent
        }
    }

    /** Metadata-only range validation. parse(file) also checks JPEG end and MP4 box structure. */
    fun parseXmp(xmp: String, fileLength: Long): MotionPhotoInfo {
        return try {
            if (
                fileLength < 4 ||
                    xmp.length > 256 * 1024 ||
                    xmp.contains("<!DOCTYPE", true) ||
                    xmp.contains("<!ENTITY", true)
            )
                return absent
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {})
            val document = builder.parse(InputSource(StringReader(xmp)))
            val elements = document.getElementsByTagName("*")
            fun property(name: String): String? {
                val values = mutableSetOf<String>()
                for (i in 0 until elements.length) {
                    val element = elements.item(i) as Element
                    if (element.hasAttributeNS(Camera, name))
                        values += element.getAttributeNS(Camera, name).trim()
                    if (element.namespaceURI == Camera && element.localName == name)
                        values += element.textContent.trim()
                }
                require(values.size <= 1)
                return values.singleOrNull()
            }
            val motion = property("MotionPhoto")
            val modern = motion != null
            if ((modern && motion != "1") || (!modern && property("MicroVideo") != "1"))
                return absent
            val version = property(if (modern) "MotionPhotoVersion" else "MicroVideoVersion") ?: "1"
            if (version != "1") return absent
            val timestamp =
                property(
                        if (modern) "MotionPhotoPresentationTimestampUs"
                        else "MicroVideoPresentationTimestampUs"
                    )
                    ?.let {
                        it.toLongOrNull()?.also { time -> require(time >= -1) } ?: return absent
                    }
                    ?.takeIf { it >= 0 }
            if (!modern) {
                val length = property("MicroVideoOffset")?.toLongOrNull() ?: return absent
                if (length !in 24 until fileLength - 4) return absent
                return MotionPhotoInfo(
                    true,
                    fileLength - length,
                    length,
                    1,
                    presentationTimeUs = timestamp,
                    primaryEnd = fileLength - length,
                )
            }
            val directories = document.getElementsByTagNameNS(Container, "Directory")
            if (directories.length != 1) return absent
            val nodes = (directories.item(0) as Element).getElementsByTagName("*")
            data class Entry(
                val semantic: String,
                val mime: String,
                val length: Long,
                val padding: Long,
            )
            val items = mutableListOf<Entry>()
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                if (!e.hasAttributeNS(Item, "Semantic")) continue
                val semantic = e.getAttributeNS(Item, "Semantic")
                val mime = e.getAttributeNS(Item, "Mime")
                val length =
                    if (e.hasAttributeNS(Item, "Length"))
                        e.getAttributeNS(Item, "Length").toLongOrNull() ?: return absent
                    else if (semantic == "Primary") 0L else return absent
                val padding =
                    if (e.hasAttributeNS(Item, "Padding"))
                        e.getAttributeNS(Item, "Padding").toLongOrNull() ?: return absent
                    else 0L
                if (length < 0 || padding < 0 || mime.isBlank() || semantic.isBlank()) return absent
                items += Entry(semantic, mime, length, padding)
                if (items.size > 32) return absent
            }
            if (
                items.size < 2 ||
                    items.first().semantic != "Primary" ||
                    items.first().mime != "image/jpeg" ||
                    items.first().length != 0L ||
                    items.count { it.semantic == "Primary" } != 1
            )
                return absent
            if (
                items.last().semantic != "MotionPhoto" ||
                    items.last().mime != "video/mp4" ||
                    items.last().length < 24 ||
                    items.count { it.semantic == "MotionPhoto" } != 1
            )
                return absent
            if (items.drop(1).any { it.padding != 0L }) return absent
            var remaining = fileLength
            for (entry in items.drop(1)) {
                if (entry.length >= remaining) return absent
                remaining -= entry.length
            }
            if (items.first().padding >= remaining - 4) return absent
            val primaryEnd = remaining - items.first().padding
            MotionPhotoInfo(
                true,
                fileLength - items.last().length,
                items.last().length,
                1,
                presentationTimeUs = timestamp,
                primaryEnd = primaryEnd,
            )
        } catch (_: Exception) {
            absent
        }
    }

    private fun validMp4(file: SeekableInput, offset: Long, length: Long): Boolean {
        if (offset < 4 || length < 24 || offset > file.length() - length) return false
        val end = offset + length
        var cursor = offset
        var ftyp = false
        var moov = false
        var mdat = false
        var boxes = 0
        while (cursor < end && ++boxes <= 256) {
            if (end - cursor < 8) return false
            file.seek(cursor)
            val small = file.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
            val size =
                when (small) {
                    0L -> end - cursor
                    1L -> if (end - cursor >= 16) file.readLong() else return false
                    else -> small
                }
            if (size < (if (small == 1L) 16 else 8) || size > end - cursor) return false
            when (type) {
                "ftyp" -> {
                    if (cursor != offset || size < 16) return false
                    ftyp = true
                }
                "moov" -> moov = true
                "mdat" -> mdat = true
            }
            cursor += size
        }
        return cursor == end && ftyp && moov && mdat
    }
}
