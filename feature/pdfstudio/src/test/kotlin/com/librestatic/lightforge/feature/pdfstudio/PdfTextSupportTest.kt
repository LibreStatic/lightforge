package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfTextSupportTest {
    @Test
    fun acceptsLatinGreekAndCyrillicIncludingPunctuationAndNewlines() {
        assertTrue(PdfTextSupport.check("Hello, world! 123 - café").isSuccess)
        assertTrue(PdfTextSupport.check("Ελληνικά: Καλημέρα").isSuccess)
        assertTrue(PdfTextSupport.check("Русский: Привет").isSuccess)
        assertTrue(PdfTextSupport.check("Line one\nLine two\tTabbed").isSuccess)
        assertTrue(PdfTextSupport.check("“Smart quotes” — em dash, ellipsis…").isSuccess)
        assertTrue(PdfTextSupport.check("€ £ $ ¥ 20%").isSuccess)
    }

    @Test
    fun rejectsArabicHebrewDevanagariThaiCjkAndEmoji() {
        assertTrue(PdfTextSupport.check("مرحبا").isFailure)
        assertTrue(PdfTextSupport.check("שלום").isFailure)
        assertTrue(PdfTextSupport.check("नमस्ते").isFailure)
        assertTrue(PdfTextSupport.check("สวัสดี").isFailure)
        assertTrue(PdfTextSupport.check("你好").isFailure)
        assertTrue(PdfTextSupport.check("こんにちは").isFailure)
        assertTrue(PdfTextSupport.check("안녕하세요").isFailure)
        assertTrue(PdfTextSupport.check("Hello 😀").isFailure) // 😀, a surrogate-pair emoji
    }

    @Test
    fun failureIsTheStablePdfFailureCode() {
        val failure = PdfTextSupport.check("你好").exceptionOrNull()
        assertTrue(failure is PdfOperationFailure)
        assertEquals(PdfFailure.UnsupportedGlyph, (failure as PdfOperationFailure).failure)
    }

    @Test
    fun mixingOneUnsupportedCharacterAmongSupportedOnesStillFails() {
        assertTrue(PdfTextSupport.check("Bonjour 你").isFailure)
    }

    @Test
    fun emptyStringIsSupported() {
        // Length is PdfProject.validate()'s job (1..2000 chars); check() itself has nothing to
        // reject in an empty string.
        assertTrue(PdfTextSupport.check("").isSuccess)
    }
}
