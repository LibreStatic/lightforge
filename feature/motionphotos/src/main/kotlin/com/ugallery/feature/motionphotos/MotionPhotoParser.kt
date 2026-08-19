package com.ugallery.feature.motionphotos

import java.io.RandomAccessFile

data class MotionPhotoInfo(
    val isMotionPhoto: Boolean,
    val motionVideoOffset: Long,
    val motionVideoLength: Long,
    val motionPhotoVersion: Int? = null,
    val source: MotionPhotoSource = MotionPhotoSource.UNKNOWN,
)

enum class MotionPhotoSource { SAMSUNG, GOOGLE, UNKNOWN }

object MotionPhotoParser {

    fun parse(file: RandomAccessFile): MotionPhotoInfo {
        val soi = ByteArray(2)
        file.readFully(soi)
        if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) {
            return MotionPhotoInfo(false, 0, 0)
        }
        val xmpData = readXmpFromJpeg(file)
        if (xmpData == null) return MotionPhotoInfo(false, 0, 0)
        return parseXmp(xmpData, file.length())
    }

    fun parseXmp(xmp: String, fileLength: Long): MotionPhotoInfo {
        val samsungVersion = extractXmpValue(xmp, "GCamera:MotionPhotoVersion")
        if (samsungVersion != null) {
            val microVideoOffset = extractXmpValue(xmp, "GCamera:MicroVideoOffset")?.toLongOrNull()
            if (microVideoOffset != null) {
                val videoStart = fileLength - microVideoOffset
                return MotionPhotoInfo(true, videoStart, microVideoOffset, samsungVersion.toIntOrNull(), MotionPhotoSource.SAMSUNG)
            }
        }
        val motionPhotoAttr = extractXmpValue(xmp, "GCamera:MotionPhoto")
        if (motionPhotoAttr != null) {
            val items = parseContainerDirectory(xmp)
            val motionItem = items.find { it.semantic == "MotionPhoto" }
            if (motionItem != null) {
                val videoStart = fileLength - motionItem.length
                return MotionPhotoInfo(true, videoStart, motionItem.length, 1, MotionPhotoSource.GOOGLE)
            }
        }
        return MotionPhotoInfo(false, 0, 0)
    }

    data class DirectoryItem(val semantic: String, val length: Long)

    private fun parseContainerDirectory(xmp: String): List<DirectoryItem> {
        val items = mutableListOf<DirectoryItem>()
        val semRe = Regex("""Item:Semantic="([^"]+)"""")
        val lenRe = Regex("""Item:Length="(\d+)"""")
        val sems = semRe.findAll(xmp).toList()
        val lens = lenRe.findAll(xmp).toList()
        for (i in 0 until minOf(sems.size, lens.size)) {
            items.add(DirectoryItem(sems[i].groupValues[1], lens[i].groupValues[1].toLong()))
        }
        return items
    }

    private fun readXmpFromJpeg(file: RandomAccessFile): String? {
        file.seek(2)
        while (true) {
            if (file.read() != 0xFF) return null
            val marker = file.read()
            if (marker == 0xD9 || marker == 0xDA) return null
            val len = ((file.read() shl 8) or file.read()) - 2
            if (len <= 0) continue
            if (marker == 0xE1) {
                val data = ByteArray(len)
                file.readFully(data)
                val ns = String(data, 0, minOf(29, data.size))
                if (ns.startsWith("http://ns.adobe.com/xap/1.0/")) {
                    if (data.size > 29) return String(data, 29, data.size - 29, Charsets.UTF_8)
                }
            } else {
                file.skipBytes(len)
            }
        }
    }

    private fun extractXmpValue(xmp: String, key: String): String? {
        val p1 = Regex(key + """\s*=\s*"([^"]+)"""")
        p1.find(xmp)?.let { return it.groupValues[1] }
        val p2 = Regex("<" + key + ">([^<]+)</" + key + ">")
        p2.find(xmp)?.let { return it.groupValues[1] }
        val p3 = Regex("""\w+:""" + key + """\s*=\s*"([^"]+)"""")
        p3.find(xmp)?.let { return it.groupValues[1] }
        return null
    }
}
