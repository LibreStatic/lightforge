package com.librestatic.lightforge.core.storageadvanced

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.os.storage.StorageManager
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.DirectoryContents
import com.librestatic.lightforge.core.mediastore.DirectoryEntry
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenMediaKind
import com.librestatic.lightforge.core.mediastore.HiddenMediaRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * java.io.File implementation of [AdvancedStorage] (ADR-046). The only code in the app that
 * touches shared storage by path. Every mutating call returns [AdvancedResult.NotGranted] without
 * All files access; [hiddenByApp] stays readable so the user can still see what was hidden.
 */
class FileAdvancedStorage(context: Context, private val recoveryOnly: Boolean = false) : AdvancedStorage {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val engine = StorageEngine(
        registry = HiddenRegistry(File(appContext.noBackupFilesDir, "advanced-storage/hidden-registry.json")),
        volumes = { mountedVolumes() },
        scan = ::scanAndWait,
        isGranted = ::isGranted,
        deleteOwnedMarker = ::deleteOwnedMarker,
    )

    override val isSupported: Boolean = !recoveryOnly

    override fun isGranted(): Boolean = !recoveryOnly && Environment.isExternalStorageManager()

    override fun permissionIntent(): Intent? {
        if (recoveryOnly) return null
        val uri = Uri.parse("package:${appContext.packageName}")
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
        val intent = if (specific.resolveActivity(appContext.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun absolutePathOf(volumeName: String, relativePath: String?, displayName: String?): String? =
        engine.absolutePathOf(volumeName, relativePath, displayName)

    override suspend fun hideFolder(absolutePath: String): AdvancedResult =
        guarded { engine.hide(absolutePath) }

    /** Works without the permission when the marker is ours (owner delete); otherwise NeedsTreeGrant. */
    override suspend fun unhideFolder(absolutePath: String): AdvancedResult =
        unguarded { engine.unhide(absolutePath) }

    override suspend fun unhideWithTree(absolutePath: String, treeUri: Uri): AdvancedResult =
        unguarded {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@unguarded AdvancedResult.Failed("Not a folder grant")
            engine.unhideWithTree(absolutePath, docId) { marker ->
                val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$docId/${marker.name}")
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, child) }.getOrDefault(false)
            }
        }

    override suspend fun hiddenByApp(): List<HiddenEntry> =
        mutex.withLock { withContext(Dispatchers.IO) { engine.hiddenByApp() } }

    override suspend fun unhideAllRecorded(): List<HiddenEntry> =
        mutex.withLock { withContext(Dispatchers.IO) { engine.unhideAll() } }

    override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult =
        guarded { engine.renameInPlace(sourceAbsolutePath, targetAbsolutePath) }

    override suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult =
        guarded { engine.deleteEmptyDirectory(absolutePath) }

    override suspend fun createDirectory(parentAbsolutePath: String, name: String): AdvancedResult =
        guarded { engine.createDirectory(parentAbsolutePath, name) }

    override suspend fun renameDirectory(absolutePath: String, newName: String): AdvancedResult =
        guarded { engine.renameDirectory(absolutePath, newName) }

    override suspend fun moveDirectory(absolutePath: String, newParentAbsolutePath: String): AdvancedResult =
        guarded { engine.moveDirectory(absolutePath, newParentAbsolutePath) }

    override suspend fun moveAll(moves: List<Pair<String, String>>): List<AdvancedResult> =
        mutex.withLock {
            if (!isGranted()) return@withLock moves.map { AdvancedResult.NotGranted }
            withContext(Dispatchers.IO) {
                try {
                    engine.moveAll(moves)
                } catch (_: SecurityException) {
                    moves.map { AdvancedResult.NotGranted }
                }
            }
        }

    override suspend fun copyAll(copies: List<Pair<String, String>>): List<AdvancedResult> =
        mutex.withLock {
            if (!isGranted()) return@withLock copies.map { AdvancedResult.NotGranted }
            withContext(Dispatchers.IO) {
                try {
                    engine.copyAll(copies)
                } catch (_: SecurityException) {
                    copies.map { AdvancedResult.NotGranted }
                }
            }
        }

    override suspend fun pathExists(absolutePath: String): Boolean =
        unguardedValue { engine.exists(absolutePath) } ?: false

    override suspend fun deleteFiles(absolutePaths: List<String>): List<AdvancedResult> =
        mutex.withLock {
            if (!isGranted()) return@withLock absolutePaths.map { AdvancedResult.NotGranted }
            withContext(Dispatchers.IO) {
                try {
                    engine.deleteFiles(absolutePaths)
                } catch (_: SecurityException) {
                    absolutePaths.map { AdvancedResult.NotGranted }
                }
            }
        }

    override suspend fun copyDirectory(absolutePath: String, newParentAbsolutePath: String, newName: String?): AdvancedResult =
        guarded { engine.copyDirectory(absolutePath, newParentAbsolutePath, newName) }

    override suspend fun deleteDirectory(absolutePath: String): AdvancedResult =
        guarded { engine.deleteDirectory(absolutePath) }

    override suspend fun inspectDirectory(absolutePath: String): DirectoryContents? =
        mutex.withLock {
            if (!isGranted()) return@withLock null
            withContext(Dispatchers.IO) { runCatching { engine.inspectDirectory(absolutePath) }.getOrNull() }
        }

    override suspend fun listDirectories(absolutePath: String): List<DirectoryEntry> =
        mutex.withLock {
            if (!isGranted()) return@withLock emptyList()
            withContext(Dispatchers.IO) {
                try {
                    engine.listDirectories(absolutePath)
                } catch (_: SecurityException) {
                    emptyList()
                }
            }
        }

    override suspend fun queryHiddenMedia(): List<HiddenMediaRow> {
        if (!isGranted()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                MediaStore.getExternalVolumeNames(appContext).flatMap { queryHiddenMedia(it) }
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }

    private fun queryHiddenMedia(volume: String): List<HiddenMediaRow> {
        val filesUri = MediaStore.Files.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND " +
            "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%')" +
            " OR (${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
            "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE '.%' OR ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE '%/.%')))" +
            " AND ${MediaStore.Files.FileColumns.IS_PENDING}=0 AND ${MediaStore.Files.FileColumns.IS_TRASHED}=0" +
            " AND ${MediaStore.Files.FileColumns._ID}>?"
        val rows = ArrayList<HiddenMediaRow>()
        var lastId = 0L
        while (true) {
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(lastId.toString()))
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
            }
            var count = 0
            appContext.contentResolver.query(filesUri, projection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    val id = cursor.getLong(0)
                    lastId = id
                    val mime = cursor.getString(3) ?: continue
                    val taken = cursor.getLong(5)
                    rows += HiddenMediaRow(
                        volumeName = volume,
                        mediaStoreId = id,
                        relativePath = cursor.getString(1).orEmpty(),
                        displayName = cursor.getString(2).orEmpty(),
                        mimeType = mime,
                        kind = if (mime.startsWith("video/")) HiddenMediaKind.Video else HiddenMediaKind.Image,
                        sizeBytes = cursor.getLong(4),
                        dateTakenOrModifiedMillis = if (taken > 0) taken else cursor.getLong(6) * 1000L,
                        contentUri = ContentUris.withAppendedId(filesUri, id),
                    )
                }
            }
            if (count < PAGE_SIZE) break
        }
        return rows
    }

    private fun mountedVolumes(): List<VolumeRoot> =
        appContext.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume -> volume.directory?.let { VolumeRoot(volume.mediaStoreVolumeName ?: return@mapNotNull null, it) } }

    /**
     * One scan call for all paths of an operation; returns when every callback arrived or after
     * [SCAN_TIMEOUT_SECONDS]. Runs on the IO thread of the caller (the engine is blocking).
     */
    private fun scanAndWait(paths: List<String>) {
        val unique = paths.distinct()
        if (unique.isEmpty()) return
        val latch = CountDownLatch(unique.size)
        MediaScannerConnection.scanFile(appContext, unique.toTypedArray(), null) { _, _ -> latch.countDown() }
        latch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Owner delete of the `.nomedia` Files row; needs no storage permission for files this app created. */
    private fun deleteOwnedMarker(marker: File): Boolean {
        val deleted = try {
            appContext.contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.DATA}=?",
                arrayOf(marker.path),
            )
        } catch (_: SecurityException) {
            0
        }
        return deleted > 0 && !marker.exists()
    }

    private suspend fun guarded(block: () -> AdvancedResult): AdvancedResult =
        mutex.withLock {
            // Re-read inside the lock: the user can revoke while an earlier operation held it.
            if (!isGranted()) {
                AdvancedResult.NotGranted
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        block()
                    } catch (_: SecurityException) {
                        AdvancedResult.NotGranted
                    }
                }
            }
        }

    private suspend fun <T> unguardedValue(block: () -> T): T? =
        mutex.withLock { withContext(Dispatchers.IO) { runCatching(block).getOrNull() } }

    private suspend fun unguarded(block: () -> AdvancedResult): AdvancedResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (_: SecurityException) {
                    AdvancedResult.NotGranted
                }
            }
        }

    private companion object {
        const val PAGE_SIZE = 500
        const val SCAN_TIMEOUT_SECONDS = 10L
    }
}
