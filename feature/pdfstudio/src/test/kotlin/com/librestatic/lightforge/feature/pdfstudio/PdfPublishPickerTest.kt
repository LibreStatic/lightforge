package com.librestatic.lightforge.feature.pdfstudio

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class PdfPublishPickerTest {
    @Test
    fun onlyOneOutstandingRequestAndCancellationAllowsAnother() {
        val picker = PdfPublishPicker(SavedStateHandle())
        assertEquals(PublishStart.Launch, picker.begin("one"))
        assertEquals(PublishStart.AlreadyPending("one" to null), picker.begin("two"))
        assertFalse(picker.result(null))
        assertEquals(PublishStart.Launch, picker.begin("two"))
    }

    @Test
    fun `no pending request decides to launch`() {
        assertEquals(PublishStart.Launch, beginDecision(null))
    }

    @Test
    fun `stale pending request decides already pending`() {
        assertEquals(PublishStart.AlreadyPending("job" to null), beginDecision("job" to null))
        assertEquals(
            PublishStart.AlreadyPending("job" to "content://x"),
            beginDecision("job" to "content://x"),
        )
    }

    @Test
    fun `cancel clears a delivered but unacknowledged request`() {
        val picker = PdfPublishPicker(SavedStateHandle())
        picker.begin("one")
        assertTrue(picker.result("content://one"))
        assertFalse(picker.result(null)) // stale delivery never acknowledged, e.g. process death
        assertNull(picker.request)
        assertEquals(PublishStart.Launch, picker.begin("two"))
    }

    @Test
    fun `discard clears any request`() {
        val picker = PdfPublishPicker(SavedStateHandle())
        picker.begin("one")
        picker.discard()
        assertNull(picker.request)
        assertEquals(PublishStart.Launch, picker.begin("two"))
    }

    @Test
    fun pendingRequestAndDeliverySurviveSavedStateRecreation() {
        val saved = SavedStateHandle()
        val first = PdfPublishPicker(saved)
        first.begin("one")
        val restored =
            PdfPublishPicker(
                SavedStateHandle(
                    mapOf(
                        PdfPublishPicker.KEY to saved.get<ArrayList<String>>(PdfPublishPicker.KEY)
                    )
                )
            )
        assertEquals("one" to null, restored.request)
        assertTrue(restored.result("content://document/new"))
        assertFalse(restored.result("content://document/duplicate"))
        assertEquals("one" to "content://document/new", restored.request)
    }

    @Test
    fun staleAcknowledgementCannotClearANewerRequest() {
        val picker = PdfPublishPicker(SavedStateHandle())
        picker.begin("one")
        picker.result("content://one")
        val old = picker.request!!
        picker.acknowledge(old)
        picker.begin("two")
        picker.acknowledge(old)
        assertEquals("two" to null, picker.request)
    }
}
