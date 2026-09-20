package com.ugallery.app

import com.ugallery.core.mediastore.VerifiedMoveProof
import com.ugallery.core.mediastore.MoveCopyDraft
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
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

internal data class CorruptMoveReview internal constructor(
    val bytes: Long, val sha256: String, internal val fileKey: String, internal val modified: String,
)

internal enum class VerifiedMovePhase { Ready, AwaitingSystem, Cancelled, RequestFailed, Completed }

internal data class VerifiedMoveEntry(
    val proof: VerifiedMoveProof,
    val phase: VerifiedMovePhase = VerifiedMovePhase.Ready,
    val requestId: Long = 0L,
) {
    fun validated(): VerifiedMoveEntry {
        proof.validated()
        require(requestId >= 0L)
        require(if (phase == VerifiedMovePhase.Ready) requestId == 0L else requestId > 0L)
        return this
    }
}

/**
 * Single active Move, independent of SavedState. Call every operation on IO.
 * Atomic rename + file/directory fsync follow the creation publication journals. SHA detects
 * corruption, not a malicious writer in this app's private directory. No method opens media,
 * grants access, authenticates, issues Delete or removes public bytes.
 *
 * The parent must exist (use a dedicated child of noBackupFilesDir). All instances in this
 * application process share a lock. Cross-process writers are not supported; the app owns it.
 */
