package com.ugallery.feature.privatealbum

import com.ugallery.core.security.PrivateSeekableReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PrivateViewerMetadata(
    val mediaId: Long, val displayName: String, val mimeType: String,
    val mediaKind: String, val width: Int, val height: Int,
    val durationMillis: Long, val plaintextBytes: Long,
)

internal interface PrivateViewerSource : AutoCloseable {
    val metadata: PrivateViewerMetadata
    val valid: StateFlow<Boolean>
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

/** Reader ownership is independent from both composition cancellation and the Room mutex. */
internal class SessionPrivateViewerSource(private val cleanupScope: CoroutineScope) : PrivateViewerSource {
    private val ownership = Any()
    private val mutableValid = MutableStateFlow(true)
    override val valid = mutableValid.asStateFlow()
    private var admission: PrivateReaderAdmission? = null
    private var reader: PrivateSeekableReader? = null
    private var verifiedMetadata: PrivateViewerMetadata? = null
    override val metadata: PrivateViewerMetadata get() = synchronized(ownership) {
        requireNotNull(verifiedMetadata)
    }

    fun bind(value: PrivateReaderAdmission) {
        synchronized(ownership) {
            check(admission == null)
            if (mutableValid.value) admission = value else value.close()
        }
    }

    fun attach(value: PrivateSeekableReader, metadata: PrivateViewerMetadata) {
        val accepted = synchronized(ownership) {
            if (!mutableValid.value) false else {
                check(reader == null)
                reader = value; verifiedMetadata = metadata; true
            }
        }
        if (!accepted) { value.close(); throw PrivateIndexLockedException() }
    }

    fun checkValid() { if (!mutableValid.value) throw PrivateIndexLockedException() }

    override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
        val (handle, gate) = synchronized(ownership) {
            checkValid(); requireNotNull(reader) to requireNotNull(admission)
        }
        gate.withAdmission { checkValid() }
        val scratch = ByteArray(minOf(length, 64 * 1024))
        try {
            val count = handle.readAt(position, scratch, 0, scratch.size)
            // No I/O lock is held here; revoke and this final copy share the admission monitor.
            return gate.withAdmission {
                checkValid()
                if (count > 0) scratch.copyInto(target, offset, 0, count)
                count
            }
        } finally { scratch.fill(0) }
    }

    /** Synchronously mask and remove admission; physical close never blocks the main thread. */
    override fun close() {
        val owned = synchronized(ownership) {
            mutableValid.value = false
            val result = reader to admission
            reader = null; admission = null
            result
        }
        owned.second?.close()
        owned.first?.let { handle -> cleanupScope.launch { handle.close() } }
    }
}
