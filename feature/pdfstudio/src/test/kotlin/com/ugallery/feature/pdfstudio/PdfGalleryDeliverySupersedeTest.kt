package com.ugallery.feature.pdfstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfGalleryDeliverySupersedeTest {

    private val selection = """["content://media/1","content://media/2"]"""
    private val other = """["content://media/9"]"""

    private fun delivery(id: String, uris: String, error: String? = null) =
        PdfGalleryDelivery(id = id, name = "Untitled", uris = uris, error = error)

    @Test
    fun `a clean import supersedes an earlier failure of the same selection`() {
        val completed = delivery("b", selection)
        val failed = delivery("a", selection, error = "Unsupported")
        assertEquals(true, supersedesFailedDelivery(completed, failed))
    }

    @Test
    fun `a failure of a different selection is left alone`() {
        val completed = delivery("b", selection)
        val failed = delivery("a", other, error = "Unsupported")
        assertEquals(false, supersedesFailedDelivery(completed, failed))
    }

    @Test
    fun `a delivery still waiting is not a stale failure`() {
        val completed = delivery("b", selection)
        val waiting = delivery("a", selection, error = null)
        assertEquals(false, supersedesFailedDelivery(completed, waiting))
    }

    @Test
    fun `a cancelled delivery of the same selection is superseded too`() {
        val completed = delivery("b", selection)
        val cancelled = delivery("a", selection, error = "Cancelled")
        assertEquals(true, supersedesFailedDelivery(completed, cancelled))
    }

    @Test
    fun `the completed row never supersedes itself`() {
        val completed = delivery("a", selection, error = "Unsupported")
        assertEquals(false, supersedesFailedDelivery(completed, completed))
    }
}