internal class VerifiedMoveJournal internal constructor(
    directory: File,
    private val beforePublish: (() -> Unit)?,
    private val directorySync: ((Path) -> Unit)?,
) {
    constructor(directory: File) : this(directory, null, null)
    private val directory = directory.toPath().toAbsolutePath().normalize()
    private val active = this.directory.resolve("active.bin")

    /** Corrupt, oversized, unsupported or symlinked state throws; it never becomes Missing. */
    fun readActive(): VerifiedMoveEntry? = synchronized(lock) { readLocked() }

    /** Valid copy drafts are distinct from absent state and from unreadable tracking. */
    fun readCopyDraft(): MoveCopyDraft? = synchronized(lock) {
        (readStoredLocked() as? StoredMove.Copy)?.draft
    }

    fun beginCopy(draft: MoveCopyDraft): MoveCopyDraft = synchronized(lock) {
        draft.validated()
        require(draft.destinationUri == null) { "Begin before creating the destination" }
        val existing = readStoredLocked()
        if (existing != null) {
            check(existing == StoredMove.Copy(draft)) { "Another Move is still tracked" }
            syncFile(active); syncDirectory(directory)
            return@synchronized draft
        }
        ensureDirectory()
        writeBytesLocked(encodeCopyDraft(draft), null)
        draft
    }

    /** A destination is attached exactly once. Never replace a reviewed URI on retry. */
    fun attachCopyDestination(expected: MoveCopyDraft, uri: String): MoveCopyDraft = synchronized(lock) {
        expected.validated()
        require(expected.destinationUri == null)
        check(readStoredLocked() == StoredMove.Copy(expected)) { "Copy draft changed" }
        val next = expected.copy(destinationUri = uri).validated()
        writeBytesLocked(encodeCopyDraft(next), StoredMove.Copy(expected))
        next
    }

    /** Atomic ownership transfer, after caller has verified both streams and source identity. */
    fun promoteCopy(expected: MoveCopyDraft): VerifiedMoveEntry = synchronized(lock) {
        val entry = VerifiedMoveEntry(expected.toProof()).validated()
        check(readStoredLocked() == StoredMove.Copy(expected)) { "Copy draft changed" }
        writeBytesLocked(encode(entry), StoredMove.Copy(expected))
        entry
    }

    /** Explicit tracking-only CAS; never removes the source, partial copy or tree grant. */
    fun forgetCopy(expected: MoveCopyDraft): Boolean = synchronized(lock) {
        expected.validated()
        if (readStoredLocked() != StoredMove.Copy(expected)) return@synchronized false
        Files.delete(active)
        syncDirectory(directory)
        true
    }

    /** A retry of the same begin is idempotent, including a prior ambiguous directory fsync. */
    fun begin(proof: VerifiedMoveProof): VerifiedMoveEntry = synchronized(lock) {
        proof.validated()
        val existing = readLocked()
        if (existing != null) {
            check(existing.proof == proof) { "Another Move is still tracked" }
            syncFile(active)
            syncDirectory(directory)
            return@synchronized existing
        }
        ensureDirectory()
        VerifiedMoveEntry(proof).also { writeLocked(it, null) }
    }

    /** Records lifecycle only. Callers separately authenticate/revalidate before emitting a launch. */
    fun advance(expected: VerifiedMoveEntry, phase: VerifiedMovePhase, requestId: Long): VerifiedMoveEntry = synchronized(lock) {
        expected.validated()
        check(readLocked() == expected) { "Move state changed" }
        val next = VerifiedMoveEntry(expected.proof, phase, requestId).validated()
        val legal = when (expected.phase) {
            VerifiedMovePhase.Ready -> phase == VerifiedMovePhase.AwaitingSystem && requestId > expected.requestId
            VerifiedMovePhase.AwaitingSystem -> phase in setOf(VerifiedMovePhase.Cancelled, VerifiedMovePhase.RequestFailed, VerifiedMovePhase.Completed) && requestId == expected.requestId
            VerifiedMovePhase.Cancelled, VerifiedMovePhase.RequestFailed ->
                (phase == VerifiedMovePhase.AwaitingSystem && requestId > expected.requestId) ||
                    (phase == VerifiedMovePhase.Completed && requestId == expected.requestId)
            VerifiedMovePhase.Completed -> false
        }
        require(legal) { "Invalid Move transition" }
        writeLocked(next, expected)
        next
    }

    /** Explicit tracking-only CAS. Callers separately release only a grant acquired by this proof. */
    fun forget(expected: VerifiedMoveEntry): Boolean = synchronized(lock) {
        expected.validated()
        if (readStoredLocked() != StoredMove.Entry(expected)) return@synchronized false
        Files.delete(active)
        syncDirectory(directory)
        true
    }

    /** Review is byte/inode bound and never treats a valid replacement as corrupt. */
    fun reviewCorrupt(checkActive: () -> Unit = {}): CorruptMoveReview = synchronized(lock) {
        check(runCatching { readStoredLocked() }.isFailure) { "Move record is readable" }
        corruptIdentity(checkActive)
    }

    /** Preserve unreadable tracking only. Never opens/deletes a public source or destination. */
    fun quarantineCorrupt(review: CorruptMoveReview, checkActive: () -> Unit = {}): File = synchronized(lock) {
        check(reviewCorrupt(checkActive) == review) { "Reviewed Move record changed" }
        FileChannel.open(active, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        check(reviewCorrupt(checkActive) == review) { "Reviewed Move record changed during sync" }
        val preserved = directory.resolve("unreadable-${java.util.UUID.randomUUID()}-${review.sha256.take(12)}.bin")
        check(!Files.exists(preserved, NOFOLLOW_LINKS))
        checkActive()
        Files.move(active, preserved, ATOMIC_MOVE)
        syncDirectory(directory)
        preserved.toFile()
    }

    private fun corruptIdentity(checkActive: () -> Unit): CorruptMoveReview {
        checkAncestors(directory)
        val before = requireNotNull(attributes(active))
        check(before.isRegularFile && !before.isSymbolicLink)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileChannel.open(active, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                checkActive()
                val read = channel.read(buffer)
                checkActive()
                if (read < 0) break
                if (read == 0) continue
                count = Math.addExact(count, read.toLong())
                check(count <= before.size()) { "Record grew during review" }
                digest.update(buffer.array(), 0, read)
                buffer.clear()
            }
        }
        val after = requireNotNull(attributes(active))
        check(before.fileKey() == after.fileKey() && before.size() == after.size() && count == before.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() && after.isRegularFile && !after.isSymbolicLink)
        return CorruptMoveReview(count, digest.digest().joinToString("") { "%02x".format(it) },
            requireNotNull(after.fileKey()).toString(), after.lastModifiedTime().toString())
    }

    private sealed interface StoredMove {
        data class Entry(val entry: VerifiedMoveEntry) : StoredMove
        data class Copy(val draft: MoveCopyDraft) : StoredMove
    }

    private fun readLocked(): VerifiedMoveEntry? = when (val stored = readStoredLocked()) {
        null -> null
        is StoredMove.Entry -> stored.entry
        is StoredMove.Copy -> error("A Move copy draft is pending")
    }

    private fun readStoredLocked(): StoredMove? {
        checkAncestors(directory)
        val dir = attributes(directory) ?: return null
        check(dir.isDirectory)
        val before = attributes(active) ?: run { syncDirectory(directory); return null }
        check(before.isRegularFile && !before.isSymbolicLink)
        require(before.size() in 1..MaximumBytes.toLong())
        val bytes = ByteArrayOutputStream()
        FileChannel.open(active, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(count <= MaximumBytes - bytes.size())
                bytes.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        val after = requireNotNull(attributes(active))
        check(after.isRegularFile && !after.isSymbolicLink && before.fileKey() == after.fileKey() &&
            before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
        return decodeStored(bytes.toByteArray())
    }

    private fun writeLocked(entry: VerifiedMoveEntry, expected: VerifiedMoveEntry?) =
        writeBytesLocked(encode(entry.validated()), expected?.let { StoredMove.Entry(it) })

    private fun writeBytesLocked(bytes: ByteArray, expected: StoredMove?) {
        checkAncestors(directory)
        val temp = Files.createTempFile(directory, "move-", ".tmp")
        try {
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforePublish?.invoke()
            check(readStoredLocked() == expected) { "Move changed before publication" }
            Files.move(temp, active, ATOMIC_MOVE)
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ensureDirectory() {
        checkAncestors(directory)
        val parent = requireNotNull(directory.parent)
        check(attributes(parent)?.isDirectory == true)
        if (attributes(directory) == null) Files.createDirectory(directory)
        check(attributes(directory)?.isDirectory == true)
        syncDirectory(parent)
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun checkAncestors(path: Path) {
        var current: Path? = path
        while (current != null) {
            check(attributes(current)?.isSymbolicLink != true)
            current = current.parent
        }
    }

    private fun syncFile(path: Path) = FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    private fun syncDirectory(path: Path) {
        directorySync?.let { it(path); return }
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    companion object {
        private val lock = Any()
        internal const val MaximumBytes = 64 * 1024
        private const val Magic = 0x55474d56

        private fun encode(entry: VerifiedMoveEntry): ByteArray {
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(bytes)
            fun text(value: String) {
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                require(encoded.remaining() <= MaximumBytes - 32 - bytes.size() - 4)
                output.writeInt(encoded.remaining())
                val data = ByteArray(encoded.remaining()); encoded.get(data); output.write(data)
            }
            output.writeInt(Magic); output.writeInt(1)
            entry.proof.let { proof ->
                text(proof.id); text(proof.targetVolume); output.writeLong(proof.targetId); text(proof.targetKind)
                text(proof.sourceUri); text(proof.destinationUri); text(proof.treeUri)
                output.writeLong(proof.bytes); text(proof.sha256)
                output.writeLong(proof.generationAdded); output.writeLong(proof.generationModified)
                output.writeByte(if (proof.grantReadAcquired) 1 else 0)
            }
            output.writeInt(entry.phase.ordinal); output.writeLong(entry.requestId); output.flush()
            require(bytes.size() <= MaximumBytes - 32)
            return bytes.toByteArray().let { it + MessageDigest.getInstance("SHA-256").digest(it) }
        }

        /** v1 proof bytes remain unchanged. v2 is reserved for pre-proof copy drafts. */
        private fun encodeCopyDraft(draft: MoveCopyDraft): ByteArray {
            draft.validated()
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(bytes)
            fun text(value: String) {
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
                require(encoded.remaining() <= MaximumBytes - 32 - bytes.size() - 4)
                output.writeInt(encoded.remaining())
                val data = ByteArray(encoded.remaining()); encoded.get(data); output.write(data)
            }
            output.writeInt(Magic); output.writeInt(2)
            text(draft.id); text(draft.targetVolume); output.writeLong(draft.targetId); text(draft.targetKind)
            text(draft.sourceUri); text(draft.treeUri); text(draft.name); text(draft.mime)
            output.writeByte(if (draft.lastModifiedMillis == null) 0 else 1)
            draft.lastModifiedMillis?.let { output.writeLong(it) }
            output.writeLong(draft.bytes); text(draft.sha256)
            output.writeLong(draft.generationAdded); output.writeLong(draft.generationModified)
            output.writeByte(if (draft.destinationUri == null) 0 else 1)
            draft.destinationUri?.let(::text)
            output.writeByte(if (draft.grantReadAcquired) 1 else 0)
            output.flush(); require(bytes.size() <= MaximumBytes - 32)
            return bytes.toByteArray().let { it + MessageDigest.getInstance("SHA-256").digest(it) }
        }

        private fun decodeStored(bytes: ByteArray): StoredMove {
            require(bytes.size in 40..MaximumBytes)
            val payload = bytes.copyOf(bytes.size - 32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
            val input = DataInputStream(ByteArrayInputStream(payload))
            require(input.readInt() == Magic)
            val version = input.readInt(); require(version in 1..2)
            fun text(): String {
                val length = input.readInt()
                require(length in 0..input.available() && length <= 4 * 8192)
                val data = ByteArray(length); input.readFully(data)
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            }
            fun flag(): Boolean = when (input.readUnsignedByte()) {
                0 -> false; 1 -> true; else -> error("Invalid Move flag")
            }
            val id = text(); val volume = text(); val targetId = input.readLong(); val kind = text()
            if (version == 2) {
                val source = text(); val tree = text(); val name = text(); val mime = text()
                val modifiedMillis = if (flag()) input.readLong() else null
                val size = input.readLong(); val hash = text(); val added = input.readLong(); val modified = input.readLong()
                val destination = if (flag()) text() else null
                val acquired = flag()
                require(input.available() == 0)
                return StoredMove.Copy(MoveCopyDraft(id, volume, targetId, kind, source, tree, name, mime,
                    modifiedMillis, size, hash, added, modified, destination, acquired).validated())
            }
            val source = text(); val destination = text(); val tree = text(); val size = input.readLong(); val hash = text()
            val added = input.readLong(); val modified = input.readLong()
            val acquired = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Invalid grant ownership flag") }
            val phase = VerifiedMovePhase.entries.getOrNull(input.readInt()) ?: error("Invalid Move phase")
            val requestId = input.readLong()
            require(input.available() == 0)
            return StoredMove.Entry(VerifiedMoveEntry(VerifiedMoveProof(id, volume, targetId, kind, source, destination, tree, size, hash,
                added, modified, acquired), phase, requestId).validated())
        }
    }
}
