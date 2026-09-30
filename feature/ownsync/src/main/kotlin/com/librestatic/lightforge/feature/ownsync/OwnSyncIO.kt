package com.librestatic.lightforge.feature.ownsync

import com.librestatic.lightforge.core.remotestorage.RemoteDigest
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal object OwnSyncIO {
    const val MaxFile = 50L * 1024 * 1024 * 1024
    const val MaxTotal = 100L * 1024 * 1024 * 1024

    fun copy(
        input: InputStream,
        output: OutputStream? = null,
        check: () -> Unit = {},
    ): RemoteDigest {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            size += count
            require(size <= MaxFile)
            md.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        check()
        return RemoteDigest(size, md.digest().joinToString("") { "%02x".format(it) })
    }

    fun bounded(input: InputStream, maximum: Int = 32 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            require(out.size() + n <= maximum)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
