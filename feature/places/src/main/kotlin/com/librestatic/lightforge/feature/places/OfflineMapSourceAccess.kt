package com.librestatic.lightforge.feature.places

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.Closeable
import java.io.InputStream

/** IO boundary permits deterministic provider failure tests without substituting map bytes. */
interface OfflineMapSourceAccess {
    fun hasRead(uri: Uri): Boolean

    fun retainRead(uri: Uri)

    fun releaseRead(uri: Uri)

    fun open(uri: Uri): OfflineMapInput
}

class OfflineMapInput(
    val stream: InputStream,
    val bytes: Long,
    private val cleanup: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            stream.close()
        } finally {
            cleanup()
        }
    }
}

class AndroidOfflineMapSourceAccess(context: Context) : OfflineMapSourceAccess {
    private val resolver = context.applicationContext.contentResolver

    override fun hasRead(uri: Uri) =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    override fun retainRead(uri: Uri) =
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun releaseRead(uri: Uri) =
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun open(uri: Uri): OfflineMapInput {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: error("SOURCE")
        return try {
            OfflineMapInput(afd.createInputStream(), afd.length) { afd.close() }
        } catch (t: Throwable) {
            afd.close()
            throw t
        }
    }
}
