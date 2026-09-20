package com.ugallery.feature.privatealbum

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RevocablePrivateResourceTest {
    private class Resource : AutoCloseable {
        var closed = false
        var count = 0
        override fun close() { closed = true }
    }
    @Test fun lockedNeverOpensAndReopenReplacesResource() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        assertEquals(PrivateIndexAccessState.Locked, lease.state.value)
        assertTrue(runCatching { lease.use { it.count } }.exceptionOrNull() is PrivateIndexLockedException)
        val first = Resource(); lease.open { first }
        val old = lease.epoch
        lease.revoke()
        assertEquals(PrivateIndexAccessState.Locked, lease.state.value)
        lease.closeRevoked(); assertTrue(first.closed)
        val second = Resource(); lease.open { second }
        assertTrue(runCatching { lease.use(old) { it.count++ } }.exceptionOrNull() is PrivateIndexLockedException)
        assertEquals(0, lease.use { it.count })
        lease.dispose(); lease.closeRevoked(); assertTrue(second.closed)
        assertTrue(runCatching { lease.open { Resource() } }.isFailure)
    }
    @Test fun revocationCancelsWorkButWaitsForAtomicFinalization() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        val resource = Resource(); lease.open { resource }
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var published = false
        val work = async {
            lease.use(write = true) {
                withContext(NonCancellable) {
                    entered.complete(Unit); finish.await()
                    assertFalse(it.closed); it.count++
                }
            }
            published = true
        }
        entered.await(); lease.revoke()
        val close = launch { lease.closeRevoked() }
        runCurrent(); assertFalse(resource.closed); assertFalse(close.isCompleted)
        finish.complete(Unit); work.join(); close.join()
        assertTrue(work.isCancelled); assertFalse(published)
        assertEquals(1, resource.count); assertTrue(resource.closed)
    }
    @Test fun staleQueuedOpenAndRevokedInFlightOpenNeverPublishReady() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        val old = lease.epoch; lease.revoke()
        var created = false
        assertTrue(runCatching { lease.open(old) { created = true; Resource() } }.isFailure)
        assertFalse(created)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val resource = Resource()
        val opening = async {
            runCatching { lease.open { entered.complete(Unit); release.await(); resource } }
        }
        entered.await(); lease.revoke(); release.complete(Unit)
        assertTrue(opening.await().exceptionOrNull() is PrivateIndexLockedException)
        assertTrue(resource.closed); assertEquals(PrivateIndexAccessState.Locked, lease.state.value)
    }
    @Test fun observerReleasesLeaseAndRefreshesOnlyAfterWritesOrReopen() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        val values = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lease.observe { it.count }.toList(values) }
        val first = Resource(); lease.open { first }; runCurrent()
        assertEquals(listOf(0), values)
        lease.use(write = true) { it.count = 2 }; runCurrent()
        assertEquals(listOf(0, 2), values)
        lease.use { it.count }; runCurrent(); assertEquals(2, values.size)
        lease.revoke(); lease.closeRevoked(); runCurrent()
        lease.open { Resource().apply { count = 7 } }; runCurrent()
        assertEquals(listOf(0, 2, 7), values)
    }
    @Test fun corruptOpenIsUnavailableAuthenticationFailureIsLocked() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope) { it is SecurityException }
        assertTrue(runCatching { lease.open { throw IllegalStateException("corrupt") } }.isFailure)
        assertEquals(PrivateIndexAccessState.Unavailable, lease.state.value)
        assertTrue(runCatching { lease.open { throw SecurityException() } }.isFailure)
        assertEquals(PrivateIndexAccessState.Locked, lease.state.value)
    }
    @Test fun queuedOperationCannotUseNewDatabaseAfterRevocation() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        lease.open { Resource() }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = launch { lease.use { withContext(NonCancellable) { entered.complete(Unit); release.await() } } }
        entered.await()
        var enteredSecond = false
        val second = launch { lease.use { enteredSecond = true } }
        runCurrent(); lease.revoke(); release.complete(Unit)
        first.join(); second.join(); lease.closeRevoked(); lease.open { Resource() }
        assertFalse(enteredSecond); assertTrue(second.isCancelled)
    }
    @Test fun cancelledQueuedOpenStillClosesOldResourceAfterAtomicWork() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        val resource = Resource(); lease.open { resource }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work = launch { lease.use { withContext(NonCancellable) { entered.complete(Unit); release.await() } } }
        entered.await()
        val opening = launch { lease.open { Resource() } }
        runCurrent(); opening.cancelAndJoin()
        assertEquals(PrivateIndexAccessState.Locked, lease.state.value)
        assertFalse(resource.closed)
        release.complete(Unit); work.join(); runCurrent()
        assertTrue(resource.closed)
    }

    @Test fun uiLifecycleRevokesDatabaseAdmissionBeforeReturning() = runTest {
        val lease = RevocablePrivateResource<Resource>(backgroundScope)
        val session = PrivateAlbumSession { lease.revoke() }
        session.setActive(true); session.setForeground(true)
        assertTrue(session.completeAuthentication(requireNotNull(session.beginAuthentication())))
        val resource = Resource(); lease.open { resource }
        session.setForeground(false)
        assertFalse(session.isUnlocked)
        assertEquals(PrivateIndexAccessState.Locked, lease.state.value)
        assertTrue(runCatching { lease.use { it.count++ } }.exceptionOrNull() is PrivateIndexLockedException)
        lease.closeRevoked(); assertTrue(resource.closed)
        session.setForeground(true); assertFalse(session.isUnlocked)
    }

}
