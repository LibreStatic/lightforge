package com.librestatic.lightforge.feature.collage

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

enum class CreationGifPublicationPhase { Intent, Inserted, Ready, Published }
enum class CreationGifPublicationStatus { Missing, Incomplete, Conflict, Published }

data class CreationGifPublicationDestination(
    val uri: String,
    val ownerPackage: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val generationAdded: Long,
    val generationModified: Long,
    val sizeBytes: Long,
    val pending: Boolean,
    val trashed: Boolean,
)

data class CreationGifPublicationReceipt(
    val sessionId: String,
    val token: String,
    val sourceIdentities: List<String>,
    /** Hash i belongs to sourceIdentities[order[i]]; removed draft frames are not read. */
    val sourceSha256: List<String>,
    val order: List<Int>,
    val secondsPerFrame: Int,
    val renderSha256: String,
    val renderSizeBytes: Long,
    val destination: CreationGifPublicationDestination? = null,
    val phase: CreationGifPublicationPhase = CreationGifPublicationPhase.Intent,
) {
    fun sameRequest(other: CreationGifPublicationReceipt): Boolean =
        sessionId == other.sessionId && token == other.token && sourceIdentities == other.sourceIdentities &&
            sourceSha256 == other.sourceSha256 && order == other.order && secondsPerFrame == other.secondsPerFrame &&
            renderSha256 == other.renderSha256 && renderSizeBytes == other.renderSizeBytes

    companion object {
        private val digest = Regex("[0-9a-f]{64}")
        private val destinationUri = Regex("content://media/(external|external_primary)/images/media/[1-9][0-9]*")
        fun validatedCopy(value: CreationGifPublicationReceipt): CreationGifPublicationReceipt {
            require(UUID.fromString(value.sessionId).toString() == value.sessionId)
            require(UUID.fromString(value.token).toString() == value.token)
            require(value.sourceIdentities.size in 2..60 && value.sourceIdentities.distinct().size == value.sourceIdentities.size)
            require(value.sourceIdentities.all { it.isNotBlank() && it.length <= 2_048 })
            require(value.order.size in 2..value.sourceIdentities.size && value.order.distinct().size == value.order.size)
            require(value.order.all { it in value.sourceIdentities.indices } && value.secondsPerFrame in 1..5)
            require(value.sourceSha256.size == value.order.size && value.sourceSha256.all(digest::matches))
            require(digest.matches(value.renderSha256) && value.renderSizeBytes in 1..64L * 1024 * 1024)
            require((value.phase == CreationGifPublicationPhase.Intent) == (value.destination == null))
            value.destination?.let {
                require(destinationUri.matches(it.uri))
                require(it.ownerPackage.isNotBlank() && it.ownerPackage.length <= 256)
                require(it.displayName == "Lightforge-GIF-${value.token}.gif")
                require(it.relativePath == "Pictures/Lightforge/GIF/" && it.mimeType == "image/gif")
                require(it.generationAdded >= 0 && it.generationModified >= it.generationAdded && it.sizeBytes >= 0)
                require(!it.trashed)
                require(it.pending == (value.phase != CreationGifPublicationPhase.Published))
                // MediaStore may leave _size NULL/0 while pending, even after a verified fsynced stream.
                if (value.phase == CreationGifPublicationPhase.Ready)
                    require(it.sizeBytes == 0L || it.sizeBytes == value.renderSizeBytes)
                if (value.phase == CreationGifPublicationPhase.Published)
                    require(it.sizeBytes == value.renderSizeBytes)
            }
            return value.copy(sourceIdentities = Collections.unmodifiableList(value.sourceIdentities.toList()),
                sourceSha256 = Collections.unmodifiableList(value.sourceSha256.toList()), order = Collections.unmodifiableList(value.order.toList()))
        }
    }
}

data class CreationGifPublicationRecovery(
    val status: CreationGifPublicationStatus,
    val receipt: CreationGifPublicationReceipt? = null,
    val resultUri: String? = null,
)

