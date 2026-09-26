package com.ugallery.feature.pdfstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCollateralDeathTest {
    @Test
    fun `a death well before the call's own deadline was another operation's overrun`() {
        assertTrue(collateralDeath(elapsedMillis = 2_000, timeoutMillis = 60_000))
        assertTrue(collateralDeath(elapsedMillis = 58_999, timeoutMillis = 60_000))
    }

    @Test
    fun `a death at the call's own deadline is its own overrun`() {
        assertFalse(collateralDeath(elapsedMillis = 59_000, timeoutMillis = 60_000))
        assertFalse(collateralDeath(elapsedMillis = 300_000, timeoutMillis = 300_000))
    }

    @Test
    fun `an interrupted operation keeps its own persisted code`() {
        assertEquals(PdfFailure.Interrupted, PdfFailure.persisted(PdfFailure.Interrupted.name))
        assertEquals(
            PdfFailure.Interrupted,
            PdfFailure.from(PdfOperationFailure(PdfFailure.Interrupted)),
        )
    }
}
