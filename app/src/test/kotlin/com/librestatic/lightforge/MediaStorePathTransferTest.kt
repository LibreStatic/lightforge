package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePathTransferTest {
    private class Gateway(val refuse: Set<Long> = emptySet()) : MediaStoreGateway {
        val moved = ArrayList<Pair<Long, String>>()
        val copied = ArrayList<Triple<Long, String, String>>()

        override fun move(source: TransferSource, relativePath: String): Boolean {
            if (source.key.mediaStoreId in refuse) return false
            moved += source.key.mediaStoreId to relativePath
            return true
        }

        override suspend fun copy(source: TransferSource, volumeName: String, relativePath: String): Boolean {
            if (source.key.mediaStoreId in refuse) return false
            copied += Triple(source.key.mediaStoreId, volumeName, relativePath)
            return true
        }
    }

    private fun source(id: Long, volume: String = "external_primary", path: String = "DCIM/Camera/") =
        TransferSource(MediaKey(volume, id), MediaKind.Image, volume, path, "IMG_$id.jpg")

    private val target = FolderTarget("external_primary", "Pictures/Trips")

    @Test
    fun destinationPathNormalisesAndRefusesTheVolumeRoot() {
        val transfer = MediaStorePathTransfer(Gateway())
        assertEquals("Pictures/Trips/", transfer.destinationPath(FolderTarget("v", "/Pictures/Trips/")))
        assertEquals("A/", transfer.destinationPath(FolderTarget("v", "A")))
        assertNull(transfer.destinationPath(FolderTarget("v", null)))
        assertNull(transfer.destinationPath(FolderTarget("v", "/")))
    }

    @Test
    fun aMoveStaysInPlaceOnlyWhenEverySourceIsOnTheTargetVolume() {
        val transfer = MediaStorePathTransfer(Gateway())
        assertTrue(transfer.movesInPlace(listOf(source(1), source(2)), target))
        assertFalse(transfer.movesInPlace(listOf(source(1), source(2, volume = "1234-5678")), target))
        assertFalse(transfer.movesInPlace(emptyList(), target))
    }

    @Test
    fun moveCountsAcceptedRefusedAndAlreadyThere() {
        val gateway = Gateway(refuse = setOf(2L))
        val result = MediaStorePathTransfer(gateway).move(
            listOf(source(1), source(2), source(3, path = "Pictures/Trips/")),
            target,
        )
        assertEquals(listOf(1L to "Pictures/Trips/"), gateway.moved)
        assertEquals(1, result.moved)
        assertEquals(1, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(3, result.total)
    }

    @Test
    fun moveToTheVolumeRootFailsEverything() {
        val gateway = Gateway()
        val result = MediaStorePathTransfer(gateway).move(listOf(source(1)), FolderTarget("external_primary", null))
        assertTrue(gateway.moved.isEmpty())
        assertEquals(1, result.failed)
    }

    @Test
    fun copyReportsProgressAndOnlyCountsItemsThatWereWritten() = runBlocking {
        val gateway = Gateway(refuse = setOf(2L))
        val progress = ArrayList<Pair<Int, Int>>()
        val (result, copied) = MediaStorePathTransfer(gateway).copy(
            TransferPurpose.Copy, listOf(source(1), source(2), source(3)), target,
        ) { done, total -> progress += done to total }
        assertEquals(listOf(1L, 3L), copied.map { it.key.mediaStoreId })
        assertEquals(2, result.copied)
        assertEquals(1, result.failed)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun aCrossVolumeMoveLeavesTheCopiedOriginalsAwaitingDeletion() = runBlocking {
        val gateway = Gateway()
        val (result, copied) = MediaStorePathTransfer(gateway).copy(
            TransferPurpose.Move, listOf(source(1, volume = "1234-5678", path = "DCIM/")), target,
        )
        assertEquals(listOf("external_primary"), gateway.copied.map { it.second })
        assertEquals(0, result.copied)
        assertEquals(copied, result.awaitingDelete)
        assertEquals(TransferOutcome.Done, result.outcome())
    }

    @Test
    fun aMoveIntoTheFolderTheItemIsAlreadyInIsSkippedNotDuplicated() = runBlocking {
        val gateway = Gateway()
        val (result, copied) = MediaStorePathTransfer(gateway).copy(
            TransferPurpose.Move, listOf(source(1, path = "Pictures/Trips/")), target,
        )
        assertTrue(gateway.copied.isEmpty())
        assertTrue(copied.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun copyAndVerifyAcceptsAnExactCopy() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val written = java.io.ByteArrayOutputStream()
        copyAndVerify(
            openOriginal = { bytes.inputStream() },
            openDestination = { written },
            openWritten = { written.toByteArray().inputStream() },
        )
        assertEquals(bytes.toList(), written.toByteArray().toList())
    }

    @Test
    fun copyAndVerifyRejectsACopyWhoseBytesDiffer() {
        val bytes = ByteArray(1000) { it.toByte() }
        val corrupted = bytes.copyOf().also { it[500] = (it[500] + 1).toByte() }
        val failure = runCatching {
            copyAndVerify(
                openOriginal = { bytes.inputStream() },
                openDestination = { java.io.ByteArrayOutputStream() },
                openWritten = { corrupted.inputStream() },
            )
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun copyAndVerifyRejectsATruncatedCopyEvenWithoutAKnownSize() {
        val bytes = ByteArray(1000) { it.toByte() }
        assertTrue(
            runCatching {
                copyAndVerify({ bytes.inputStream() }, { java.io.ByteArrayOutputStream() }, { bytes.copyOf(999).inputStream() })
            }.isFailure,
        )
    }

    @Test
    fun copyAndVerifyRejectsAnOriginalThatChangedWhileCopying() {
        val bytes = ByteArray(1000) { it.toByte() }
        var opened = 0
        assertTrue(
            runCatching {
                copyAndVerify(
                    openOriginal = { if (opened++ == 0) bytes.inputStream() else bytes.copyOf(10).inputStream() },
                    openDestination = { java.io.ByteArrayOutputStream() },
                    openWritten = { bytes.inputStream() },
                )
            }.isFailure,
        )
    }

    @Test
    fun copyAndVerifyFailsWhenAStreamCannotBeOpened() {
        assertTrue(runCatching { copyAndVerify({ null }, { java.io.ByteArrayOutputStream() }, { null }) }.isFailure)
        val bytes = ByteArray(10)
        assertTrue(runCatching { copyAndVerify({ bytes.inputStream() }, { java.io.ByteArrayOutputStream() }, { null }) }.isFailure)
    }

    @Test
    fun aBatchCopiedToAPickedTreeIsDoneForCopyAndWaitsForTheOriginalsForMove() {
        val copied = listOf(source(1), source(2))
        val copy = treeTransferResult(TransferPurpose.Copy, 3, copied, failed = 1)
        assertEquals(2, copy.copied)
        assertEquals(1, copy.failed)
        assertTrue(copy.awaitingDelete.isEmpty())
        val move = treeTransferResult(TransferPurpose.Move, 3, copied, failed = 1)
        assertEquals(copied, move.awaitingDelete)
        assertEquals(0, move.copied)
        assertEquals(TransferOutcome.Partial, move.outcome())
        assertEquals(TransferOutcome.Failed, treeTransferResult(TransferPurpose.Move, 2, emptyList(), failed = 2).outcome())
    }
}
