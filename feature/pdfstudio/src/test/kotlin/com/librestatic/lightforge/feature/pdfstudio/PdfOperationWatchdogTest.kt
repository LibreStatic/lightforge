package com.librestatic.lightforge.feature.pdfstudio

import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PdfOperationWatchdogTest {
    /** Deadlines fire only when the test advances time, so scheduler pauses cannot flake. */
    private class FakeClock {
        var now = 0L
            private set
        private val tasks = mutableListOf<Pair<Long, FutureTask<Unit>>>()

        val scheduler = PdfDeadlineScheduler { delay, task ->
            FutureTask<Unit> { task() }.also { tasks += (now + delay) to it }
        }

        fun advanceMillis(millis: Long) {
            now += TimeUnit.MILLISECONDS.toNanos(millis)
            tasks.filter { (due, task) -> due <= now && !task.isDone }
                .sortedBy { it.first }
                .forEach { it.second.run() }
        }
    }

    private fun watchdog(clock: FakeClock, kills: AtomicInteger) =
        PdfOperationWatchdog(nanoTime = { clock.now }, scheduler = clock.scheduler) {
            kills.incrementAndGet()
        }

    @Test
    fun completedAndThrowingCallsDisarmTheirTimers() {
        val clock = FakeClock()
        val kills = AtomicInteger()
        val watchdog = watchdog(clock, kills)
        repeat(30) { assertEquals(42, watchdog.run("same", 50) { 42 }) }
        assertTrue(runCatching { watchdog.run("throws", 50) { error("fixture") } }.isFailure)
        clock.advanceMillis(150)
        assertEquals(0, kills.get())
    }

    @Test
    fun blockedCallExpiresExactlyOnce() {
        val clock = FakeClock()
        val kills = AtomicInteger()
        val watchdog = watchdog(clock, kills)
        watchdog.run("blocked", 50) {
            clock.advanceMillis(49)
            assertEquals(0, kills.get())
            clock.advanceMillis(1)
            assertEquals(1, kills.get())
        }
        clock.advanceMillis(100)
        assertEquals(1, kills.get())
    }

    @Test
    fun cancellationNeverExtendsAnEarlierDeadline() {
        val clock = FakeClock()
        val kills = AtomicInteger()
        val watchdog = watchdog(clock, kills)
        watchdog.run("short", 50) {
            watchdog.cancel("short")
            clock.advanceMillis(50)
            assertEquals(1, kills.get())
        }
    }

    @Test
    fun cancellationShortensLongOperationAndIgnoresUnknownIds() {
        val clock = FakeClock()
        val kills = AtomicInteger()
        val watchdog = watchdog(clock, kills)
        watchdog.cancel("absent")
        watchdog.run("long", 60_000) {
            watchdog.cancel("long")
            clock.advanceMillis(4_999)
            assertEquals(0, kills.get())
            clock.advanceMillis(1)
            assertEquals(1, kills.get())
        }
        clock.advanceMillis(60_000)
        assertEquals(1, kills.get())
    }
}
