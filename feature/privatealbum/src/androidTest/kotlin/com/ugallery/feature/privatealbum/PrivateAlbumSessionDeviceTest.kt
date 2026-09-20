package com.ugallery.feature.privatealbum

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production Compose lifecycle adapter, not platform authentication itself. */
class PrivateAlbumSessionDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun stopAndStartHideContentAndRequireANewAuthentication() {
        lateinit var owner: Owner
        lateinit var session: PrivateAlbumSession
        compose.runOnUiThread { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        compose.setContent {
            session = rememberPrivateAlbumSession(true, owner)
            Text(if (session.isUnlocked) "Private content" else "Locked")
        }
        compose.runOnIdle { assertTrue(session.completeAuthentication(session.beginAuthentication()!!)) }
        compose.onNodeWithText("Private content").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.runOnIdle { assertTrue(session.completeAuthentication(session.beginAuthentication()!!)) }
        compose.onNodeWithText("Private content").assertIsDisplayed()
    }

    @Test fun savedStateRestorationDoesNotRestorePrivateAuthorization() {
        lateinit var owner: Owner
        lateinit var session: PrivateAlbumSession
        compose.runOnUiThread { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            session = rememberPrivateAlbumSession(true, owner)
            Text(if (session.isUnlocked) "Private content" else "Locked")
        }
        compose.runOnIdle { assertTrue(session.completeAuthentication(session.beginAuthentication()!!)) }
        val old = session
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.runOnIdle {
            assertNotSame(old, session)
            assertNull(old.beginAuthentication())
            assertFalse(session.isUnlocked)
        }
    }

    @Test fun routeExitAndStoppedPromptRejectLateSuccess() {
        lateinit var owner: Owner
        lateinit var session: PrivateAlbumSession
        val active = mutableStateOf(true)
        compose.runOnUiThread { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        compose.setContent {
            session = rememberPrivateAlbumSession(active.value, owner)
            Text(if (session.isUnlocked) "Private content" else "Locked")
        }
        var token = 0L
        compose.runOnIdle { token = session.beginAuthentication()!!; active.value = false }
        compose.runOnIdle { active.value = true }
        compose.runOnIdle {
            assertFalse(session.completeAuthentication(token))
            token = session.beginAuthentication()!!
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertFalse(session.completeAuthentication(token))
        }
        compose.onNodeWithText("Locked").assertIsDisplayed()
    }

    @Test fun ownerReplacementRevokesOldSessionAndIgnoresItsCallbacks() {
        lateinit var first: Owner
        lateinit var second: Owner
        compose.runOnUiThread {
            first = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            second = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        }
        val owner = mutableStateOf<LifecycleOwner>(first)
        lateinit var session: PrivateAlbumSession
        compose.setContent {
            session = rememberPrivateAlbumSession(true, owner.value)
            Text(if (session.isUnlocked) "Private content" else "Locked")
        }
        val old = session
        var token = 0L
        compose.runOnIdle { token = old.beginAuthentication()!!; owner.value = second }
        compose.runOnIdle {
            assertNotSame(old, session)
            assertFalse(old.completeAuthentication(token))
            assertNull(old.beginAuthentication())
        }
        compose.onNodeWithText("Locked").assertIsDisplayed()
    }
}
