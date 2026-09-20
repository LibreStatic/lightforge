package com.ugallery.feature.pdfstudio

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Wall-clock leases outlive Service unbinding and do not rely on parser cooperation. */
internal class PdfOperationWatchdog(private val terminate: () -> Unit) {
    private val scheduler =
        ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "pdf-operation-deadline").apply { isDaemon = true }
            }
            .apply { removeOnCancelPolicy = true }

    private class Lease(var deadline: Long) {
        var future: ScheduledFuture<*>? = null
    }

    private val active = mutableMapOf<String, Lease>()

    fun <T> run(id: String, timeoutMillis: Long, block: () -> T): T {
        require(timeoutMillis in 1..300_000)
        val lease =
            synchronized(active) {
                check(id !in active)
                Lease(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)).also {
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
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            if (deadline < lease.deadline) {
                lease.future?.cancel(false)
                lease.deadline = deadline
                schedule(id, lease)
            }
        }
    }

    private fun schedule(id: String, lease: Lease) {
        lease.future =
            scheduler.schedule(
                {
                    synchronized(active) {
                        if (active[id] === lease && System.nanoTime() >= lease.deadline) {
                            active.remove(id)
                            // The check and termination share the completion lock: no stale timer
                            // kill.
                            terminate()
                        }
                    }
                },
                (lease.deadline - System.nanoTime()).coerceAtLeast(0),
                TimeUnit.NANOSECONDS,
            )
    }
}
