package com.ugallery.feature.pdfstudio

import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs a deadline check after a delay; replaceable so JVM tests can drive a fake clock. */
internal fun interface PdfDeadlineScheduler {
    fun schedule(delayNanos: Long, task: () -> Unit): Future<*>
}

private val deadlineExecutor by lazy {
    ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "pdf-operation-deadline").apply { isDaemon = true }
        }
        .apply { removeOnCancelPolicy = true }
}

/** Wall-clock leases outlive Service unbinding and do not rely on parser cooperation. */
internal class PdfOperationWatchdog(
    private val nanoTime: () -> Long = System::nanoTime,
    private val scheduler: PdfDeadlineScheduler = PdfDeadlineScheduler { delay, task ->
        deadlineExecutor.schedule(task, delay, TimeUnit.NANOSECONDS)
    },
    private val terminate: () -> Unit,
) {
    private class Lease(var deadline: Long) {
        var future: Future<*>? = null
    }

    private val active = mutableMapOf<String, Lease>()

    fun <T> run(id: String, timeoutMillis: Long, block: () -> T): T {
        require(timeoutMillis in 1..300_000)
        val lease =
            synchronized(active) {
                check(id !in active)
                Lease(nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)).also {
                    active[id] = it
                    schedule(id, it)
                }
            }
        return try {
            block()
        } finally {
            synchronized(active) {
                if (active[id] === lease) active.remove(id)
                lease.future?.cancel(false)
            }
        }
    }

    /** Cooperative cancellation gets five seconds to finish; never extends an existing deadline. */
    fun cancel(id: String) {
        synchronized(active) {
            val lease = active[id] ?: return
            val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(5)
            if (deadline < lease.deadline) {
                lease.future?.cancel(false)
                lease.deadline = deadline
                schedule(id, lease)
            }
        }
    }

    private fun schedule(id: String, lease: Lease) {
        lease.future =
            scheduler.schedule((lease.deadline - nanoTime()).coerceAtLeast(0)) {
                synchronized(active) {
                    if (active[id] === lease && nanoTime() >= lease.deadline) {
                        active.remove(id)
                        // The check and termination share the completion lock: no stale timer
                        // kill.
                        terminate()
                    }
                }
            }
    }
}
