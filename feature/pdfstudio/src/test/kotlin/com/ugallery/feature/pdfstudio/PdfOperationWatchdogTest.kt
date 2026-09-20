package com.ugallery.feature.pdfstudio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PdfOperationWatchdogTest {
    @Test
    fun completedAndThrowingCallsDisarmTheirTimers() {
        val kills = AtomicInteger()
        val watchdog = PdfOperationWatchdog { kills.incrementAndGet() }
        repeat(30) { assertEquals(42, watchdog.run("same", 50) { 42 }) }
        assertTrue(runCatching { watchdog.run("throws", 50) { error("fixture") } }.isFailure)
        Thread.sleep(150)
        assertEquals(0, kills.get())
    }

    @Test
    fun blockedCallExpiresExactlyOnce() {
        val killed = CountDownLatch(1)
        val kills = AtomicInteger()
        val watchdog = PdfOperationWatchdog {
            kills.incrementAndGet()
            killed.countDown()
        }
        watchdog.run("blocked", 50) { assertTrue(killed.await(3, TimeUnit.SECONDS)) }
        Thread.sleep(100)
        assertEquals(1, kills.get())
    }

    @Test
    fun cancellationNeverExtendsAnEarlierDeadline() {
        val killed = CountDownLatch(1)
        val watchdog = PdfOperationWatchdog { killed.countDown() }
        watchdog.run("short", 50) {
            watchdog.cancel("short")
            assertTrue(killed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancellationShortensLongOperationAndIgnoresUnknownIds() {
        val killed = CountDownLatch(1)
        val watchdog = PdfOperationWatchdog { killed.countDown() }
        watchdog.cancel("absent")
        watchdog.run("long", 60_000) {
            watchdog.cancel("long")
            assertFalse(killed.await(1, TimeUnit.SECONDS))
            assertTrue(killed.await(8, TimeUnit.SECONDS))
        }
    }
}
