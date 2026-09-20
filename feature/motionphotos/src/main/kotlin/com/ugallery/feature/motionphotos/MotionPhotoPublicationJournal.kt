package com.ugallery.feature.motionphotos

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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException

enum class MotionPhotoPublicationKind { Frame, Clip }

enum class MotionPhotoPublicationPhase { Intent, Inserted, Ready, Published }
enum class MotionPhotoPublicationStatus { Missing, Incomplete, Conflict, Published }

data class MotionPhotoPublicationDestination(
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

data class MotionPhotoPublicationReceipt(
    val publicationId: String,
    val token: String,
    val sourceUri: String,
    val generationModified: Long?,
    val generationAdded: Long?,
    val sourceIdentity: String,
    val sourceSha256: String,
    val kind: MotionPhotoPublicationKind,
    val selectedTimeUs: Long?,
    val durationUs: Long,
    val renderSha256: String,
    val renderSizeBytes: Long,
    val destination: MotionPhotoPublicationDestination? = null,
    val phase: MotionPhotoPublicationPhase = MotionPhotoPublicationPhase.Intent,
) {
    fun sameRequest(other: MotionPhotoPublicationReceipt): Boolean =
        publicationId == other.publicationId && token == other.token && sourceUri == other.sourceUri &&
            generationModified == other.generationModified && generationAdded == other.generationAdded &&
            sourceIdentity == other.sourceIdentity && sourceSha256 == other.sourceSha256 && kind == other.kind &&
            selectedTimeUs == other.selectedTimeUs && durationUs == other.durationUs &&
            renderSha256 == other.renderSha256 && renderSizeBytes == other.renderSizeBytes

    companion object {
        private val digest = Regex("[0-9a-f]{64}")
        fun validatedCopy(value: MotionPhotoPublicationReceipt): MotionPhotoPublicationReceipt {
            require(UUID.fromString(value.publicationId).toString() == value.publicationId)
            require(UUID.fromString(value.token).toString() == value.token)
            require(value.sourceUri.isNotBlank() && value.sourceUri.length <= 2048)
            val source = java.net.URI(value.sourceUri)
            require(source.scheme in setOf("file", "content"))
            require(value.generationModified == null || value.generationModified >= 0)
            require(value.generationAdded == null || (value.generationModified != null && value.generationAdded >= 0))
            require(value.generationModified == null || (source.scheme == "content" && source.authority == "media"))
            require(value.sourceIdentity == "${value.sourceUri}@${value.generationModified}/${value.generationAdded}")
            require(digest.matches(value.sourceSha256) && digest.matches(value.renderSha256))
            require(value.durationUs in 1..60_000_000L)
            if (value.kind == MotionPhotoPublicationKind.Frame)
                require(value.selectedTimeUs != null && value.selectedTimeUs in 0 until value.durationUs)
            else require(value.selectedTimeUs == null)
            require(value.renderSizeBytes > 0)
            // Clips are slices of the existing <=512 MiB source limit. JPEG has no new arbitrary byte cap.
            if (value.kind == MotionPhotoPublicationKind.Clip) require(value.renderSizeBytes <= 512L * 1024 * 1024)
            require((value.phase == MotionPhotoPublicationPhase.Intent) == (value.destination == null))
            value.destination?.let {
                val video = value.kind == MotionPhotoPublicationKind.Clip
                val collection = if (video) "video" else "images"
                require(Regex("content://media/(external|external_primary)/$collection/media/[1-9][0-9]*").matches(it.uri))
                require(it.ownerPackage.isNotBlank() && it.ownerPackage.length <= 256)
                require(it.displayName == "UGallery-Motion-${value.token}.${if (video) "mp4" else "jpg"}")
                require(it.relativePath == if (video) "Movies/UGallery/Motion/" else "Pictures/UGallery/Motion/")
                require(it.mimeType == if (video) "video/mp4" else "image/jpeg")
                require(it.generationAdded >= 0 && it.generationModified >= it.generationAdded && it.sizeBytes >= 0)
                require(!it.trashed && it.pending == (value.phase != MotionPhotoPublicationPhase.Published))
                if (value.phase == MotionPhotoPublicationPhase.Ready) require(it.sizeBytes == 0L || it.sizeBytes == value.renderSizeBytes)
                if (value.phase == MotionPhotoPublicationPhase.Published) require(it.sizeBytes == value.renderSizeBytes)
            }
            return value.copy()
        }
    }
}

data class MotionPhotoPublicationRecovery(
    val status: MotionPhotoPublicationStatus,
    val receipt: MotionPhotoPublicationReceipt? = null,
    val resultUri: String? = null,
)

data class MotionPhotoPublicationEntry(
    val id: String,
    val journalSha256: String? = null,
    val receipt: MotionPhotoPublicationReceipt? = null,
    val unreadable: Boolean = false,
)

data class MotionPhotoPublicationResolution(
    val entry: MotionPhotoPublicationEntry,
    val recovery: MotionPhotoPublicationRecovery? = null,
    val busy: Boolean = false,
    val pendingDestination: MotionPhotoPublicationDestination? = null,
    val pendingBytesSha256: String? = null,
    val pendingBytesSize: Long? = null,
    val backingFileMissing: Boolean = false,
    val canComplete: Boolean = false,
    val canRemovePending: Boolean = false,
    val canForget: Boolean = false,
)

/** Per-session write-ahead receipt. An unknown inserted URI is retained as Intent, never guessed. */
class MotionPhotoPublicationJournal internal constructor(
    directory: File,
    private val beforePublish: (() -> Unit)?,
    private val directorySync: ((Path) -> Unit)?,
) {
    constructor(directory: File) : this(directory, null, null)
    private val directory = directory.toPath().toAbsolutePath().normalize()

    internal suspend fun <T> withPublicationLock(id: String, block: suspend () -> T): T {
        val mutex = publicationMutex(id); val owner = Any()
        mutex.lock(owner)
        try { return block() } finally { mutex.unlock(owner) }
    }

    internal suspend fun <T> tryWithPublicationLock(id: String, onBusy: () -> T, block: suspend () -> T): T {
        val mutex = publicationMutex(id); val owner = Any()
        if (!mutex.tryLock(owner)) return onBusy()
        try { return block() } finally { mutex.unlock(owner) }
    }

    private fun publicationMutex(id: String): Mutex {
        path(id) // Validate before using an identifier as a shared operation key.
        return publicationLocks.computeIfAbsent(directory.toFile().canonicalPath + "/" + id) { Mutex() }
    }

    /** Enumerate exact UUID markers only; temp names and unknown destinations are never adopted. */
    fun listEntries(): List<MotionPhotoPublicationEntry> = synchronized(lock) {
        checkAncestors(directory)
        val attrs = attributes(directory) ?: return@synchronized emptyList()
        check(attrs.isDirectory)
        Files.newDirectoryStream(directory).use { entries ->
            entries.mapNotNull { file ->
                val name = file.fileName.toString()
                if (!name.endsWith(".bin")) null else name.removeSuffix(".bin").takeIf { id ->
                    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                }
            }.sorted().map(::readEntryLocked)
        }
    }

    fun readEntry(id: String): MotionPhotoPublicationEntry = synchronized(lock) { readEntryLocked(id) }

    /** Forget is marker-only: no provider query, source read, output deletion or name-based adoption. */
    internal fun forget(expected: MotionPhotoPublicationEntry): Boolean = synchronized(lock) {
        if (expected.unreadable || expected.receipt == null || expected.journalSha256 == null ||
            expected.receipt.publicationId != expected.id || readEntryLocked(expected.id) != expected) return@synchronized false
        Files.delete(path(expected.id)); syncDirectory(directory); true
    }

    private fun readEntryLocked(id: String): MotionPhotoPublicationEntry {
        path(id)
        var bytes: ByteArray? = null
        return try {
            val content = readBytesLocked(id) ?: return MotionPhotoPublicationEntry(id)
            bytes = content
            val receipt = decode(content).also { require(it.publicationId == id) }
            MotionPhotoPublicationEntry(id, checksum(content), receipt)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: IOException) { MotionPhotoPublicationEntry(id, bytes?.let(::checksum), unreadable = true)
        } catch (_: IllegalArgumentException) { MotionPhotoPublicationEntry(id, bytes?.let(::checksum), unreadable = true)
        } catch (_: IllegalStateException) { MotionPhotoPublicationEntry(id, bytes?.let(::checksum), unreadable = true)
        } catch (_: SecurityException) { MotionPhotoPublicationEntry(id, bytes?.let(::checksum), unreadable = true) }
    }

    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hasEntry(publicationId: String): Boolean = synchronized(lock) {
        val path = path(publicationId)
        checkAncestors(directory)
        attributes(directory)?.let { check(it.isDirectory) }
        if (attributes(path) != null) true else {
            if (attributes(directory) != null) syncDirectory(directory)
            false
        }
    }

    /** No source reads, mkdir, temp adoption, acknowledgement or provider writes. Absent entries fsync the existing directory before reporting Missing. */
    fun read(publicationId: String): MotionPhotoPublicationReceipt? = synchronized(lock) { readLocked(publicationId) }

    fun begin(receipt: MotionPhotoPublicationReceipt): Unit = synchronized(lock) {
        val checked = MotionPhotoPublicationReceipt.validatedCopy(receipt)
        require(checked.phase == MotionPhotoPublicationPhase.Intent)
        val bytes = encode(checked)
        ensureDirectory()
        val current = readLocked(checked.publicationId)
        check(current == null || current == checked) { "Publication session already has a different receipt" }
        if (current == checked) {
            syncFile(path(checked.publicationId)); syncDirectory(directory)
        } else atomicWrite(checked.publicationId, bytes, null)
    }

    fun advance(expected: MotionPhotoPublicationReceipt, next: MotionPhotoPublicationReceipt): Unit = synchronized(lock) {
        val old = MotionPhotoPublicationReceipt.validatedCopy(expected)
        val checked = MotionPhotoPublicationReceipt.validatedCopy(next)
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
        check(readLocked(old.publicationId) == old) { "Publication receipt changed" }
        atomicWrite(old.publicationId, bytes, old)
    }

    /** Exporter must first verify the published destination; no caller here deletes public bytes. */
    internal fun retire(expected: MotionPhotoPublicationReceipt): Boolean = synchronized(lock) {
        require(expected.phase in listOf(MotionPhotoPublicationPhase.Ready, MotionPhotoPublicationPhase.Published))
        if (readLocked(expected.publicationId) != expected) return@synchronized false
        Files.delete(path(expected.publicationId)); syncDirectory(directory); true
    }

    private fun readLocked(publicationId: String): MotionPhotoPublicationReceipt? = readBytesLocked(publicationId)?.let { bytes ->
        decode(bytes).also { require(it.publicationId == publicationId) }
    }

    private fun readBytesLocked(publicationId: String): ByteArray? {
        val file = path(publicationId)
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
        val after = checkNotNull(attributes(file))
        check(after.isRegularFile && !after.isSymbolicLink && attrs.fileKey() == after.fileKey() &&
            attrs.size() == after.size() && attrs.lastModifiedTime() == after.lastModifiedTime()) { "Marker changed during read" }
        return output.toByteArray()
    }

    private fun atomicWrite(publicationId: String, bytes: ByteArray, expected: MotionPhotoPublicationReceipt?) {
        checkAncestors(directory)
        val temp = Files.createTempFile(directory, "publication-", ".tmp")
        try {
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforePublish?.invoke()
            check(readLocked(publicationId) == expected) { "Publication receipt changed before atomic publication" }
            Files.move(temp, path(publicationId), ATOMIC_MOVE)
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
    private fun path(publicationId: String): Path {
        require(UUID.fromString(publicationId).toString() == publicationId)
        return directory.resolve("$publicationId.bin")
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
        private val publicationLocks = ConcurrentHashMap<String, Mutex>()
        internal const val MaximumBytes = 64 * 1024
        private const val Magic = 0x55474d50
        private fun encode(value: MotionPhotoPublicationReceipt): ByteArray {
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
            text(value.publicationId); text(value.token); output.writeInt(value.phase.ordinal)
            text(value.sourceUri)
            fun optional(value: Long?) { output.writeByte(if (value == null) 0 else 1); value?.let(output::writeLong) }
            optional(value.generationModified); optional(value.generationAdded)
            text(value.sourceIdentity); text(value.sourceSha256); output.writeInt(value.kind.ordinal)
            optional(value.selectedTimeUs); output.writeLong(value.durationUs)
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
        private fun decode(bytes: ByteArray): MotionPhotoPublicationReceipt {
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
            val phase = MotionPhotoPublicationPhase.entries.getOrNull(input.readInt()) ?: error("Invalid publication phase")
            val sourceUri = text()
            fun optional(): Long? = if (flag()) input.readLong() else null
            val modified = optional(); val added = optional(); val identity = text(); val sourceHash = text()
            val kind = MotionPhotoPublicationKind.entries.getOrNull(input.readInt()) ?: error("Invalid publication kind")
            val time = optional(); val duration = input.readLong()
            val hash = text(); val size = input.readLong()
            val destination = if (flag()) MotionPhotoPublicationDestination(text(), text(), text(), text(), text(),
                input.readLong(), input.readLong(), input.readLong(), flag(), flag()) else null
            require(input.available() == 0)
            return MotionPhotoPublicationReceipt.validatedCopy(MotionPhotoPublicationReceipt(session, token,
                sourceUri, modified, added, identity, sourceHash, kind, time, duration, hash, size, destination, phase))
        }
    }
}
