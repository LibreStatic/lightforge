package com.ugallery.feature.pdfstudio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PdfStorageBudgetTest {
    @Test
    fun rendererStreamStopsAfterStorageDropsAndDoesNotWriteRejectedBytes() {
        val sink = java.io.ByteArrayOutputStream()
        var free = PdfStorageBudget.RESERVE_BYTES + 128 * 1024
        var checks = 0
        val stream =
            PdfBudgetOutputStream(sink) {
                checks++
                free
            }
        stream.write(ByteArray(64 * 1024))
        assertEquals(1, checks)
        free = PdfStorageBudget.RESERVE_BYTES
        val error = runCatching { stream.write(byteArrayOf(7)) }.exceptionOrNull()!!
        assertEquals(PdfFailure.StorageFull, PdfFailure.from(error))
        assertEquals(64 * 1024, sink.size())
        assertEquals(2, checks)
    }

    @Test
    fun reserveAndChunkAreCheckedWithoutOverflow() {
        val f = File("/tmp/output")
        PdfStorageBudget { PdfStorageBudget.RESERVE_BYTES + 65536 }.beforeWrite(f)
        for (free in listOf(-1L, 0L, PdfStorageBudget.RESERVE_BYTES, Long.MIN_VALUE)) {
            val error = runCatching { PdfStorageBudget { free }.beforeWrite(f) }.exceptionOrNull()!!
            assertEquals(PdfFailure.StorageFull, PdfFailure.from(error))
        }
        assertTrue(
            runCatching { PdfStorageBudget { Long.MAX_VALUE }.beforeWrite(f, Long.MAX_VALUE) }
                .isFailure
        )
    }

    @Test
    fun remoteFailuresPreserveStableReasonsAndHideUnknownText() {
        assertEquals(PdfFailure.StorageFull, PdfFailure.remote("StorageFull"))
        assertEquals(PdfFailure.MemoryPressure, PdfFailure.remote("MemoryPressure"))
        assertEquals(PdfFailure.EncryptedPdf, PdfFailure.remote("InvalidPasswordException"))
        assertEquals(PdfFailure.InvalidPdf, PdfFailure.remote("Unknown", PdfFailure.InvalidPdf))
        assertEquals(PdfFailure.Unknown, PdfFailure.remote("private engine path and exception"))
    }
}
