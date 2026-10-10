package com.librestatic.lightforge.core.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class HeifContainerTest {
    private val startCode = byteArrayOf(0, 0, 0, 1)
    private val vps = byteArrayOf(0x40, 0x01, 0x0C)
    private val sps = byteArrayOf(0x42, 0x01, 0x01, 0x02)
    private val pps = byteArrayOf(0x44, 0x01, 0x03)

    // --- box builder -------------------------------------------------------------------------

    private fun u8(v: Int) = byteArrayOf(v.toByte())
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = u32(v.toLong())
    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = cat(*payload)
        return cat(u32(body.size + 8), type.toByteArray(Charsets.ISO_8859_1), body)
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray) =
        box(type, u8(version), u8(flags shr 16), u16(flags and 0xFFFF), *payload)

    private fun ftyp(vararg brands: String) =
        box("ftyp", "heic".toByteArray(), u32(0), *brands.map { it.toByteArray() }.toTypedArray())

    private fun infe(id: Int, type: String) =
        fullBox("infe", 2, 0, u16(id), u16(0), type.toByteArray(), u8(0))

    private fun ispe(w: Int, h: Int) = fullBox("ispe", 0, 0, u32(w), u32(h))
    private fun irot(angle: Int) = box("irot", u8(angle))
    private fun imir(axis: Int) = box("imir", u8(axis))

    private fun hvcc(lengthSizeMinusOne: Int = 3): ByteArray {
        val header = ByteArray(21)
        header[0] = 1
        fun array(type: Int, nal: ByteArray) = cat(u8(0x80 or type), u16(1), u16(nal.size), nal)
        return box(
            "hvcC", header, u8(0xFC or lengthSizeMinusOne), u8(3),
            array(32, vps), array(33, sps), array(34, pps),
        )
    }

    private fun nclx(matrix: Int, fullRange: Boolean) =
        box("colr", "nclx".toByteArray(), u16(1), u16(13), u16(matrix), u8(if (fullRange) 0x80 else 0))

    private fun prof(type: String = "prof", bytes: ByteArray) = box("colr", type.toByteArray(), bytes)

    /** One `ipma` entry per item with 8-bit (or 15-bit when [wide]) association indices. */
    private fun ipma(wide: Boolean, vararg entries: Pair<Int, List<Int>>): ByteArray {
        val body = ByteArrayOutputStream()
        entries.forEach { (id, indices) ->
            body.write(u16(id))
            body.write(indices.size)
            indices.forEach { body.write(if (wide) u16(it or 0x8000) else u8(it or 0x80)) }
        }
        return fullBox("ipma", 0, if (wide) 1 else 0, u32(entries.size), body.toByteArray())
    }

    private class Loc(val id: Int, val method: Int, val extents: List<Pair<Long, Long>>, val base: Long = 0)

    /** iloc v1 with 4-byte offsets/lengths/base offsets. */
    private fun iloc(vararg locs: Loc): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(u16(locs.size))
        locs.forEach { l ->
            body.write(u16(l.id))
            body.write(u16(l.method))
            body.write(u16(0))
            body.write(u32(l.base))
            body.write(u16(l.extents.size))
            l.extents.forEach { (o, len) -> body.write(u32(o)); body.write(u32(len)) }
        }
        return fullBox("iloc", 1, 0, u8(0x44), u8(0x40), body.toByteArray())
    }

    private fun iref(vararg refs: Triple<String, Int, List<Int>>): ByteArray = fullBox(
        "iref", 0, 0,
        *refs.map { (type, from, to) ->
            box(type, u16(from), u16(to.size), *to.map { u16(it) }.toTypedArray())
        }.toTypedArray(),
    )

    private fun gridPayload(rows: Int, cols: Int, w: Int, h: Int, wide: Boolean = false) =
        if (wide) cat(u8(0), u8(1), u8(rows - 1), u8(cols - 1), u32(w), u32(h))
        else cat(u8(0), u8(0), u8(rows - 1), u8(cols - 1), u16(w), u16(h))

    private fun nal(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()
    private fun sample(nal: ByteArray, lengthSize: Int = 4) =
        cat(ByteArray(lengthSize).also { for (i in 0 until lengthSize) it[i] = (nal.size shr (8 * (lengthSize - 1 - i))).toByte() }, nal)

    private fun parse(bytes: ByteArray) = HeifContainer.parse(ByteArrayHeifSource(bytes))

    // --- tests -------------------------------------------------------------------------------

    @Test
    fun singleHvc1Primary() {
        val payload = sample(nal(0x26, 0x01, 0xAA, 0xBB))
        val metaWithoutOffsets = { offset: Long ->
            fullBox(
                "meta", 0, 0,
                fullBox("pitm", 0, 0, u16(1)),
                fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1")),
                iloc(Loc(1, 0, listOf(offset to payload.size.toLong()))),
                box("iprp", box("ipco", hvcc(), ispe(640, 480), nclx(1, false)), ipma(false, 1 to listOf(1, 2, 3))),
            )
        }
        val ftyp = ftyp("mif1", "heic")
        val metaSize = metaWithoutOffsets(0).size
        val file = cat(ftyp, metaWithoutOffsets((ftyp.size + metaSize + 8).toLong()), box("mdat", payload))

        val container = parse(file)!!
        assertTrue(HeifContainer.isHeifBrands(container.brands))
        val primary = container.primary!!
        assertNull(primary.grid)
        assertEquals(640, primary.width)
        assertEquals(480, primary.height)
        assertEquals(listOf(primary.item), primary.tiles)
        assertEquals(4, primary.item.hevcConfig!!.lengthSize)
        assertArrayEquals(
            cat(startCode, vps, startCode, sps, startCode, pps),
            primary.item.hevcConfig!!.parameterSetsAnnexB(),
        )
        assertEquals(1, primary.colour!!.matrixCoefficients)
        assertFalse(primary.colour!!.fullRange)
        assertArrayEquals(payload, container.readItem(primary.item))
        assertTrue(container.thumbnails.isEmpty())
    }

    private fun primaryWithColr(vararg colr: ByteArray): HeifImage {
        val meta = fullBox(
            "meta", 0, 0,
            fullBox("pitm", 0, 0, u16(1)),
            fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1")),
            iloc(Loc(1, 0, listOf(0L to 4L))),
            box("iprp", box("ipco", hvcc(), ispe(10, 20), *colr), ipma(false, 1 to (1..colr.size + 2).toList())),
        )
        return parse(cat(ftyp("heic"), meta))!!.primary!!
    }

    @Test
    fun profAndNclxOnTheSameItemAreBothKept() {
        val icc = ByteArray(300) { it.toByte() }
        val primary = primaryWithColr(prof(bytes = icc), nclx(6, true))
        assertArrayEquals(icc, primary.iccProfile)
        assertEquals(6, primary.colour!!.matrixCoefficients)
        assertEquals(1, primary.colour!!.colourPrimaries)
        assertEquals(13, primary.colour!!.transfer)
    }

    @Test
    fun rIccIsKeptAndOversizedProfilesAreDropped() {
        val icc = ByteArray(64) { 5 }
        assertArrayEquals(icc, primaryWithColr(prof("rICC", icc)).iccProfile)
        assertNull(primaryWithColr(prof(bytes = ByteArray(1024 * 1024 + 1))).iccProfile)
        assertArrayEquals(ByteArray(1024 * 1024), primaryWithColr(prof(bytes = ByteArray(1024 * 1024))).iccProfile)
    }

    @Test
    fun gridPrimaryWithThumbnailAndTileOrder() {
        // Tiles are listed 4,3,2 in the dimg reference: that order must be preserved.
        val tilePayloads = mapOf(2 to nal(0x26, 2), 3 to nal(0x26, 3), 4 to nal(0x26, 4)).mapValues { sample(it.value) }
        val thumbPayload = sample(nal(0x26, 9))
        val grid = gridPayload(rows = 1, cols = 3, w = 1000, h = 500)
        val build = { base: Long ->
            var offset = base
            val locs = mutableListOf<Loc>()
            for ((id, data) in tilePayloads) { locs += Loc(id, 0, listOf(offset to data.size.toLong())); offset += data.size }
            locs += Loc(5, 0, listOf(offset to thumbPayload.size.toLong()))
            locs += Loc(1, 1, listOf(0L to grid.size.toLong()))
            fullBox(
                "meta", 0, 0,
                fullBox("pitm", 0, 0, u16(1)),
                fullBox("iinf", 0, 0, u16(5), infe(1, "grid"), infe(2, "hvc1"), infe(3, "hvc1"), infe(4, "hvc1"), infe(5, "hvc1")),
                iloc(*locs.toTypedArray()),
                iref(Triple("dimg", 1, listOf(4, 3, 2)), Triple("thmb", 5, listOf(1))),
                box(
                    "iprp",
                    box("ipco", hvcc(), ispe(512, 512), ispe(160, 80), irot(1), imir(0)),
                    ipma(false, 1 to listOf(4, 5), 2 to listOf(1, 2), 3 to listOf(1, 2), 4 to listOf(1, 2), 5 to listOf(1, 3)),
                ),
                box("idat", grid),
            )
        }
        val ftyp = ftyp("mif1", "heic")
        val metaLen = build(0).size
        val file = cat(ftyp, build((ftyp.size + metaLen + 8).toLong()), box("mdat", *tilePayloads.values.toTypedArray(), thumbPayload))

        val container = parse(file)!!
        val primary = container.primary!!
        val g = primary.grid!!
        assertEquals(1, g.rows)
        assertEquals(3, g.columns)
        assertEquals(1000, primary.width)
        assertEquals(500, primary.height)
        assertEquals(listOf(4, 3, 2), primary.tiles.map { it.id })
        assertEquals(512, primary.tiles[0].width)
        assertEquals(listOf(HeifProperty.Irot(90), HeifProperty.Imir(horizontalFlip = true)), primary.transforms)
        assertArrayEquals(tilePayloads.getValue(4), container.readItem(primary.tiles[0]))
        assertArrayEquals(tilePayloads.getValue(2), container.readItem(primary.tiles[2]))

        val thumb = container.thumbnails.single()
        assertEquals(5, thumb.id)
        assertEquals(160, thumb.width)
        assertArrayEquals(thumbPayload, container.readItem(thumb.item))
    }

    @Test
    fun wideGridFieldsAreRead() {
        val grid = gridPayload(2, 2, 70_000, 40_000, wide = true)
        val meta = fullBox(
            "meta", 0, 0,
            fullBox("pitm", 0, 0, u16(1)),
            fullBox("iinf", 0, 0, u16(5), infe(1, "grid"), infe(2, "hvc1"), infe(3, "hvc1"), infe(4, "hvc1"), infe(5, "hvc1")),
            iloc(Loc(1, 1, listOf(0L to grid.size.toLong()))),
            iref(Triple("dimg", 1, listOf(2, 3, 4, 5))),
            box("iprp", box("ipco", hvcc(), ispe(35_000, 20_000)), ipma(false, 2 to listOf(1, 2), 3 to listOf(1, 2), 4 to listOf(1, 2), 5 to listOf(1, 2))),
            box("idat", grid),
        )
        val primary = parse(cat(ftyp("mif1"), meta))!!.primary!!
        assertEquals(70_000, primary.grid!!.outputWidth)
        assertEquals(40_000, primary.grid!!.outputHeight)
    }

    @Test
    fun multiExtentItemsAreConcatenated() {
        val first = nal(0, 0, 0, 2, 0x26, 1)
        val second = nal(0, 0, 0, 3, 0x26, 2, 3)
        val build = { base: Long ->
            fullBox(
                "meta", 0, 0,
                fullBox("pitm", 0, 0, u16(1)),
                fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1")),
                // base_offset is added to each extent offset; extents are out of file order.
                iloc(Loc(1, 0, listOf(first.size.toLong() + 3 to second.size.toLong(), 3L to first.size.toLong()), base = base)),
                box("iprp", box("ipco", hvcc(), ispe(8, 8)), ipma(false, 1 to listOf(1, 2))),
            )
        }
        val ftyp = ftyp("heic")
        val len = build(0).size
        val base = (ftyp.size + len + 8).toLong()
        val file = cat(ftyp, build(base), box("mdat", byteArrayOf(9, 9, 9), first, second))
        val container = parse(file)!!
        assertArrayEquals(cat(second, first), container.readItem(container.primary!!.item))
    }

    @Test
    fun fifteenBitPropertyIndicesAreResolved() {
        // Property index 130 requires the 15-bit form (essential bit set on the wire).
        val props = mutableListOf(hvcc())
        repeat(128) { props += box("free") }
        props += ispe(33, 44)
        val grid = cat(*props.toTypedArray())
        val payload = sample(nal(0x26, 1))
        val build = { offset: Long ->
            fullBox(
                "meta", 0, 0,
                fullBox("pitm", 0, 0, u16(1)),
                fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1")),
                iloc(Loc(1, 0, listOf(offset to payload.size.toLong()))),
                box("iprp", box("ipco", grid), ipma(true, 1 to listOf(1, 130))),
            )
        }
        val ftyp = ftyp("heic")
        val file = cat(ftyp, build((ftyp.size + build(0).size + 8).toLong()), box("mdat", payload))
        val primary = parse(file)!!.primary!!
        assertEquals(33, primary.width)
        assertEquals(44, primary.height)
    }

    @Test
    fun rotationAndMirrorKeepAssociationOrder() {
        val build = {
            fullBox(
                "meta", 0, 0,
                fullBox("pitm", 0, 0, u16(1)),
                fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1")),
                iloc(Loc(1, 0, listOf(0L to 4L))),
                box("iprp", box("ipco", hvcc(), ispe(10, 20), imir(1), irot(3)), ipma(false, 1 to listOf(1, 2, 3, 4))),
            )
        }
        val primary = parse(cat(ftyp("heic"), build()))!!.primary!!
        assertEquals(listOf(HeifProperty.Imir(horizontalFlip = false), HeifProperty.Irot(270)), primary.transforms)
    }

    @Test
    fun lengthPrefixedConversionToAnnexB() {
        val a = nal(0x26, 1, 2)
        val b = nal(0x02, 7)
        val converted = lengthPrefixedToAnnexB(cat(sample(a, 2), sample(b, 2)), 2)
        assertArrayEquals(cat(startCode, a, startCode, b), converted)
        try {
            lengthPrefixedToAnnexB(nal(0, 0, 0, 9, 1), 4)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun nonHeifAndTruncatedInputNeverCrash() {
        assertNull(parse(ByteArray(0)))
        assertNull(parse("not a heif file at all, just text".toByteArray()))
        val meta = fullBox(
            "meta", 0, 0,
            fullBox("pitm", 0, 0, u16(1)),
            fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1")),
            iloc(Loc(1, 0, listOf(0L to 4L))),
            box("iprp", box("ipco", hvcc(), ispe(10, 20)), ipma(false, 1 to listOf(1, 2))),
        )
        val valid = cat(ftyp("heic"), meta)
        assertNotNull(parse(valid)?.primary)
        for (length in 0 until valid.size) {
            try {
                parse(valid.copyOf(length))?.primary
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun corruptedBytesNeverThrowUnexpectedExceptions() {
        val meta = fullBox(
            "meta", 0, 0,
            fullBox("pitm", 0, 0, u16(1)),
            fullBox("iinf", 0, 0, u16(2), infe(1, "grid"), infe(2, "hvc1")),
            iloc(Loc(1, 1, listOf(0L to 8L)), Loc(2, 0, listOf(0L to 4L))),
            iref(Triple("dimg", 1, listOf(2)), Triple("thmb", 2, listOf(1))),
            box("iprp", box("ipco", hvcc(), ispe(10, 20), irot(1)), ipma(false, 1 to listOf(1, 2), 2 to listOf(1, 2))),
            box("idat", gridPayload(1, 1, 10, 20)),
        )
        val valid = cat(ftyp("heic"), meta)
        val random = java.util.Random(42)
        repeat(2000) {
            val bytes = valid.copyOf()
            repeat(1 + random.nextInt(4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            try {
                val container = parse(bytes)
                container?.primary
                container?.thumbnails
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun hugeBoxSizesAreRejected() {
        val bogus = cat(ftyp("heic"), u32(0x7FFFFFF0), "meta".toByteArray(), ByteArray(32))
        try {
            assertNull(parse(bogus))
        } catch (_: IOException) {
        }
    }

    @Test
    fun realFixtureWhenProvided() {
        val path = System.getProperty("heif.realFixture")
        assumeTrue(!path.isNullOrBlank() && File(path).isFile)
        RandomAccessFile(path, "r").use { file ->
            val container = HeifContainer.parse(ChannelHeifSource(file.channel))!!
            val primary = container.primary!!
            val grid = primary.grid!!
            assertEquals(48, primary.tiles.size)
            assertEquals(4032, grid.outputWidth)
            assertEquals(3024, grid.outputHeight)
            assertTrue(primary.transforms.contains(HeifProperty.Irot(270)))
            val thumb = container.thumbnails.first()
            assertEquals(416, thumb.width)
            assertEquals(312, thumb.height)
            assertNotNull(thumb.item.hevcConfig)
            val icc = primary.iccProfile
            if (icc != null) {
                val resolved = resolveColourSpace(primary.colour, icc)
                val space = parseIccRgb(icc)
                println("real fixture icc: ${icc.size} bytes, primaries=${space?.primaries?.toList()}, white=${space?.whitePoint?.toList()}, srgbTrc=${space?.transfer?.isSrgb()}, g=${space?.transfer?.g}, resolved=$resolved")
                assertEquals(ResolvedColourSpace.Named.DISPLAY_P3, resolved)
            }
            println("real fixture ok: ${grid.rows}x${grid.columns} tiles, transforms=${primary.transforms}, colour=${primary.colour}")
        }
    }
}
