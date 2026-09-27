package com.librestatic.lightforge.feature.widget

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Two bounded lanes: one serial widget worker and one deadline watchdog; never replacement workers. */
internal class WidgetUpdateDispatcher(
    private val timeoutMillis: Long = 8_000L,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    init { require(timeoutMillis in 1L..8_000L) }
    private val lock = Any()
    private val worker = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(1), threads("lightforge-widget-worker"))
    private val watchdog = ScheduledThreadPoolExecutor(1, threads("lightforge-widget-deadline")).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private var pending: Request? = null
    private var active: Request? = null
    private var draining = false
    private var closed = false

    /** Null IDs means refresh all currently installed widgets, also the bounded union overflow case. */
    fun submit(ids: IntArray?, finish: () -> Unit, work: (IntArray?, Ticket) -> Unit) {
        val request = Request(normalize(ids), finish, work)
        if (request.ids?.isEmpty() == true) { request.complete(); return }
        var replaced: Request? = null
        var rejected = false
        synchronized(lock) {
            if (closed) rejected = true else {
                replaced = pending
                request.ids = union(pending?.ids, request.ids, pending != null)
                pending = request
                request.timeout = watchdog.schedule({ expire(request) }, timeoutMillis, TimeUnit.MILLISECONDS)
                if (!draining) {
                    draining = true
                    worker.execute(::drain)
                }
            }
        }
        // Only the latest pending broadcast owns the conflated work; finish every replaced result.
        replaced?.complete()
        if (rejected) request.complete()
    }

    fun cancelAll() {
        val requests = synchronized(lock) {
            listOfNotNull(active, pending).also { pending = null }
        }
        requests.forEach(Request::complete)
    }

    private fun expire(request: Request) {
        synchronized(lock) { if (pending === request) pending = null }
        request.complete()
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) { draining = false; return }
                active = next
                next
            }
            // Cancellation can interrupt cooperative work; discard that flag before another batch.
            Thread.interrupted()
            request.thread = Thread.currentThread()
            try {
                if (request.ticket.isActive) request.work(request.ids, request.ticket)
            } catch (failure: Throwable) {
                runCatching { onFailure(failure) }
            } finally {
                request.thread = null
                request.complete()
                synchronized(lock) { if (active === request) active = null }
            }
        }
    }

    /**
     * Guard directly before each publication. An already-entered platform Binder call cannot be
     * revoked. No lock spans publication, so a slow provider cannot hold onReceive or the watchdog.
     */
    internal class Ticket internal constructor(private val deadlineNanos: Long) {
        private val cancelled = AtomicBoolean(false)
        val isActive: Boolean get() = !cancelled.get() && System.nanoTime() - deadlineNanos < 0
        fun publish(action: () -> Unit): Boolean {
            if (!isActive) return false
            action()
            return true
        }
        internal fun cancel() { cancelled.set(true) }
    }

    private inner class Request(
        var ids: IntArray?,
        private val finish: () -> Unit,
        val work: (IntArray?, Ticket) -> Unit,
    ) {
        val ticket = Ticket(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
        private val completed = AtomicBoolean(false)
        @Volatile var timeout: ScheduledFuture<*>? = null
        @Volatile var thread: Thread? = null
        fun complete() {
            ticket.cancel()
            if (completed.compareAndSet(false, true)) {
                timeout?.cancel(false)
                // No CancellationSignal.cancel here: it can enter a remote Binder on this lane.
                thread?.takeIf { it !== Thread.currentThread() }?.interrupt()
                runCatching(finish).onFailure { runCatching { onFailure(it) } }
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancelAll()
        worker.shutdown()
        watchdog.shutdown()
    }

    private companion object {
        const val MAX_PENDING_IDS = 64
        fun threads(name: String) = ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
        fun normalize(ids: IntArray?): IntArray? {
            if (ids == null) return null
            val result = linkedSetOf<Int>()
            for (id in ids) if (id > 0) {
                result += id
                if (result.size > MAX_PENDING_IDS) return null
            }
            return result.toIntArray()
        }
        fun union(previous: IntArray?, incoming: IntArray?, hasPrevious: Boolean): IntArray? = when {
            !hasPrevious -> incoming
            previous == null || incoming == null -> null
            else -> normalize(previous + incoming)
        }
    }
}
