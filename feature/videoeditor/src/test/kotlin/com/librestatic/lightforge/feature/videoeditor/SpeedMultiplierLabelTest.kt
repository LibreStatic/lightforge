package com.librestatic.lightforge.feature.videoeditor

import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals

class SpeedMultiplierLabelTest {
    @Test
    fun usesTheLocaleDecimalSeparator() {
        assertEquals("0.25×", speedMultiplierLabel(0.25f, Locale.US))
        assertEquals("0,25×", speedMultiplierLabel(0.25f, Locale.GERMANY))
        assertEquals("1,0×", speedMultiplierLabel(1f, Locale.FRANCE))
        assertEquals("0,125×", speedMultiplierLabel(0.125f, Locale("es")))
    }
}
