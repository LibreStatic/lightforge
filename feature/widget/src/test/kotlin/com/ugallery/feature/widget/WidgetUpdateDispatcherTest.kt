package com.ugallery.feature.widget

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class WidgetUpdateDispatcherTest {
    private val unexpected = Collections.synchronizedList(mutableListOf<Throwable>())
    private fun newDispatcher(timeoutMillis: Long = 8_000L) =
        WidgetUpdateDispatcher(timeoutMillis, onFailure = { unexpected += it })
    @After fun workerAssertionsRemainTestFailures() {
        assertTrue("Unexpected worker failure: $unexpected", unexpected.isEmpty())
    }

    @Test fun workAndDeliveryRunOffCallerAndFinishOnce() {
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val delivered = AtomicReference<Thread>()
        val finish = AtomicInteger()
        val done = CountDownLatch(1)
        newDispatcher().use { dispatcher ->
            dispatcher.submit(intArrayOf(7), { finish.incrementAndGet(); done.countDown() }) { ids, ticket ->
                assertArrayEquals(intArrayOf(7), ids)
                worker.set(Thread.currentThread())
                assertTrue(ticket.publish { delivered.set(Thread.currentThread()) })
            }
            await(done)
            assertNotSame(caller, worker.get())
            assertSame(worker.get(), delivered.get())
            assertEquals(1, finish.get())
        }
        assertEquals(1, finish.get())
    }

    @Test fun conflationPreservesUnionOfDifferentWidgetIdsAndFinishesEveryBroadcast() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(3)
        val finishes = List(3) { AtomicInteger() }
        val published = Collections.synchronizedList(mutableListOf<List<Int>>())
        newDispatcher().use { dispatcher ->
            try {
                dispatcher.submit(intArrayOf(1), { finishes[0].incrementAndGet(); done.countDown() }) { ids, ticket ->
                    started.countDown(); await(release)
                    ticket.publish { published += checkNotNull(ids).toList() }
                }
                await(started)
                dispatcher.submit(intArrayOf(2, 2), { finishes[1].incrementAndGet(); done.countDown() }) { _, _ ->
                    error("Replaced pending work must not execute")
                }
                dispatcher.submit(intArrayOf(3), { finishes[2].incrementAndGet(); done.countDown() }) { ids, ticket ->
                    ticket.publish { published += checkNotNull(ids).toList() }
                }
                assertEquals(1, finishes[1].get())
                release.countDown()
                await(done)
                assertEquals(listOf(listOf(1), listOf(2, 3)), published.toList())
                assertEquals(listOf(1, 1, 1), finishes.map { it.get() })
            } finally { release.countDown() }
        }
    }

    @Test fun failedWorkStillFinishesAndFollowingWidgetIsDelivered() {
        val failed = CountDownLatch(1)
        val following = CountDownLatch(1)
        val finishes = AtomicInteger()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        WidgetUpdateDispatcher(onFailure = { errors += it }).use { dispatcher ->
            dispatcher.submit(intArrayOf(1), { finishes.incrementAndGet(); failed.countDown() }) { _, _ ->
                throw IllegalStateException("owned load failure")
            }
            await(failed)
            dispatcher.submit(intArrayOf(2), { finishes.incrementAndGet(); following.countDown() }) { _, ticket ->
                assertTrue(ticket.publish {})
            }
            await(following)
            assertEquals("owned load failure", errors.single().message)
            assertEquals(2, finishes.get())
        }
    }

    @Test fun timeoutFinishesWhileNoncooperativeLoadRemainsBlockedAndRejectsLatePublication() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val finishes = AtomicInteger()
        val deliveries = AtomicInteger()
        newDispatcher(timeoutMillis = 500).use { dispatcher ->
            try {
                dispatcher.submit(intArrayOf(1), { finishes.incrementAndGet(); finished.countDown() }) { _, ticket ->
                    started.countDown()
                    awaitIgnoringInterrupts(release)
                    assertFalse(ticket.publish { deliveries.incrementAndGet() })
                    returned.countDown()
                }
                await(started)
                await(finished)
                assertEquals(1L, returned.count)
                assertEquals(1, finishes.get())
                release.countDown()
                await(returned)
                assertEquals(0, deliveries.get())
            } finally { release.countDown() }
        }
        assertEquals(1, finishes.get())
    }

    @Test fun blockedWorkerIsNotReplacedAndQueuedBroadcastAlsoExpires() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val executions = AtomicInteger()
        val finishes = AtomicInteger()
        newDispatcher(timeoutMillis = 500).use { dispatcher ->
            try {
                dispatcher.submit(intArrayOf(1), { finishes.incrementAndGet(); firstFinished.countDown() }) { _, ticket ->
                    executions.incrementAndGet(); started.countDown()
                    awaitIgnoringInterrupts(release)
                    assertFalse(ticket.publish { error("Late delivery") })
                    returned.countDown()
                }
                await(started); await(firstFinished)
                dispatcher.submit(intArrayOf(2), { finishes.incrementAndGet(); secondFinished.countDown() }) { _, _ ->
                    executions.incrementAndGet()
                }
                await(secondFinished)
                assertEquals(1, executions.get())
                assertEquals(2, finishes.get())
                release.countDown(); await(returned)
            } finally { release.countDown() }
        }
        assertEquals(2, finishes.get())
    }

    @Test fun disableCancelsActiveAndPendingThenLaterEnableCanDeliverAgain() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val done = CountDownLatch(2)
        val next = CountDownLatch(1)
        val finishes = AtomicInteger()
        val deliveries = AtomicInteger()
        newDispatcher().use { dispatcher ->
            try {
                dispatcher.submit(intArrayOf(1), { finishes.incrementAndGet(); done.countDown() }) { _, ticket ->
                    started.countDown(); awaitIgnoringInterrupts(release)
                    assertFalse(ticket.publish { deliveries.incrementAndGet() }); returned.countDown()
                }
                await(started)
                dispatcher.submit(intArrayOf(2), { finishes.incrementAndGet(); done.countDown() }) { _, _ ->
                    error("Disabled pending job must not execute")
                }
                dispatcher.cancelAll(); dispatcher.cancelAll()
                await(done)
                release.countDown(); await(returned)
                dispatcher.submit(intArrayOf(3), { next.countDown() }) { _, ticket ->
                    assertTrue(ticket.publish { deliveries.incrementAndGet() })
                }
                await(next)
                assertEquals(1, deliveries.get())
                assertEquals(2, finishes.get())
            } finally { release.countDown() }
        }
    }

    @Test fun boundedPendingIdsOverflowToAllInstalledWidgetsRatherThanLosingIds() {
        val done = CountDownLatch(1)
        val all = AtomicReference<Boolean>()
        newDispatcher().use { dispatcher ->
            dispatcher.submit(IntArray(65) { it + 1 }, { done.countDown() }) { ids, ticket ->
                all.set(ids == null)
                assertTrue(ticket.publish {})
            }
            await(done)
            assertEquals(true, all.get())
        }
    }

    @Test fun closedDispatcherFinishesWithoutExecutingOrPublishing() {
        val dispatcher = newDispatcher()
        dispatcher.close()
        val finishes = AtomicInteger()
        dispatcher.submit(intArrayOf(1), { finishes.incrementAndGet() }) { _, _ -> error("Closed") }
        assertEquals(1, finishes.get())
    }

    private fun await(latch: CountDownLatch) { assertTrue("Widget test deadline", latch.await(3, TimeUnit.SECONDS)) }
    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            try { latch.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { /* Model noncooperative provider IO. */ }
        }
        check(latch.count == 0L) { "Test-owned load must be released" }
    }
}
