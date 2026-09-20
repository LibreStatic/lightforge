package com.ugallery.feature.privatealbum

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException

/** In-process only: no fallback resolver, network source, disk cache or transferable plaintext URI. */
@UnstableApi
internal class PrivateMediaDataSource(private val source: PrivateViewerSource) : BaseDataSource(false) {
    class Factory(private val source: PrivateViewerSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = PrivateMediaDataSource(source)
    }

    private val expectedUri = Uri.parse("ugallery-private://viewer/${source.metadata.mediaId}")
    private var opened = false
    private var readPosition = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("Private source is already open")
        ensureValid()
        if (dataSpec.uri != expectedUri || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null) {
            throw IOException("Unsupported private media request")
        }
        val size = source.metadata.plaintextBytes
        if (size < 0 || dataSpec.position < 0 || dataSpec.position > size) throw EOFException("Private position is out of range")
        if (dataSpec.length < 0 && dataSpec.length != C.LENGTH_UNSET.toLong()) throw IOException("Invalid private read length")
        transferInitializing(dataSpec)
        ensureValid()
        readPosition = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - readPosition
            else minOf(dataSpec.length, size - readPosition)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (!opened) throw IOException("Private source is closed")
        ensureValid()
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), remaining).toInt()
        val count = try { source.readAt(readPosition, buffer, offset, requested) }
        catch (failure: Exception) {
            buffer.fill(0, offset, offset + requested)
            throw IOException("Private read failed", failure)
        }
        if (!source.valid.value) {
            buffer.fill(0, offset, offset + requested)
            throw IOException("Private source was revoked")
        }
        if (count <= 0 || count > requested) {
            buffer.fill(0, offset, offset + requested)
            throw EOFException("Private source ended before its authenticated size")
        }
        readPosition = Math.addExact(readPosition, count.toLong())
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = expectedUri.takeIf { opened }

    /** ExoPlayer closes/reopens on seek. Only the UI owner closes the underlying private lease. */
    override fun close() {
        val wasOpen = opened
        opened = false
        readPosition = 0L
        remaining = 0L
        if (wasOpen) transferEnded()
    }

    private fun ensureValid() {
        if (!source.valid.value) throw IOException("Private source was revoked")
    }
}
