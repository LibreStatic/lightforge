package com.ugallery.feature.pdfstudio

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class PdfImportPickerTest {
    @Test
    fun durableInboxFillsMissingDeliveryButDoesNotReplaceAnotherPicker() {
        val picker = PdfImportPicker(SavedStateHandle())
        val request = PdfImportRequest(newId(), false, "p", "page", listOf("content://source"))
        assertTrue(picker.restore(request))
        assertEquals(request, picker.request)
        assertFalse(picker.restore(request.copy(id = newId())))
        picker.acknowledge(request)
        assertTrue(picker.begin(true, null, null))
        assertFalse(picker.restore(request))
    }

    @Test
    fun ownerSurvivesRecreationAndDuplicateOrWrongContractCannotReplaceDelivery() {
        val saved = SavedStateHandle()
        val picker = PdfImportPicker(saved)
        assertFalse(picker.begin(false, null, null))
        assertTrue(picker.begin(false, "project", "page"))
        assertFalse(picker.begin(true, null, null))
        val restored =
            PdfImportPicker(
                SavedStateHandle(
                    mapOf(PdfImportPicker.KEY to saved.get<ArrayList<String>>(PdfImportPicker.KEY))
                )
            )
        assertEquals(picker.request, restored.request)
        assertFalse(restored.result(true, listOf("content://wrong")))
        assertTrue(restored.result(false, listOf("content://one", "content://two")))
        assertFalse(restored.result(false, listOf("content://duplicate")))
        assertEquals("project", restored.request!!.projectId)
        assertEquals("page", restored.request!!.pageId)
        assertEquals(listOf("content://one", "content://two"), restored.request!!.uris)
    }

    @Test
    fun cancellationAndStaleAcknowledgementDoNotConsumeNewRequests() {
        val picker = PdfImportPicker(SavedStateHandle())
        picker.begin(true, null, null)
        val old = picker.request!!
        assertFalse(picker.result(true, emptyList()))
        assertTrue(picker.begin(false, "p", "page"))
        picker.acknowledge(old)
        assertEquals("p", picker.request!!.projectId)
    }

    @Test
    fun savedDeliveryHasBoundedCountAndBytes() {
        val picker = PdfImportPicker(SavedStateHandle())
        picker.begin(false, "p", "page")
        assertTrue(runCatching { picker.result(false, List(101) { "content://$it" }) }.isFailure)
        assertTrue(
            runCatching { picker.result(false, listOf("x".repeat(128 * 1024 + 1))) }.isFailure
        )
        assertTrue(picker.request!!.uris.isEmpty())
        assertTrue(picker.result(false, List(100) { "content://$it" }))
    }
}
