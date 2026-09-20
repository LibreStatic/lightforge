package com.ugallery.feature.pdfstudio

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Recheck at most every 64 KiB, not once per PDF token; preserve metadata headroom while saving.
 */
internal class PdfBudgetOutputStream(output: OutputStream, private val freeBytes: () -> Long) :
    FilterOutputStream(output) {
    private var credit = 0L

    private fun account(count: Int) {
        if (count == 0) return
        if (credit < count) {
            val free = freeBytes()
            if (
                free < PdfStorageBudget.RESERVE_BYTES ||
                    count.toLong() > free - PdfStorageBudget.RESERVE_BYTES
            )
                throw PdfOperationFailure(PdfFailure.StorageFull)
            credit = minOf(free - PdfStorageBudget.RESERVE_BYTES, maxOf(64L * 1024, count.toLong()))
        }
        credit -= count
    }

    override fun write(value: Int) {
        account(1)
        out.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        account(length)
        out.write(bytes, offset, length)
    }
}
