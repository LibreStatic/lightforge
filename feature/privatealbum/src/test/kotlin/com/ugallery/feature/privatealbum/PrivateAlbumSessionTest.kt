package com.ugallery.feature.privatealbum

import org.junit.Assert.*
import org.junit.Test

class PrivateAlbumSessionTest {
    private fun session() = PrivateAlbumSession().apply { setActive(true); setForeground(true) }
    private fun unlock(session: PrivateAlbumSession): Long {
        assertTrue(session.completeAuthentication(session.beginAuthentication()!!))
        return session.accessToken()!!
    }

    @Test fun freshSessionAndRecreatedSessionNeverInheritAuthorization() {
        val old = session(); unlock(old)
        assertFalse(session().isUnlocked)
        assertNull(session().accessToken())
    }

    @Test fun backgroundInvalidatesAuthorizationAndDoesNotUnlockOnReturn() {
        val session = session(); val access = unlock(session)
        session.setForeground(false)
        assertFalse(session.isUnlocked); assertFalse(session.hasAccess(access))
        session.setForeground(true)
        assertFalse(session.isUnlocked)
        assertTrue(unlock(session) != access)
    }

    @Test fun stoppedPromptCannotUnlockAfterForegroundReturns() {
        val session = session(); val token = session.beginAuthentication()!!
        session.setForeground(false); session.setForeground(true)
        assertFalse(session.completeAuthentication(token))
        assertFalse(session.isUnlocked)
    }

    @Test fun routeExitInvalidatesPromptEvenAfterReentry() {
        val session = session(); val token = session.beginAuthentication()!!
        session.setActive(false); session.setActive(true)
        assertFalse(session.completeAuthentication(token))
        assertFalse(session.isUnlocked)
    }

    @Test fun onlyOnePromptAndOneSuccessPerToken() {
        val session = session(); val token = session.beginAuthentication()!!
        assertNull(session.beginAuthentication())
        assertFalse(session.completeAuthentication(token + 1))
        assertTrue(session.completeAuthentication(token))
        assertFalse(session.completeAuthentication(token))
    }

    @Test fun failedSampleMayRetryButTerminalErrorRejectsLateSuccess() {
        val session = session(); val token = session.beginAuthentication()!!
        assertTrue(session.isPending(token))
        assertTrue(session.cancelAuthentication(token))
        val next = session.beginAuthentication()!!
        assertFalse(session.cancelAuthentication(token))
        assertFalse(session.completeAuthentication(token))
        assertTrue(session.completeAuthentication(next))
    }

    @Test fun disposedSessionCannotBeReactivatedByOldLifecycleOrCallback() {
        val session = session(); val token = session.beginAuthentication()!!
        session.dispose(); session.setActive(true); session.setForeground(true)
        assertFalse(session.completeAuthentication(token)); assertNull(session.beginAuthentication())
    }

    @Test fun inactiveAndBackgroundSessionsCannotStartAuthentication() {
        val session = PrivateAlbumSession()
        assertNull(session.beginAuthentication())
        session.setActive(true); assertNull(session.beginAuthentication())
        session.setForeground(true); assertNotNull(session.beginAuthentication())
    }
}
