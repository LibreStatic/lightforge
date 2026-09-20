package com.ugallery.feature.localsharing

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.ugallery.core.designsystem.UGalleryTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Actual TLS re-pair of a known peer must keep pending consent and its stored profile on one list.
 */
class LocalSharingPairRefreshContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun knownPeerAndPendingConsentUseDistinctLazyKeysAndRemainActionable() {
        PeerFixture().use { f ->
            runBlocking { f.pair() }
            val pin = PeerTls(f.sender).pin
            f.server.close()
            LocalSharingReceiver(f.recipient, f.services).use { renewed ->
                val invite = renewed.start("127.0.0.1")
                runBlocking { assertFalse(f.sendController.pair(invite, "Fixture sender")) }
                assertEquals(pin, LocalSharingReceiver.state.value.pending.single().pin)
                assertEquals(pin, f.receiveStore.peers().single().id)
                f.receiveController.reconcile()
                compose.setContent {
                    UGalleryTheme(dynamicColor = false) {
                        LocalSharingContent(f.receiveController, {})
                    }
                }
                compose.waitUntil(5000) { f.receiveController.peers.value.any { it.id == pin } }
                val list = compose.onNode(hasScrollAction() and !hasSetTextAction())
                list.performScrollToNode(hasTestTag("peer-profile-$pin"))
                compose.onNodeWithTag("peer-profile-$pin").assertIsDisplayed()
                list.performScrollToNode(hasTestTag("peer-approve-$pin"))
                compose.onNodeWithTag("peer-approve-$pin").assertIsDisplayed().performClick()
                compose.waitUntil(5000) { LocalSharingReceiver.state.value.pending.isEmpty() }
                runBlocking { assertTrue(f.sendController.pair(invite, "Fixture sender")) }
                assertEquals(1, f.receiveStore.peers().count { it.id == pin })
                list.performScrollToNode(hasTestTag("peer-profile-$pin"))
                compose.onNodeWithTag("peer-profile-$pin").assertIsDisplayed()
            }
        }
    }
}
