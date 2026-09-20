package com.ugallery.feature.semanticsearch

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.sync.Mutex

/** Serializes index visibility changes; native execution itself never holds this mutex. */
internal object SemanticIndexCommitGate { val mutex = Mutex() }

/** Revocable leases are thread-independent: coroutines may resume on a different IO thread. */
internal object SemanticModelAccess {
    private class Entry(val directory: File, var users: Int = 0, var revoked: Boolean = false, val leases: MutableSet<Lease> = mutableSetOf())
    private val entries = mutableMapOf<String, Entry>()
    private val epochs = mutableMapOf<String, Long>()
    @Synchronized fun epoch(directory: File): Long = epochs[directory.canonicalPath] ?: 0L
    @Synchronized fun acquire(directory: File): Lease {
        val key = directory.canonicalPath
        val entry = entries.getOrPut(key) { Entry(directory) }
        check(!entry.revoked) { "Semantic model was removed" }
        entry.users++
        return Lease(key) { check(!entry.revoked) { "Semantic model was removed" } }.also { entry.leases += it }
    }
    fun revoke(directory: File) {
        val leases = synchronized(this) {
            val key = directory.canonicalPath
            epochs[key] = (epochs[key] ?: 0L) + 1L
            val entry = entries.getOrPut(key) { Entry(directory) }
            entry.revoked = true
            directory.resolve("complete.marker").delete()
            if (entry.users == 0) { directory.deleteRecursively(); entries.remove(key) }
            entry.leases.toList()
        }
        // Never invoke native owner callbacks while holding the registry monitor.
        leases.forEach { it.notifyRevoked() }
    }
    /** Atomic within the lease registry: no acquisition/deletion can interleave publication. */
    @Synchronized fun publish(directory: File, staging: File, expectedEpoch: Long) {
        val key = directory.canonicalPath
        check((epochs[key] ?: 0L) == expectedEpoch) { "Semantic installation was removed" }
        check(canReplace(directory)) { "Semantic model is currently in use" }
        val previous = File(directory.parentFile, directory.name + ".previous-" + java.util.UUID.randomUUID())
        val hadPrevious = directory.exists()
        if (hadPrevious) check(directory.renameTo(previous)) { "Unable to retain previous semantic model" }
        if (!staging.renameTo(directory)) {
            check(!hadPrevious || previous.renameTo(directory)) { "Previous semantic model retained at ${previous.name}" }
            error("Unable to activate downloaded model")
        }
        epochs[key] = expectedEpoch + 1L
        if (hadPrevious) previous.deleteRecursively()
    }
    @Synchronized fun canReplace(directory: File): Boolean = entries[directory.canonicalPath]?.users.let { it == null || it == 0 }
    @Synchronized private fun release(key: String, lease: Lease) {
        val entry = entries[key] ?: return
        entry.leases.remove(lease)
        entry.users--
        if (entry.users == 0) {
            if (entry.revoked) entry.directory.deleteRecursively()
            entries.remove(key)
        }
    }
    class Lease internal constructor(private val key: String, private val validate: () -> Unit) : Closeable {
        private var closed = false
        private var onRevocation: (() -> Unit)? = null
        fun onRevoked(callback: () -> Unit) {
            val revoked = synchronized(SemanticModelAccess) {
                onRevocation = callback
                runCatching { validate() }.isFailure
            }
            if (revoked) callback()
        }
        internal fun notifyRevoked() { synchronized(SemanticModelAccess) { onRevocation }?.invoke() }
        fun checkCurrent() = synchronized(SemanticModelAccess) { check(!closed); validate() }
        override fun close() = synchronized(SemanticModelAccess) { if (!closed) { closed = true; release(key, this) } }
    }
}
