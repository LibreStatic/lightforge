package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.VerifiedMoveProof
import com.librestatic.lightforge.core.model.MediaKey
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedMoveRowHintTest {

    private fun proof() = VerifiedMoveProof(
        UUID.randomUUID().toString(), "external_primary", 17L, "Image",
        "content://media/external_primary/images/media/17",
        "content://fixture.documents/tree/own%3Adir/document/own%3Adir%2Fphoto.png",
        "content://fixture.documents/tree/own%3Adir", 123L, "ab".repeat(32), 7L, 9L, true,
    )

    private fun entry(phase: VerifiedMovePhase) =
        VerifiedMoveEntry(proof(), phase, if (phase == VerifiedMovePhase.Ready) 0L else 1L)

    @Test
    fun `a completed move retires the original row`() {
        assertEquals(
            MediaKey("external_primary", 17L),
            retiredMoveRow(entry(VerifiedMovePhase.Completed)),
        )
    }

    @Test
    fun `an unfinished move retires nothing`() {
        assertNull(retiredMoveRow(entry(VerifiedMovePhase.Ready)))
        assertNull(retiredMoveRow(entry(VerifiedMovePhase.AwaitingSystem)))
        assertNull(retiredMoveRow(entry(VerifiedMovePhase.Cancelled)))
        assertNull(retiredMoveRow(entry(VerifiedMovePhase.RequestFailed)))
    }

    @Test
    fun `no entry retires nothing`() {
        assertNull(retiredMoveRow(null))
    }
}
