package com.librestatic.lightforge.feature.localsharing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Alternating real listeners models each app's one explicit receive session, without extra
 * services.
 */
class LocalSharingReversePairDeviceTest {
    @Test
    fun reversePairPreservesConfirmedEndpointAndAuthenticatesBothDirections() =
        runBlocking<Unit> {
            PeerFixture().use { f ->
                f.pair()
                val firstForward = f.prepare()
                f.complete(firstForward)
                assertArrayEquals(
                    f.source.bytes,
                    f.receiveStore.received(firstForward, 0).readBytes(),
                )
                val confirmedB = f.sendStore.peers().single { it.id == f.invitation.pin }
                assertTrue(confirmedB.canSend)
                f.server.close()

                val aPin = PeerTls(f.sender).pin
                val bPin = PeerTls(f.recipient).pin
                var aPort = 0
                LocalSharingReceiver(f.sender, f.services).use { a ->
                    val inviteA = a.start("127.0.0.1")
                    aPort = inviteA.port
                    assertFalse(f.receiveController.pair(inviteA, "Fixture recipient"))
                    LocalSharingReceiver.approve(bPin)
                    assertTrue(f.receiveController.pair(inviteA, "Fixture recipient"))
                    val stillConfirmedB = f.sendStore.peers().single { it.id == bPin }
                    assertTrue(
                        "Explicit reverse pairing must preserve the previously confirmed outbound endpoint",
                        stillConfirmedB.canSend,
                    )
                    assertFalse(stillConfirmedB.revoked)
                    assertEquals(confirmedB.host, stillConfirmedB.host)
                    assertEquals(confirmedB.port, stillConfirmedB.port)
                    assertTrue(f.receiveStore.peers().single { it.id == aPin }.canSend)
                }

                // New receiver objects reuse the exact previously confirmed endpoints. New
                // invitations
                // are not paired: both sends must authenticate with the rotated, durable peer
                // token.
                LocalSharingReceiver(f.recipient, f.services).use { b ->
                    val reopened = b.start(confirmedB.host, confirmedB.port)
                    assertEquals(bPin, reopened.pin)
                    val nextForward = f.prepare()
                    f.complete(nextForward)
                    assertArrayEquals(
                        f.source.bytes,
                        f.receiveStore.received(nextForward, 0).readBytes(),
                    )
                }
                LocalSharingReceiver(f.sender, f.services).use { a ->
                    assertEquals(aPin, a.start("127.0.0.1", aPort).pin)
                    val reverse =
                        f.receiveController.enqueue(aPin, listOf("content://fixture/reverse"), true)
                    LocalSharingRunner(f.recipient, f.services).run(reverse)
                    assertEquals(
                        LocalSharingStatus.AwaitingReview,
                        f.receiveStore.transfer(reverse)!!.status,
                    )
                    f.receiveController.confirmSend(reverse)
                    LocalSharingRunner(f.recipient, f.services).run(reverse)
                    assertEquals(
                        LocalSharingStatus.AwaitingReceiveConsent,
                        f.sendStore.transfer(reverse)!!.status,
                    )
                    f.sendController.acceptReceive(reverse)
                    f.receiveController.resume(reverse)
                    LocalSharingRunner(f.recipient, f.services).run(reverse)
                    assertEquals(
                        LocalSharingStatus.Completed,
                        f.receiveStore.transfer(reverse)!!.status,
                    )
                    assertEquals(
                        LocalSharingStatus.ReadyToImport,
                        f.sendStore.transfer(reverse)!!.status,
                    )
                    assertArrayEquals(f.source.bytes, f.sendStore.received(reverse, 0).readBytes())
                }
            }
        }
}