data class CreationGifPublicationEntry(
    val id: String,
    val journalSha256: String? = null,
    val receipt: CreationGifPublicationReceipt? = null,
    val unreadable: Boolean = false,
)

data class CreationGifPublicationResolution(
    val entry: CreationGifPublicationEntry,
    val recovery: CreationGifPublicationRecovery? = null,
    val busy: Boolean = false,
    val pendingDestination: CreationGifPublicationDestination? = null,
    val pendingBytesSha256: String? = null,
    val pendingBytesSize: Long? = null,
    val backingFileMissing: Boolean = false,
    val canComplete: Boolean = false,
    val canRemovePending: Boolean = false,
    val canForget: Boolean = false,
)

/** Per-session write-ahead receipt. An unknown inserted URI is retained as Intent, never guessed. */
class CreationGifPublicationJournal internal constructor(
    directory: File,
    private val beforePublish: (() -> Unit)?,
    private val directorySync: ((Path) -> Unit)?,
) {
    constructor(directory: File) : this(directory, null, null)
    private val directory = directory.toPath().toAbsolutePath().normalize()

    internal suspend fun <T> withPublicationLock(id: String, action: suspend () -> T): T {
        val mutex = operationMutex(id)
        check(mutex.tryLock()) { "Another publication operation is active" }
        try { return action() } finally { mutex.unlock() }
    }

    internal suspend fun <T> tryWithPublicationLock(id: String, onBusy: () -> T, action: suspend () -> T): T {
        val mutex = operationMutex(id)
        if (!mutex.tryLock()) return onBusy()
        try { return action() } finally { mutex.unlock() }
    }

    private fun operationMutex(id: String): Mutex = operations.getOrPut(path(id).toString()) { Mutex() }

    fun listEntries(): List<CreationGifPublicationEntry> = synchronized(lock) {
        checkAncestors(directory)
        val attrs = attributes(directory) ?: return@synchronized emptyList()
        check(attrs.isDirectory)
        Files.newDirectoryStream(directory).use { children ->
            children.filter { it.fileName.toString().endsWith(".bin") }.map {
                readEntryLocked(it.fileName.toString().removeSuffix(".bin"))
            }.sortedBy { it.id }.toList()
        }
    }

    fun readEntry(id: String): CreationGifPublicationEntry = synchronized(lock) { readEntryLocked(id) }

    private fun readEntryLocked(id: String): CreationGifPublicationEntry {
        var hash: String? = null
        return try {
            val file = path(id); checkAncestors(directory)
            val dir = attributes(directory) ?: return CreationGifPublicationEntry(id)
            check(dir.isDirectory)
            val attrs = attributes(file) ?: run { syncDirectory(directory); return CreationGifPublicationEntry(id) }
            check(attrs.isRegularFile && !attrs.isSymbolicLink)
            require(attrs.size() in 1..MaximumBytes.toLong())
            val bytes = ByteArrayOutputStream()
            FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(4096)
                while (true) {
                    val count = channel.read(buffer); if (count < 0) break
                    require(count <= MaximumBytes - bytes.size())
                    bytes.write(buffer.array(), 0, count); buffer.clear()
                }
            }
            val after = checkNotNull(attributes(file))
            check(attrs.fileKey() == after.fileKey() && attrs.size() == after.size() && attrs.lastModifiedTime() == after.lastModifiedTime())
            val raw = bytes.toByteArray()
            hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            val receipt = decode(raw).also { require(it.sessionId == id) }
            CreationGifPublicationEntry(id, hash, receipt)
        } catch (_: Exception) { CreationGifPublicationEntry(id, hash, unreadable = true) }
    }

    /** Exact tracking deletion only; it does not inspect, adopt, publish or delete a MediaStore row. */
    internal fun forgetEntry(expected: CreationGifPublicationEntry): Boolean = synchronized(lock) {
        if (expected.unreadable || expected.receipt == null || expected.journalSha256 == null || readEntryLocked(expected.id) != expected)
            return@synchronized false
        Files.delete(path(expected.id)); syncDirectory(directory)
        check(attributes(path(expected.id)) == null)
        true
    }

    fun hasEntry(sessionId: String): Boolean = synchronized(lock) {
        val path = path(sessionId)
        checkAncestors(directory)
        attributes(directory)?.let { check(it.isDirectory) }
        if (attributes(path) != null) true else {
            if (attributes(directory) != null) syncDirectory(directory)
            false
        }
    }

    /** No source reads, mkdir, temp adoption, acknowledgement or provider writes. Absent entries fsync the existing directory before reporting Missing. */
    fun read(sessionId: String): CreationGifPublicationReceipt? = synchronized(lock) { readLocked(sessionId) }

    fun begin(receipt: CreationGifPublicationReceipt): Unit = synchronized(lock) {
        val checked = CreationGifPublicationReceipt.validatedCopy(receipt)
        require(checked.phase == CreationGifPublicationPhase.Intent)
        val bytes = encode(checked)
        ensureDirectory()
        val current = readLocked(checked.sessionId)
        check(current == null || current == checked) { "Publication session already has a different receipt" }
        if (current == checked) {
            syncFile(path(checked.sessionId)); syncDirectory(directory)
        } else atomicWrite(checked.sessionId, bytes, null)
    }

    fun advance(expected: CreationGifPublicationReceipt, next: CreationGifPublicationReceipt): Unit = synchronized(lock) {
        val old = CreationGifPublicationReceipt.validatedCopy(expected)
        val checked = CreationGifPublicationReceipt.validatedCopy(next)
        require(old.sameRequest(checked))
        require(checked.phase.ordinal == old.phase.ordinal + 1)
        old.destination?.let { prior ->
            val destination = requireNotNull(checked.destination)
            require(prior.uri == destination.uri && prior.ownerPackage == destination.ownerPackage &&
                prior.displayName == destination.displayName && prior.relativePath == destination.relativePath &&
                prior.mimeType == destination.mimeType && prior.generationAdded == destination.generationAdded &&
                destination.generationModified >= prior.generationModified)
        }
        val bytes = encode(checked)
        check(readLocked(old.sessionId) == old) { "Publication receipt changed" }
        atomicWrite(old.sessionId, bytes, old)
    }

    /** Exporter must first verify the published destination; no caller here deletes public bytes. */
    internal fun retire(expected: CreationGifPublicationReceipt): Boolean = synchronized(lock) {
        require(expected.phase in listOf(CreationGifPublicationPhase.Ready, CreationGifPublicationPhase.Published))
        if (readLocked(expected.sessionId) != expected) return@synchronized false
        Files.delete(path(expected.sessionId)); syncDirectory(directory); true
    }

    private fun readLocked(sessionId: String): CreationGifPublicationReceipt? {
        val file = path(sessionId)
        checkAncestors(directory)
        val dir = attributes(directory) ?: return null
        check(dir.isDirectory)
        val attrs = attributes(file) ?: run { syncDirectory(directory); return null }
        check(attrs.isRegularFile && !attrs.isSymbolicLink)
        require(attrs.size() in 1..MaximumBytes.toLong())
        val output = ByteArrayOutputStream()
        FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(count <= MaximumBytes - output.size())
                output.write(buffer.array(), 0, count); buffer.clear()
            }
        }
        return decode(output.toByteArray()).also { require(it.sessionId == sessionId) }
    }

    private fun atomicWrite(sessionId: String, bytes: ByteArray, expected: CreationGifPublicationReceipt?) {
        checkAncestors(directory)
        val temp = Files.createTempFile(directory, "publication-", ".tmp")
        try {
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforePublish?.invoke()
            check(readLocked(sessionId) == expected) { "Publication receipt changed before atomic publication" }
            Files.move(temp, path(sessionId), ATOMIC_MOVE)
            syncDirectory(directory)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun ensureDirectory() {
        checkAncestors(directory)
        val parent = requireNotNull(directory.parent)
        check(attributes(parent)?.isDirectory == true)
        if (attributes(directory) == null) Files.createDirectory(directory)
        check(attributes(directory)?.isDirectory == true)
        syncDirectory(parent)
    }
    private fun path(sessionId: String): Path {
        require(UUID.fromString(sessionId).toString() == sessionId)
        return directory.resolve("$sessionId.bin")
    }
    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }
    private fun checkAncestors(path: Path) {
        var at: Path? = path
        while (at != null) { check(attributes(at)?.isSymbolicLink != true); at = at.parent }
    }
    private fun syncFile(path: Path) = FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    private fun syncDirectory(path: Path) {
        directorySync?.let { it(path); return }
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    companion object {
        private val lock = Any()
        private val operations = ConcurrentHashMap<String, Mutex>()
        internal const val MaximumBytes = 64 * 1024
        private const val Magic = 0x55474750
        private fun encode(value: CreationGifPublicationReceipt): ByteArray {
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(object : OutputStream() {
                override fun write(value: Int) { require(bytes.size() < MaximumBytes - 32); bytes.write(value) }
                override fun write(value: ByteArray, offset: Int, length: Int) {
                    require(length <= MaximumBytes - 32 - bytes.size()); bytes.write(value, offset, length)
                }
            })
            fun text(value: String) {
                require(value.length <= 4096)
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                output.writeInt(encoded.remaining()); val data = ByteArray(encoded.remaining()); encoded.get(data); output.write(data)
            }
            output.writeInt(Magic); output.writeInt(1)
            text(value.sessionId); text(value.token); output.writeInt(value.phase.ordinal)
            output.writeInt(value.sourceIdentities.size)
            value.sourceIdentities.forEach(::text)
            output.writeInt(value.order.size); value.order.forEach(output::writeInt)
            value.sourceSha256.forEach(::text); output.writeInt(value.secondsPerFrame)
            text(value.renderSha256); output.writeLong(value.renderSizeBytes)
            output.writeByte(if (value.destination == null) 0 else 1)
            value.destination?.let {
                text(it.uri); text(it.ownerPackage); text(it.displayName); text(it.relativePath); text(it.mimeType)
                output.writeLong(it.generationAdded); output.writeLong(it.generationModified); output.writeLong(it.sizeBytes)
                output.writeByte(if (it.pending) 1 else 0); output.writeByte(if (it.trashed) 1 else 0)
            }
            output.flush()
            return bytes.toByteArray().let { it + MessageDigest.getInstance("SHA-256").digest(it) }
        }
        private fun decode(bytes: ByteArray): CreationGifPublicationReceipt {
            require(bytes.size in 40..MaximumBytes)
            val payload = bytes.copyOf(bytes.size - 32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
            val input = DataInputStream(ByteArrayInputStream(payload))
            require(input.readInt() == Magic && input.readInt() == 1)
            fun text(): String {
                val size = input.readInt(); require(size in 0..input.available())
                val data = ByteArray(size); input.readFully(data)
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            }
            fun flag(): Boolean = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Invalid publication boolean") }
            val session = text(); val token = text()
            val phase = CreationGifPublicationPhase.entries.getOrNull(input.readInt()) ?: error("Invalid publication phase")
            val count = input.readInt(); require(count in 2..60)
            val identities = List(count) { text() }
            val orderedCount = input.readInt(); require(orderedCount in 2..count)
            val order = List(orderedCount) { input.readInt() }
            val hashes = List(orderedCount) { text() }; val seconds = input.readInt()
            val hash = text(); val size = input.readLong()
            val destination = if (flag()) CreationGifPublicationDestination(text(), text(), text(), text(), text(),
                input.readLong(), input.readLong(), input.readLong(), flag(), flag()) else null
            require(input.available() == 0)
            return CreationGifPublicationReceipt.validatedCopy(CreationGifPublicationReceipt(session, token,
                identities, hashes, order, seconds, hash, size, destination, phase))
        }
    }
}
