package com.librestatic.lightforge.feature.privatealbum

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateViewerAdmissionTest {
    @Test fun revokeAndReplacementInvalidateOriginalReadersWithoutDatabaseLease() = runTest {
        val lease=RevocablePrivateResource<AutoCloseable>(backgroundScope)
        lease.open { AutoCloseable {} }
        var invalidated=0
        val first=lease.registerReader(lease.epoch) { invalidated++ }
        assertEquals(7,first.withAdmission { 7 })
        lease.use { /* Playback has no resource lease; database operations proceed. */ }
        lease.revoke()
        assertEquals(1,invalidated)
        assertThrows(PrivateIndexLockedException::class.java) { first.withAdmission { fail() } }
        lease.open { AutoCloseable {} }
        assertThrows(PrivateIndexLockedException::class.java) { first.withAdmission { fail() } }
        val next=lease.registerReader(lease.epoch) { invalidated++ }
        lease.open { AutoCloseable {} }
        assertEquals(2,invalidated)
        assertThrows(PrivateIndexLockedException::class.java) { next.withAdmission { fail() } }
    }
    @Test fun deletionClosesReaderAdmissionWithoutLockingIndexAndCloseUnregisters() = runTest {
        val lease=RevocablePrivateResource<AutoCloseable>(backgroundScope)
        lease.open { AutoCloseable {} }
        var invalidated=0
        val detached=lease.registerReader(lease.epoch) { invalidated++ };detached.close();detached.close()
        val selected=lease.registerReader(lease.epoch) { invalidated++ }
        lease.invalidateReaders()
        assertEquals(1,invalidated)
        assertEquals(PrivateIndexAccessState.Ready,lease.state.value)
        assertThrows(PrivateIndexLockedException::class.java) { selected.withAdmission { fail() } }
        assertThrows(PrivateIndexLockedException::class.java) { detached.withAdmission { fail() } }
        val next=lease.registerReader(lease.epoch) { invalidated++ }
        assertEquals(9,next.withAdmission {9});lease.dispose()
        assertEquals(2,invalidated)
    }
    @Test fun admittedCopyAndRevocationAreSerializedButInFlightIoDoesNotBlockRevocation() = runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val lease=RevocablePrivateResource<AutoCloseable>(scope)
            lease.open { AutoCloseable {} }
            val invalidated=AtomicBoolean(false)
            val gate=lease.registerReader(lease.epoch) { invalidated.set(true) }
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val copied=AtomicBoolean(false)
            val job=async(Dispatchers.IO) {
                // Simulated decoder I/O has no admission or Room monitor held.
                gate.withAdmission { Unit };entered.countDown()
                check(release.await(5,TimeUnit.SECONDS))
                runCatching { gate.withAdmission { copied.set(true) } }.exceptionOrNull()
            }
            check(entered.await(5,TimeUnit.SECONDS));lease.revoke()
            assertTrue(invalidated.get());release.countDown()
            assertTrue(job.await() is PrivateIndexLockedException);assertFalse(copied.get())
        } finally { scope.cancel() }
    }
    @Test fun conflatedBitmapsReleaseOnlyUnpresentedFramesAndDisposeOnce() {
        val released=mutableListOf<Any>()
        val owner=PrivateViewerFrameOwner<Any> { released.add(it) }
        val a=Any();val b=Any();val c=Any();val d=Any()
        owner.offer(a);owner.present(a)
        owner.offer(b);owner.offer(c)
        assertEquals(listOf(b),released)
        owner.releasePresented(a);owner.present(c)
        assertEquals(listOf(b,a),released)
        owner.offer(d);owner.close();owner.close()
        assertEquals(listOf(b,a,d),released)
        owner.releasePresented(c)
        assertEquals(listOf(b,a,d,c),released)
        assertThrows(IllegalStateException::class.java) { owner.offer(Any()) }
    }

}
