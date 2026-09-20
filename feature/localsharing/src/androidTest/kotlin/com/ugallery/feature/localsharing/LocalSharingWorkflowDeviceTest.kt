package com.ugallery.feature.localsharing

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSharingWorkflowDeviceTest {
    @Test
    fun tlsPairConsentTransferVerifiedImportAndReceiptSurviveReopen() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                assertEquals(0, f.receiveStore.received(id, 0).length())
                f.complete(id)
                assertArrayEquals(f.source.bytes, f.receiveStore.received(id, 0).readBytes())
                assertEquals(LocalSharingStatus.ReadyToImport, f.receiveStore.transfer(id)!!.status)
                f.receiveController.confirmImport(id, emptyMap())
                LocalSharingRunner(f.recipient, f.services).run(id)
                assertEquals(
                    LocalSharingStatus.LocalTaskCreated,
                    LocalSharingStore(f.recipient).transfer(id)!!.status,
                )
                assertEquals(1, f.imported.copies)
                LocalSharingRunner(f.recipient, f.services).run(id)
                assertEquals(1, f.imported.copies)
            }
        }

    @Test
    fun incorrectOutOfBandSecretDoesNotCreatePairOrTask() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                assertTrue(
                    runCatching {
                            f.sendController.pair(
                                f.invitation.copy(secret = "c".repeat(64)),
                                "Wrong",
                            )
                        }
                        .isFailure
                )
                assertTrue(LocalSharingReceiver.state.value.pending.isEmpty())
                assertTrue(f.receiveStore.transfers().isEmpty())
            }
        }

    @Test
    fun incorrectTlsPinStopsBeforeApplicationOffer() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                assertTrue(
                    runCatching {
                            f.sendController.pair(f.invitation.copy(pin = "c".repeat(64)), "Wrong")
                        }
                        .isFailure
                )
                assertTrue(LocalSharingReceiver.state.value.pending.isEmpty())
            }
        }

    @Test
    fun resumedActualPrefixMustMatchBeforeAppending() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                f.receiveController.acceptReceive(id)
                val prefix = f.source.bytes.copyOf(777_777)
                f.receiveStore.received(id, 0).writeBytes(prefix)
                f.sendController.resume(id)
                LocalSharingRunner(f.sender, f.services).run(id)
                assertEquals(LocalSharingStatus.Completed, f.sendStore.transfer(id)!!.status)
                assertArrayEquals(f.source.bytes, f.receiveStore.received(id, 0).readBytes())
            }
        }

    @Test
    fun corruptPrefixIsRetainedAndNeverOverwritten() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                f.receiveController.acceptReceive(id)
                val wrong = ByteArray(333) { 9 }
                f.receiveStore.received(id, 0).writeBytes(wrong)
                f.sendController.resume(id)
                LocalSharingRunner(f.sender, f.services).run(id)
                assertEquals(LocalSharingStatus.NeedsReview, f.sendStore.transfer(id)!!.status)
                assertArrayEquals(wrong, f.receiveStore.received(id, 0).readBytes())
            }
        }

    @Test
    fun revokeRejectsAuthenticatedPeerBeforeFileBytes() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                f.receiveController.revoke(PeerTls(f.sender).pin)
                f.sendController.resume(id)
                LocalSharingRunner(f.sender, f.services).run(id)
                assertEquals(LocalSharingStatus.NeedsReview, f.sendStore.transfer(id)!!.status)
                assertEquals(0, f.receiveStore.received(id, 0).length())
            }
        }

    @Test
    fun pauseAndResumeBeforeConsentCannotApproveImplicitly() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                f.receiveController.pause(id)
                f.receiveController.resume(id)
                assertEquals(
                    LocalSharingStatus.AwaitingReceiveConsent,
                    f.receiveStore.transfer(id)!!.status,
                )
                f.sendController.resume(id)
                LocalSharingRunner(f.sender, f.services).run(id)
                assertEquals(LocalSharingStatus.WaitingPeer, f.sendStore.transfer(id)!!.status)
            }
        }

    @Test
    fun zeroByteOriginalTransfersAndVerifies() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.source.bytes = byteArrayOf()
                f.pair()
                val id = f.prepare()
                f.complete(id)
                assertTrue(f.receiveStore.received(id, 0).isFile)
                assertEquals(0, f.receiveStore.received(id, 0).length())
            }
        }

    @Test
    fun cancelAfterImportReceiptPreservesInputsAndChild() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                f.complete(id)
                f.receiveController.confirmImport(id, emptyMap())
                val t = f.receiveStore.transfer(id)!!
                val child =
                    f.imported.enqueueOnce(
                        t.peerId,
                        id,
                        t.manifest!!,
                        listOf(
                            LocalSharingReceivedFile(
                                t.manifest.entries[0],
                                f.receiveStore.received(id, 0),
                            )
                        ),
                        emptyMap(),
                    )
                f.receiveController.pause(id)
                f.receiveController.cancel(id)
                LocalSharingRunner(f.recipient, f.services).run(id)
                assertEquals(child, f.receiveStore.transfer(id)!!.localTaskId)
                assertTrue(f.receiveStore.received(id, 0).exists())
            }
        }

    @Test
    fun cancelledReceiveCleansOnlyItsPrivateUncommittedBytes() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                f.receiveController.acceptReceive(id)
                f.receiveStore.received(id, 0).writeBytes(byteArrayOf(1, 2))
                val foreign =
                    java.io.File(f.recipient.filesDir, "foreign.bin").apply {
                        writeText("preserve")
                    }
                f.receiveController.cancel(id)
                LocalSharingRunner(f.recipient, f.services).run(id)
                assertFalse(f.receiveStore.received(id, 0).exists())
                assertEquals("preserve", foreign.readText())
            }
        }

    @Test
    fun unsanitizedSourceNeverReachesOffer() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                f.source.sanitized = false
                val id =
                    f.sendController.enqueue(
                        f.invitation.pin,
                        listOf("content://fixture/original"),
                        true,
                    )
                LocalSharingRunner(f.sender, f.services).run(id)
                assertEquals(LocalSharingStatus.NeedsReview, f.sendStore.transfer(id)!!.status)
                assertTrue(f.receiveStore.transfers().isEmpty())
            }
        }

    @Test
    fun corruptJournalRemainsVisibleAndFrameLengthIsBounded() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val id = f.prepare()
                java.io.File(f.sendStore.directory(id), "task.json").writeText("broken")
                assertEquals("corrupt", f.sendStore.transfers().single().failure)
                val raw = java.nio.ByteBuffer.allocate(4).putInt(PEER_FRAME_LIMIT + 1).array()
                assertThrows(java.io.IOException::class.java) {
                    PeerCodec.read(DataInputStream(ByteArrayInputStream(raw)))
                }
            }
        }

    @Test
    fun receiverStopsListeningOutsideExplicitReceive() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.server.close()
                assertFalse(LocalSharingReceiver.state.value.active)
                assertTrue(runCatching { f.sendController.pair(f.invitation, "Closed") }.isFailure)
            }
        }

    @Test
    fun alreadyImportedRevisionCreatesNoAdditionalGalleryCopy() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val first = f.prepare()
                f.complete(first)
                f.receiveController.confirmImport(first, emptyMap())
                LocalSharingRunner(f.recipient, f.services).run(first)
                f.imported.disposition = LocalSharingImportDisposition.AlreadyReceived
                val second = f.prepare()
                f.complete(second)
                assertEquals(
                    LocalSharingImportDisposition.AlreadyReceived,
                    f.receiveController.reviewImport(second).single().disposition,
                )
                f.receiveController.confirmImport(second, emptyMap())
                LocalSharingRunner(f.recipient, f.services).run(second)
                assertEquals(1, f.imported.copies)
            }
        }
}
