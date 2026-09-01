package com.ugallery.core.editing.video

import androidx.media3.common.Metadata
import androidx.media3.container.Mp4TimestampData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VideoExportMetadataTest {
    @Test
    fun replacesSourceTimestampWithExportCreationTime() {
        val passthrough = TestMetadataEntry
        val entries = mutableSetOf<Metadata.Entry>(
            Mp4TimestampData(1L, 2L),
            passthrough,
        )
        val exportTimeMillis = 1_788_235_000_000L

        freshExportMetadataProvider { exportTimeMillis }.updateMetadataEntries(entries)

        val timestamp = entries.filterIsInstance<Mp4TimestampData>().single()
        val expected = Mp4TimestampData.unixTimeToMp4TimeSeconds(exportTimeMillis)
        assertEquals(expected, timestamp.creationTimestampSeconds)
        assertEquals(expected, timestamp.modificationTimestampSeconds)
        assertSame(passthrough, entries.single { it === passthrough })
    }

    private object TestMetadataEntry : Metadata.Entry
}
