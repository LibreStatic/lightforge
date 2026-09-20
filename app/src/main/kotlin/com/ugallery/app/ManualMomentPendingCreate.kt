package com.ugallery.app

import com.ugallery.core.data.ManualMomentDraft
import com.ugallery.core.model.MediaKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

data class ManualMomentCreateRequest(
    val token: String,
    val draft: ManualMomentRestoreSnapshot,
    val title: String,
    val orderedKeys: List<MediaKey>,
    val includeSpecialMedia: Boolean,
) {
    companion object {
        fun capture(draft: ManualMomentDraft, title: String, orderedKeys: List<MediaKey>,
            includeSpecialMedia: Boolean): ManualMomentCreateRequest = validatedCopy(ManualMomentCreateRequest(
                UUID.randomUUID().toString(), ManualMomentRestoreSnapshot.capture(draft, null),
                title.trim(), orderedKeys, includeSpecialMedia,
            ))

        fun validatedCopy(value: ManualMomentCreateRequest): ManualMomentCreateRequest {
            require(UUID.fromString(value.token).toString() == value.token)
            require(value.title == value.title.trim() && value.title.length <= 120)
            require(value.draft.originSelection == null)
            val draft = ManualMomentRestoreSnapshot.validatedCopy(value.draft)
            require(value.orderedKeys.size in 1..120)
            val ordered = value.orderedKeys.map { MediaKey(it.volumeName, it.mediaStoreId) }
            require(ordered.distinct().size == ordered.size)
            require(ordered.all { key -> draft.sources.any { it.key == key } })
            return value.copy(draft = draft, orderedKeys = Collections.unmodifiableList(ordered))
        }
    }
}

