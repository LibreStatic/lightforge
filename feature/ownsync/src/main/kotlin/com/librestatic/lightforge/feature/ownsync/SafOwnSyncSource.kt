package com.librestatic.lightforge.feature.ownsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.librestatic.lightforge.core.remotestorage.RemoteNames
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A failed or loading cursor is an incomplete scan, never evidence that a source was removed. */
class SafOwnSyncSource(context: Context) : OwnSyncSourcePort {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override fun retain(jobId: String, tree: Uri) {
        require(
            java.util.UUID.fromString(jobId).toString() == jobId &&
                DocumentsContract.isTreeUri(tree)
        )
        val dir = File(context.filesDir, "own-sync/grants").apply { mkdirs() }
        val atomic = AtomicFile(File(dir, jobId))
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            require(
                atomic.openRead().use { OwnSyncIO.bounded(it, 16384).toString(Charsets.UTF_8) } ==
                    tree.toString()
            )
        }
        val out = atomic.startWrite()
        try {
            out.write(tree.toString().toByteArray())
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) {
            require(resolver.persistedUriPermissions.size < 480)
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission })
            throw SecurityException()
    }

    override fun open(entry: OwnSyncSourceEntry): InputStream =
        resolver.openInputStream(Uri.parse(entry.uri)) ?: throw IOException("Missing source")

    override suspend fun scan(tree: Uri, check: () -> Unit): OwnSyncSnapshot =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<OwnSyncSourceEntry>()
            val issues = mutableListOf<String>()
            val directories = mutableListOf<List<String>>()
            val visited = mutableSetOf<String>()
            var total = 0L
            fun visit(id: String, path: List<String>) {
                check()
                if (path.size >= 64 || !visited.add(id) || visited.size > 20000) {
                    issues += "limit-or-cycle"
                    return
                }
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                try {
                    val cursor =
                        resolver.query(
                            uri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_SIZE,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            ),
                            null,
                            null,
                            null,
                        ) ?: throw IOException()
                    cursor.use { c ->
                        if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
                            issues += "loading"
                        val names = mutableSetOf<String>()
                        while (c.moveToNext()) {
                            check()
                            if (entries.size >= 10000) {
                                issues += "file-limit"
                                break
                            }
                            val childId = c.getString(0)
                            val name = c.getString(1)
                            val mime = c.getString(2).orEmpty()
                            if (
                                runCatching { RemoteNames.requireChild(name) }.isFailure ||
                                    !names.add(name)
                            ) {
                                issues += "invalid-or-duplicate-name"
                                continue
                            }
                            val next = path + name
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                directories += next
                                visit(childId, next)
                            } else {
                                val sourceUri =
                                    DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                                val modified = if (c.isNull(4)) 0 else c.getLong(4)
                                try {
                                    val digest =
                                        resolver.openInputStream(sourceUri)?.use {
                                            OwnSyncIO.copy(it, check = check)
                                        } ?: throw IOException()
                                    if (!c.isNull(3) && c.getLong(3) != digest.size)
                                        throw IOException()
                                    val stable =
                                        resolver
                                            .query(
                                                sourceUri,
                                                arrayOf(
                                                    DocumentsContract.Document.COLUMN_SIZE,
                                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                                ),
                                                null,
                                                null,
                                                null,
                                            )
                                            ?.use { after ->
                                                after.moveToFirst() &&
                                                    (after.isNull(0) ||
                                                        after.getLong(0) == digest.size) &&
                                                    (after.isNull(1) ||
                                                        after.getLong(1) == modified)
                                            } ?: false
                                    if (!stable) throw IOException()
                                    total += digest.size
                                    if (total > OwnSyncIO.MaxTotal) {
                                        issues += "byte-limit"
                                        return
                                    }
                                    entries +=
                                        OwnSyncSourceEntry(
                                            ownSyncHash(
                                                (tree.toString() +
                                                        "\n" +
                                                        childId +
                                                        "\n" +
                                                        next.joinToString("/"))
                                                    .toByteArray()
                                            ),
                                            sourceUri.toString(),
                                            next,
                                            mime,
                                            digest,
                                            modified,
                                        )
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    issues += "unreadable-or-changed:" + next.joinToString("/")
                                }
                            }
                        }
                        if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
                            issues += "loading"
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: SecurityException) {
                    issues += "permission"
                } catch (_: Exception) {
                    issues += "unreadable-directory:" + path.joinToString("/")
                }
            }
            visit(DocumentsContract.getTreeDocumentId(tree), emptyList())
            OwnSyncSnapshot(entries, issues.distinct().take(10001), directories)
        }
}
