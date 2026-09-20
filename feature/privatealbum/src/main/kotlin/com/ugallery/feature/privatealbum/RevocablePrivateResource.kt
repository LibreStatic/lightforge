package com.ugallery.feature.privatealbum

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Admission closes synchronously; physical close waits for cancellable work/atomic commits. */
enum class PrivateIndexAccessState { Locked, Ready, Unavailable }
class PrivateIndexLockedException : IllegalStateException("Private index session requires authentication")

/** A small delivery critical section, never a Room lease or an I/O lock. */
internal interface PrivateReaderAdmission : AutoCloseable {
    fun <R> withAdmission(block: () -> R): R
}

internal class RevocablePrivateResource<T : AutoCloseable>(
    private val cleanupScope: CoroutineScope,
    initiallyReady: Boolean = false,
    private val authenticationFailure: (Throwable) -> Boolean = { false },
) {
    private val monitor = Any()
    private val mutex = Mutex()
    private val active = mutableSetOf<Job>()
    private var generation = 0L
    private var disposed = false
    private var resource: T? = null // Access only under mutex.
    private val mutableState = MutableStateFlow(if (initiallyReady) PrivateIndexAccessState.Ready else PrivateIndexAccessState.Locked)
    val state: StateFlow<PrivateIndexAccessState> = mutableState.asStateFlow()
    private val revision = MutableStateFlow(0L)
    val epoch: Long get() = synchronized(monitor) { generation }

    private fun checkAdmission(expected: Long) = synchronized(monitor) {
        if (disposed || generation != expected || mutableState.value != PrivateIndexAccessState.Ready)
            throw PrivateIndexLockedException()
    }

    private val readers = mutableMapOf<Any, () -> Unit>()

    fun registerReader(expected: Long, onInvalidated: () -> Unit): PrivateReaderAdmission {
        val token = Any()
        synchronized(monitor) { checkAdmission(expected); readers[token] = onInvalidated }
        return object : PrivateReaderAdmission {
            override fun <R> withAdmission(block: () -> R): R = synchronized(monitor) {
                checkAdmission(expected)
                if (!readers.containsKey(token)) throw PrivateIndexLockedException()
                block()
            }
            override fun close() { synchronized(monitor) { readers.remove(token) } }
        }
    }

    /** Close delivery before deleting sources, even if physical reader close is queued. */
    fun invalidateReaders() {
        val invalidated = synchronized(monitor) { readers.values.toList().also { readers.clear() } }
        invalidated.forEach { it() }
    }

    fun revoke() {
        val (jobs, invalidated) = synchronized(monitor) {
            generation++
            mutableState.value = PrivateIndexAccessState.Locked
            revision.value++
            active.toList() to readers.values.toList().also { readers.clear() }
        }
        invalidated.forEach { it() }
        jobs.forEach { it.cancel(CancellationException("Private session revoked")) }
        scheduleClose()
    }

    private fun scheduleClose() {
        cleanupScope.launch {
            try { closeRevoked() }
            catch (_: Exception) { synchronized(monitor) {
                if (mutableState.value == PrivateIndexAccessState.Locked)
                    mutableState.value = PrivateIndexAccessState.Unavailable
            } }
        }
    }

    suspend fun closeRevoked() = mutex.withLock {
        val close = synchronized(monitor) { mutableState.value != PrivateIndexAccessState.Ready }
        if (close) { resource?.close(); resource = null }
    }

    fun dispose() {
        synchronized(monitor) { disposed = true }
        revoke()
    }

    /** Called only after the platform authenticates; never reuse an earlier open/password. */
    suspend fun open(expectedEpoch: Long = epoch, opener: suspend () -> T) = coroutineScope {
        val (token, invalidated) = synchronized(monitor) {
            if (disposed || generation != expectedEpoch) throw PrivateIndexLockedException()
            generation++
            mutableState.value = PrivateIndexAccessState.Locked
            active.toList().forEach { it.cancel(CancellationException("Private session replaced")) }
            generation to readers.values.toList().also { readers.clear() }
        }
        invalidated.forEach { it() }
        try { mutex.withLock {
            var opened: T? = null
            try {
                resource?.close(); resource = null
                ensureActive()
                synchronized(monitor) { if (disposed || generation != token) throw PrivateIndexLockedException() }
                opened = opener()
                ensureActive()
                synchronized(monitor) {
                    if (disposed || generation != token) throw PrivateIndexLockedException()
                    resource = opened
                    opened = null
                    mutableState.value = PrivateIndexAccessState.Ready
                    revision.value++
                }
            } catch (failure: Throwable) {
                opened?.close()
                synchronized(monitor) {
                    if (generation == token) {
                        mutableState.value = if (failure is CancellationException || failure is PrivateIndexLockedException || authenticationFailure(failure))
                            PrivateIndexAccessState.Locked else PrivateIndexAccessState.Unavailable
                        revision.value++
                    }
                }
                throw failure
            }
        } } finally {
            // Cancellation while waiting for the mutex also owns a close obligation.
            if (state.value != PrivateIndexAccessState.Ready) scheduleClose()
        }
    }

    suspend fun <R> use(expectedEpoch: Long? = null, write: Boolean = false, block: suspend (T) -> R): R = coroutineScope {
        val expected = expectedEpoch ?: epoch
        val job = currentCoroutineContext().job
        synchronized(monitor) { checkAdmission(expected); active.add(job) }
        try {
            mutex.withLock {
                ensureActive()
                checkAdmission(expected)
                try {
                    val result = block(requireNotNull(resource))
                    ensureActive()
                    checkAdmission(expected)
                    result
                } finally {
                    if (write) synchronized(monitor) { revision.value++ }
                }
            }
        } finally { synchronized(monitor) { active.remove(job) } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <R> observe(block: suspend (T) -> R): Flow<R> = revision.transformLatest {
        if (state.value == PrivateIndexAccessState.Ready) {
            try { emit(use(block = block)) }
            catch (_: PrivateIndexLockedException) { /* A newer state will refresh after authentication. */ }
        }
    }
}