/** One durable, unacknowledged user intent. Reading never creates a memory or adopts a temp file. */
class ManualMomentPendingCreateStore internal constructor(
    directory: File,
    private val beforePublish: (() -> Unit)?,
) {
    constructor(directory: File) : this(directory, null)
    private val directory = directory.toPath().toAbsolutePath().normalize()
    private val main = this.directory.resolve(FileName)
    internal var beforeDirectorySync: (() -> Unit)? = null

    fun read(): ManualMomentCreateRequest? = synchronized(lock) { readLocked() }

    fun put(request: ManualMomentCreateRequest): Unit = synchronized(lock) {
        val checked = ManualMomentCreateRequest.validatedCopy(request)
        val bytes = encode(checked) // Reject malformed/oversized input before any filesystem writes.
        ensureDirectory()
        val existing = readLocked()
        if (existing != null) {
            check(existing == checked) { "Another manual memory intent is awaiting acknowledgment" }
            syncFile(main)
            syncDirectory(directory)
            return@synchronized
        }
        val temp = Files.createTempFile(directory, "pending-", ".tmp")
        try {
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforePublish?.invoke()
            check(readLocked() == null) { "Manual memory intent appeared before publication" }
            // The process-global lock excludes other store instances; do not fall back to non-atomic writes.
            Files.move(temp, main, ATOMIC_MOVE)
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temp) // Only this call's uniquely created temporary sibling.
        }
    }

    fun clear(expected: ManualMomentCreateRequest): Boolean = synchronized(lock) {
        val checked = ManualMomentCreateRequest.validatedCopy(expected)
        val current = readLocked() ?: return@synchronized false
        if (current != checked) return@synchronized false
        Files.delete(main)
        syncDirectory(directory)
        true
    }

    private fun readLocked(): ManualMomentCreateRequest? {
        checkAncestors(directory)
        val directoryAttrs = attributesOrNull(directory) ?: return null
        check(directoryAttrs.isDirectory) { "Pending-intent directory is not a directory" }
        val attrs = attributesOrNull(main) ?: run {
            // A previous unlink may have succeeded while its directory fsync failed. Confirm
            // durable absence before allowing retry/acknowledgment to observe no pending intent.
            syncDirectory(directory)
            return null
        }
        check(attrs.isRegularFile && !attrs.isSymbolicLink) { "Pending intent is not a regular file" }
        require(attrs.size() in 1..MaximumBytes.toLong()) { "Pending intent exceeds its size bound" }
        val bytes = ByteArrayOutputStream()
        FileChannel.open(main, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(4_096)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(count <= MaximumBytes - bytes.size()) { "Pending intent grew beyond its bound" }
                bytes.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return decode(bytes.toByteArray())
    }

    private fun ensureDirectory() {
        checkAncestors(directory)
        val parent = requireNotNull(directory.parent)
        check(attributesOrNull(parent)?.isDirectory == true) { "Pending-intent parent must already exist" }
        if (attributesOrNull(directory) == null) Files.createDirectory(directory)
        check(attributesOrNull(directory)?.isDirectory == true) { "Pending-intent directory is not a directory" }
        checkAncestors(directory)
        // Also repair durability after an earlier createDirectory followed by a failed parent fsync.
        syncDirectory(parent)
    }

    private fun checkAncestors(path: Path) {
        var cursor: Path? = path
        while (cursor != null) {
            check(attributesOrNull(cursor)?.isSymbolicLink != true) { "Symbolic links are not accepted for pending intents" }
            cursor = cursor.parent
        }
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun syncFile(path: Path) = FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    private fun syncDirectory(path: Path) {
        beforeDirectorySync?.invoke()
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    companion object {
        private val lock = Any()
        internal const val FileName = "request.bin"
        internal const val MaximumBytes = 64 * 1024
        private const val Magic = 0x554d4d49 // UMMI
        private const val Version = 1
        private const val DigestBytes = 32

        private fun encode(value: ManualMomentCreateRequest): ByteArray {
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(object : java.io.OutputStream() {
                override fun write(value: Int) {
                    require(bytes.size() < MaximumBytes - DigestBytes) { "Pending intent exceeds its size bound" }
                    bytes.write(value)
                }
                override fun write(value: ByteArray, offset: Int, length: Int) {
                    require(length <= MaximumBytes - DigestBytes - bytes.size()) { "Pending intent exceeds its size bound" }
                    bytes.write(value, offset, length)
                }
            })
            fun text(value: String) {
                require(value.length <= MaximumBytes)
                val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val encoded = encoder.encode(java.nio.CharBuffer.wrap(value))
                output.writeInt(encoded.remaining())
                val data = ByteArray(encoded.remaining()); encoded.get(data); output.write(data)
            }
            output.writeInt(Magic); output.writeInt(Version)
            text(value.token); text(value.draft.id); text(value.title)
            output.writeByte(if (value.includeSpecialMedia) 1 else 0)
            output.writeInt(value.draft.sources.size)
            value.draft.sources.forEach { source ->
                text(source.key.volumeName); output.writeLong(source.key.mediaStoreId)
                output.writeLong(source.generationAdded); output.writeLong(source.generationModified)
                output.writeByte(if (source.requiresSpecialMedia) 1 else 0)
            }
            output.writeInt(value.orderedKeys.size)
            value.orderedKeys.forEach { key -> output.writeInt(value.draft.sources.indexOfFirst { it.key == key }) }
            output.flush()
            val payload = bytes.toByteArray()
            return payload + MessageDigest.getInstance("SHA-256").digest(payload)
        }

        private fun decode(bytes: ByteArray): ManualMomentCreateRequest {
            require(bytes.size in DigestBytes + 8..MaximumBytes)
            val payload = bytes.copyOfRange(0, bytes.size - DigestBytes)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload),
                bytes.copyOfRange(payload.size, bytes.size))) { "Pending-intent checksum mismatch" }
            val input = DataInputStream(ByteArrayInputStream(payload))
            require(input.readInt() == Magic && input.readInt() == Version)
            fun text(): String {
                val length = input.readInt()
                require(length >= 0 && length <= input.available())
                val data = ByteArray(length); input.readFully(data)
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            }
            fun flag(): Boolean = when (val flag = input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> throw IOException("Invalid boolean in pending intent: $flag")
            }
            val token = text(); val id = text(); val title = text(); val include = flag()
            val sourceCount = input.readInt(); require(sourceCount in 1..120)
            val sources = List(sourceCount) {
                ManualMomentSourceSnapshot(MediaKey(text(), input.readLong()), input.readLong(), input.readLong(), flag())
            }
            val orderCount = input.readInt(); require(orderCount in 1..120)
            val order = List(orderCount) {
                val index = input.readInt(); require(index in sources.indices); sources[index].key
            }
            require(input.available() == 0) { "Unexpected trailing pending-intent data" }
            return ManualMomentCreateRequest.validatedCopy(ManualMomentCreateRequest(
                token, ManualMomentRestoreSnapshot(id, sources), title, order, include,
            ))
        }
    }
}

/** Merely finding/closing a recovery notice never authorizes an acknowledgement. */
internal fun manualMomentMayAcknowledge(
    request: ManualMomentCreateRequest,
    armedToken: String?,
    displayedMomentId: String?,
    status: com.ugallery.core.data.ManualMomentCommitStatus,
): Boolean = armedToken == request.token && displayedMomentId == request.draft.id &&
    status == com.ugallery.core.data.ManualMomentCommitStatus.Committed
