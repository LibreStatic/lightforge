package com.ugallery.feature.localsharing

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

const val LOCAL_SHARING_MAX_FILES = 1000
const val LOCAL_SHARING_MAX_FILE_BYTES = 50L * 1024 * 1024 * 1024
const val LOCAL_SHARING_MAX_TOTAL_BYTES = 100L * 1024 * 1024 * 1024
internal const val PEER_FRAME_LIMIT = 1024 * 1024
internal const val PEER_CHUNK_LIMIT = 1024 * 1024

data class LocalSharingEntry(
    val sourceId: String,
    val revision: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
    val modifiedMillis: Long,
    val sanitized: Boolean,
) {
    fun validate() {
        require(sourceId.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
        require(revision.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
        require(
            peerName(name) &&
                mime.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) &&
                mime.length <= 128
        )
        require(bytes in 0..LOCAL_SHARING_MAX_FILE_BYTES && peerHash(sha256) && modifiedMillis >= 0)
    }
}

data class LocalSharingManifest(val entries: List<LocalSharingEntry>, val stripLocation: Boolean) {
    fun validate() {
        require(entries.size in 1..LOCAL_SHARING_MAX_FILES)
        entries.forEach {
            it.validate()
            require(!stripLocation || it.sanitized)
        }
        require(entries.map { it.sourceId }.distinct().size == entries.size)
        require(entries.sumOf { it.bytes } <= LOCAL_SHARING_MAX_TOTAL_BYTES)
    }

    val totalBytes
        get() = entries.sumOf { it.bytes }
}

data class LocalSharingPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val revoked: Boolean = false,
    val canSend: Boolean = true,
)

enum class LocalSharingDirection {
    Send,
    Receive,
}

enum class LocalSharingStatus {
    Preparing,
    AwaitingReview,
    Queued,
    Transferring,
    WaitingPeer,
    Paused,
    AwaitingReceiveConsent,
    ReadyToImport,
    Importing,
    LocalTaskCreated,
    Completed,
    Cancelled,
    NeedsReview,
}

data class LocalSharingTransfer(
    val id: String,
    val peerId: String,
    val direction: LocalSharingDirection,
    val createdAt: Long = System.currentTimeMillis(),
    val status: LocalSharingStatus = LocalSharingStatus.Preparing,
    val selection: List<String> = emptyList(),
    val stripLocation: Boolean = true,
    val manifest: LocalSharingManifest? = null,
    val preparedFiles: List<String> = emptyList(),
    val bytesDone: Long = 0,
    val pauseRequested: Boolean = false,
    val cancelRequested: Boolean = false,
    val localTaskId: String? = null,
    val failure: String? = null,
    val choices: Map<String, LocalSharingConflictChoice> = emptyMap(),
    val resumeStatus: LocalSharingStatus? = null,
) {
    val terminal
        get() =
            status in
                setOf(
                    LocalSharingStatus.Completed,
                    LocalSharingStatus.Cancelled,
                    LocalSharingStatus.LocalTaskCreated,
                )
}

data class LocalSharingInvitation(
    val host: String,
    val port: Int,
    val pin: String,
    val secret: String,
    val expiresAt: Long,
) {
    fun validate(now: Long = System.currentTimeMillis()) {
        require(peerHost(host) && port in 1024..65535 && peerHash(pin))
        require(
            secret.matches(Regex("[a-f0-9]{64}")) &&
                expiresAt > now &&
                expiresAt - now <= 10 * 60_000
        )
    }
}

data class LocalSharingPairRequest(
    val pin: String,
    val name: String,
    val host: String,
    val requestedAt: Long,
)

internal fun peerUuid(v: String) {
    require(runCatching { UUID.fromString(v).toString() == v }.getOrDefault(false))
}

internal fun peerHash(v: String) = v.matches(Regex("[a-f0-9]{64}"))

internal fun peerName(v: String) =
    v.length in 1..255 &&
        v !in setOf(".", "..") &&
        v.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

internal fun peerHost(v: String) =
    v.length in 1..64 &&
        (v.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) || v.matches(Regex("[a-fA-F0-9:]+")))

internal fun ByteArray.peerHex() = joinToString("") { "%02x".format(it) }

internal fun peerDigest(
    input: InputStream,
    limit: Long,
    check: () -> Unit = {},
): Pair<Long, String> {
    val md = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var n = 0L
    while (true) {
        check()
        val c = input.read(buffer)
        if (c < 0) break
        if (c == 0) continue
        n += c
        if (n > limit) throw IOException("size")
        md.update(buffer, 0, c)
    }
    return n to md.digest().peerHex()
}

internal fun peerDigest(file: File, check: () -> Unit = {}) =
    file.inputStream().use { peerDigest(it, LOCAL_SHARING_MAX_FILE_BYTES, check) }

internal class PeerStopped : IOException("stopped")

internal class PeerFailure(val reason: String) : IOException(reason)
