package com.librestatic.lightforge.feature.privatealbum

import org.junit.Assert.*
import org.junit.Test

class PrivateExportPolicyTest {
    private val initial = PrivateExportDestination(
        "content://media/external_primary/images/media/42", "fixture.owner", "owned.png", "Pictures/Lightforge/",
        "image/png", 12, 12, null, pending = true, trashed = false)

    @Test fun currentPendingBytesMayChangeButNeverImmutableDestinationIdentity() {
        assertTrue(PrivateExportPolicy.mayObserve(initial, initial.copy(modified = 14, size = 128)))
        assertTrue(PrivateExportPolicy.mayObserve(initial, initial.copy(modified = 15, size = 128, pending = false)))
        listOf(initial.copy(uri = initial.uri + "1"), initial.copy(owner = "foreign"),
            initial.copy(name = "changed.png"), initial.copy(path = "Pictures/Other/"),
            initial.copy(mime = "image/jpeg"), initial.copy(added = 13),
            initial.copy(modified = 11), initial.copy(trashed = true)).forEach {
            assertFalse(it.toString(), PrivateExportPolicy.mayObserve(initial, it))
        }
    }

    @Test fun forgetNeverAbandonsReadyOrPartialOutput() {
        assertFalse(PrivateExportPolicy.canForget(PrivateExportStatus.Ready))
        assertFalse(PrivateExportPolicy.canForget(PrivateExportStatus.Partial))
        listOf(PrivateExportStatus.Published, PrivateExportStatus.Missing,
            PrivateExportStatus.Conflict, PrivateExportStatus.Unknown).forEach {
            assertTrue(PrivateExportPolicy.canForget(it))
        }
    }

    @Test fun proofSealsMetadataActualBytesAndReceiptRatherThanIdAlone() {
        val fields = listOf("request", "Ready", "receipt") + initial.fields() + listOf("128", "digest")
        val proof = PrivateExportPolicy.proof(fields)
        PrivateExportPolicy.requireProof(proof, PrivateExportPolicy.proof(fields.toList()))
        fields.indices.forEach { index ->
            val changed = fields.toMutableList().apply { this[index] = (this[index] ?: "") + "changed" }
            assertNotEquals(proof, PrivateExportPolicy.proof(changed))
            rejects { PrivateExportPolicy.requireProof(proof, PrivateExportPolicy.proof(changed)) }
        }
        rejects { PrivateExportPolicy.requireProof("", proof) }
        rejects { PrivateExportPolicy.requireProof("not-a-proof", "not-a-proof") }
    }

    @Test fun proofLengthFramingDistinguishesNullEmptyAndAmbiguousConcatenation() {
        assertNotEquals(PrivateExportPolicy.proof(listOf(null)), PrivateExportPolicy.proof(listOf("")))
        assertNotEquals(PrivateExportPolicy.proof(listOf("ab", "c")), PrivateExportPolicy.proof(listOf("a", "bc")))
        assertNotEquals(PrivateExportPolicy.proof(listOf("a", "b")), PrivateExportPolicy.proof(listOf("a\nb")))
        assertNotEquals(PrivateExportPolicy.proof(listOf("1", "digest")), PrivateExportPolicy.proof(listOf("2", "digest")))
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected a stale or malformed proof to fail") }
        catch (_: IllegalArgumentException) { }
    }
}
