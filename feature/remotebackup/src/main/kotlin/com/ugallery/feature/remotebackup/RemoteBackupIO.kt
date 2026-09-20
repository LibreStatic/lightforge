package com.ugallery.feature.remotebackup

import com.ugallery.core.remotestorage.RemoteDigest
import com.ugallery.core.remotestorage.RemoteEntry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal object RemoteBackupIO {
    private const val BufferSize = 65536

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(minOf(BufferSize, limit + 1))
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            if (output.size() > limit) throw IOException("Bounded input exceeded")
        }
        return output.toByteArray()
    }

    fun requireUploadCapability(connection: com.ugallery.core.remotestorage.RemoteConnection) {
        val capabilities = connection.capabilities
        if (!capabilities.atomicPublish || !capabilities.encrypted || !capabilities.signed)
            throw com.ugallery.core.remotestorage.RemoteStorageException(
                com.ugallery.core.remotestorage.RemoteFailure.UNSUPPORTED
            )
    }

    fun digest(file: File, check: () -> Unit): RemoteDigest =
        file.inputStream().use { digest(it, check) }

    fun digest(input: InputStream, check: () -> Unit): RemoteDigest = copy(input, null, check)

    fun copy(
        input: InputStream,
        output: OutputStream?,
        check: () -> Unit,
        limit: Long = RemoteBackupController.MaxArchiveBytes,
        progress: (Long) -> Unit = {},
    ): RemoteDigest {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BufferSize)
        var total = 0L
        while (true) {
            check()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) throw IOException("Archive size exceeded")
            output?.write(buffer, 0, read)
            sha.update(buffer, 0, read)
            progress(total)
        }
        return RemoteDigest(total, sha.digest().joinToString("") { "%02x".format(it) })
    }

    /**
     * Leaves remote immediately after the verified local prefix. No prefix is trusted by size
     * alone.
     */
    fun comparePrefix(local: File, remote: InputStream, check: () -> Unit): Long {
        if (!local.exists()) return 0
        var total = 0L
        local.inputStream().use { input ->
            val expected = ByteArray(BufferSize)
            val actual = ByteArray(BufferSize)
            while (true) {
                check()
                val count = input.read(expected)
                if (count < 0) break
                var done = 0
                while (done < count) {
                    check()
                    val read = remote.read(actual, done, count - done)
                    if (read < 0) throw RemoteContentChanged()
                    done += read
                }
                repeat(count) { if (expected[it] != actual[it]) throw RemoteContentChanged() }
                total += count
                if (total > RemoteBackupController.MaxArchiveBytes) throw RemoteContentChanged()
            }
        }
        return total
    }

    fun requireSame(expected: RemoteEntry, actual: RemoteEntry?) {
        if (
            actual == null ||
                !actual.regularFile ||
                expected.name != actual.name ||
                expected.size != actual.size ||
                expected.modifiedMillis != actual.modifiedMillis
        )
            throw RemoteContentChanged()
    }
}

internal class RemoteContentChanged : IOException("Remote content requires review")
