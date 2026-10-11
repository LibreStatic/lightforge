package com.librestatic.lightforge.core.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngCameraFramingTest {
    private class Entry(val tag: Int, val type: Int, val values: List<Long>, val denominators: List<Long>? = null)

    private fun short(tag: Int, vararg v: Long) = Entry(tag, 3, v.toList())
    private fun long(tag: Int, vararg v: Long) = Entry(tag, 4, v.toList())
    private fun rational(tag: Int, numerators: List<Long>, denominators: List<Long>) =
        Entry(tag, 5, numerators, denominators)
    private val dngVersion = Entry(50706, 1, listOf(1, 4, 0, 0))

    /** Lays out IFDs one after another, each followed by its out-of-line values. */
    private class Tiff(val order: ByteOrder) {
        fun build(ifds: List<List<Entry>>, chain: Boolean = false): ByteArray {
            // Sizes: header 8, then each IFD followed by its out-of-line data.
            val sizes = ifds.map { entries ->
                2 + entries.size * 12 + 4 + entries.sumOf { extra(it) }
            }
            var cursor = 8
            val offsets = sizes.map { size -> cursor.also { cursor += size } }
            val buffer = ByteBuffer.allocate(cursor).order(order)
            buffer.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
            buffer.putShort(42)
            buffer.putInt(offsets[0])
            ifds.forEachIndexed { index, entries ->
                buffer.position(offsets[index])
                buffer.putShort(entries.size.toShort())
                var data = offsets[index] + 2 + entries.size * 12 + 4
                for (entry in entries) {
                    buffer.putShort(entry.tag.toShort())
                    buffer.putShort(entry.type.toShort())
                    buffer.putInt(entry.values.size)
                    val extra = extra(entry)
                    if (extra == 0) {
                        val start = buffer.position()
                        writeValues(buffer, entry)
                        buffer.position(start + 4)
                    } else {
                        buffer.putInt(data)
                        val here = buffer.position()
                        buffer.position(data)
                        writeValues(buffer, entry)
                        data += extra
                        buffer.position(here)
                    }
                }
                buffer.putInt(if (chain && index + 1 < ifds.size) offsets[index + 1] else 0)
            }
            return buffer.array()
        }

        private fun unit(type: Int) = when (type) { 1 -> 1; 3 -> 2; 4 -> 4; 5 -> 8; else -> error("type") }
        private fun extra(entry: Entry): Int {
            val total = unit(entry.type) * entry.values.size
            return if (total <= 4) 0 else total
        }
        private fun writeValues(buffer: ByteBuffer, entry: Entry) {
            entry.values.forEachIndexed { i, v ->
                when (entry.type) {
                    1 -> buffer.put(v.toByte())
                    3 -> buffer.putShort(v.toShort())
                    4 -> buffer.putInt(v.toInt())
                    5 -> { buffer.putInt(v.toInt()); buffer.putInt(entry.denominators!![i].toInt()) }
                }
            }
        }
    }

    private fun parse(bytes: ByteArray) = parseDngCameraFraming(ByteArrayDngSource(bytes))

    private fun thumbnail(extra: List<Entry>) = listOf(
        long(254, 1), long(256, 256), long(257, 192), dngVersion,
    ) + extra

    private fun rawIfd(width: Long, height: Long, vararg extra: Entry) = listOf(
        long(254, 0), long(256, width), long(257, height), short(262, 32803),
    ) + extra

    /** IFD0 (thumbnail) pointing at one SubIFD that holds the raw image. */
    private fun withSubIfd(order: ByteOrder, sub: List<Entry>, ifd0Extra: List<Entry> = emptyList()): ByteArray {
        val ifd0 = thumbnail(ifd0Extra) + long(330, 0)
        // The SubIFD starts right after IFD0: header 8 + IFD0 size + its out-of-line data (none here).
        val ifd0Size = 2 + ifd0.size * 12 + 4 + ifd0.sumOf { entry ->
            val total = (if (entry.type == 1) 1 else if (entry.type == 3) 2 else if (entry.type == 4) 4 else 8) * entry.values.size
            if (total <= 4) 0 else total
        }
        val subOffset = 8L + ifd0Size
        val fixed = ifd0.dropLast(1) + long(330, subOffset)
        // Tiff.build sorts nothing; tags must stay ascending in real files but the parser does not care.
        return Tiff(order).build(listOf(fixed, sub))
    }

    @Test fun `crop in a little-endian SubIFD is read`() {
        val bytes = withSubIfd(
            ByteOrder.LITTLE_ENDIAN,
            rawIfd(4000, 3000, short(50719, 1563, 1173), short(50720, 873, 654)),
        )
        val framing = parse(bytes)
        assertEquals(DngCameraFraming(4000, 3000, 1563, 1173, 873, 654), framing)
        assertTrue(framing!!.isMeaningful)
    }

    @Test fun `crop in a big-endian SubIFD is read`() {
        val bytes = withSubIfd(
            ByteOrder.BIG_ENDIAN,
            rawIfd(4000, 3000, long(50719, 1563, 1173), long(50720, 873, 654)),
        )
        assertEquals(DngCameraFraming(4000, 3000, 1563, 1173, 873, 654), parse(bytes))
    }

    @Test fun `crop in IFD0 applies when the raw IFD has none`() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val bytes = withSubIfd(
                order,
                rawIfd(4000, 3000),
                ifd0Extra = listOf(short(50719, 100, 50), short(50720, 2000, 1500)),
            )
            assertEquals(DngCameraFraming(4000, 3000, 100, 50, 2000, 1500), parse(bytes))
        }
    }

    @Test fun `rational crop values are rounded`() {
        val bytes = withSubIfd(
            ByteOrder.LITTLE_ENDIAN,
            rawIfd(
                4000, 3000,
                rational(50719, listOf(3126, 2346), listOf(2, 2)),
                rational(50720, listOf(1746, 1308), listOf(2, 2)),
            ),
        )
        assertEquals(DngCameraFraming(4000, 3000, 1563, 1173, 873, 654), parse(bytes))
    }

    @Test fun `main raw image in IFD0 and a chained thumbnail`() {
        val ifd0 = rawIfd(4000, 3000, dngVersion, short(50719, 0, 0), short(50720, 2000, 3000))
        val thumb = listOf(long(254, 1), long(256, 4000), long(257, 3000))
        val bytes = Tiff(ByteOrder.BIG_ENDIAN).build(listOf(ifd0, thumb), chain = true)
        assertEquals(DngCameraFraming(4000, 3000, 0, 0, 2000, 3000), parse(bytes))
    }

    @Test fun `no crop tags means the whole image and not meaningful`() {
        val framing = parse(withSubIfd(ByteOrder.LITTLE_ENDIAN, rawIfd(4000, 3000)))
        assertEquals(DngCameraFraming(4000, 3000, 0, 0, 4000, 3000), framing)
        assertFalse(framing!!.isMeaningful)
    }

    @Test fun `an eight pixel margin is not meaningful`() {
        val framing = parse(
            withSubIfd(
                ByteOrder.LITTLE_ENDIAN,
                rawIfd(4032, 3024, short(50719, 8, 8), short(50720, 4016, 3008)),
            ),
        )
        assertNotNull(framing)
        assertEquals(4016, framing!!.cropWidth)
        assertFalse(framing.isMeaningful)
    }

    @Test fun `the one percent threshold is exclusive`() {
        assertFalse(DngCameraFraming(1000, 1000, 0, 0, 990, 1000).isMeaningful)
        assertTrue(DngCameraFraming(1000, 1000, 0, 0, 989, 1000).isMeaningful)
        assertTrue(DngCameraFraming(1000, 1000, 0, 0, 1000, 500).isMeaningful)
    }

    @Test fun `a crop outside the image is ignored`() {
        val framing = parse(
            withSubIfd(
                ByteOrder.LITTLE_ENDIAN,
                rawIfd(4000, 3000, short(50719, 3900, 0), short(50720, 873, 654)),
            ),
        )
        assertEquals(DngCameraFraming(4000, 3000, 0, 0, 4000, 3000), framing)
    }

    @Test fun `a tiff without a DNG version is not a dng`() {
        val ifd0 = listOf(long(254, 0), long(256, 4000), long(257, 3000), short(50719, 1, 1), short(50720, 10, 10))
        assertNull(parse(Tiff(ByteOrder.LITTLE_ENDIAN).build(listOf(ifd0))))
    }

    @Test fun `garbage and truncated input return null`() {
        assertNull(parse(ByteArray(0)))
        assertNull(parse(ByteArray(64) { 0x41 }))
        assertNull(parse("II".toByteArray()))
        val good = withSubIfd(
            ByteOrder.LITTLE_ENDIAN,
            rawIfd(4000, 3000, short(50719, 1563, 1173), short(50720, 873, 654)),
        )
        for (length in listOf(8, 20, 100, good.size - 30)) {
            // Truncation must never throw; either null or a (possibly partial) answer is fine.
            parse(good.copyOf(length))
        }
        assertNull(parse(good.copyOf(8)))
        // A header whose first IFD points past the end of the file.
        val dangling = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("II".toByteArray()).putShort(42).putInt(9_999).array()
        assertNull(parse(dangling))
    }

    @Test fun `an IFD loop terminates`() {
        val ifd0 = rawIfd(4000, 3000, dngVersion)
        val bytes = Tiff(ByteOrder.LITTLE_ENDIAN).build(listOf(ifd0))
        // Point the next-IFD field back at IFD0.
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8 + 2 + ifd0.size * 12, 8)
        assertEquals(4000, parse(bytes)?.fullWidth)
    }

    @Test fun `random bytes behind a valid header never throw`() {
        val random = java.util.Random(7)
        repeat(200) {
            val bytes = ByteArray(256).also(random::nextBytes)
            bytes[0] = 'M'.code.toByte(); bytes[1] = 'M'.code.toByte(); bytes[2] = 0; bytes[3] = 42
            bytes[4] = 0; bytes[5] = 0; bytes[6] = 0; bytes[7] = 8
            parse(bytes)
        }
    }
}
