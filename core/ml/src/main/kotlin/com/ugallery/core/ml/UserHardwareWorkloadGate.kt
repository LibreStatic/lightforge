package com.ugallery.core.ml

import java.util.UUID

enum class UserHardwareWorkload { VideoViewer, VideoEditor, PhotoEditor, VideoExport }

class UserHardwareLease internal constructor(
    val token: String,
    val workload: UserHardwareWorkload,
    val activatedGate: Boolean,
)

/** Process-wide, reference-counted priority gate for interactive and foreground media work. */
object UserHardwareWorkloadGate {
    private val lock = Any()
    private val leases = mutableMapOf<String, UserHardwareWorkload>()

    fun acquire(workload: UserHardwareWorkload): UserHardwareLease = synchronized(lock) {
        val activated = leases.isEmpty()
        val token = UUID.randomUUID().toString()
        leases[token] = workload
        UserHardwareLease(token, workload, activated)
    }

    /** Returns true only when this release transitions the gate back to idle. */
    fun release(lease: UserHardwareLease): Boolean = synchronized(lock) {
        if (leases.remove(lease.token) == null) return@synchronized false
        leases.isEmpty()
    }

    fun isActive(): Boolean = synchronized(lock) { leases.isNotEmpty() }
    fun activeWorkloads(): Set<UserHardwareWorkload> = synchronized(lock) { leases.values.toSet() }

    internal fun resetForTests() = synchronized(lock) { leases.clear() }
}
