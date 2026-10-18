package com.librestatic.lightforge.core.storageadvanced

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.os.storage.StorageManager
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * java.io.File implementation of [AdvancedStorage] (ADR-046). The only code in the app that
 * touches shared storage by path. Every mutating call returns [AdvancedResult.NotGranted] without
 * All files access; [hiddenByApp] stays readable so the user can still see what was hidden.
 */
class FileAdvancedStorage(context: Context) : AdvancedStorage {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val engine = StorageEngine(
        registry = HiddenRegistry(File(appContext.noBackupFilesDir, "advanced-storage/hidden-registry.json")),
        volumeRoots = {
            appContext.getSystemService(StorageManager::class.java).storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { it.directory }
        },
        scan = { paths ->
            MediaScannerConnection.scanFile(appContext, paths.toTypedArray(), null, null)
        },
    )

    override val isSupported: Boolean = true

    override fun isGranted(): Boolean = Environment.isExternalStorageManager()

    override fun permissionIntent(): Intent {
        val uri = Uri.parse("package:${appContext.packageName}")
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
        val intent = if (specific.resolveActivity(appContext.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override suspend fun hideFolder(absolutePath: String): AdvancedResult =
        guarded { engine.hide(absolutePath) }

    override suspend fun unhideFolder(absolutePath: String): AdvancedResult =
        guarded { engine.unhide(absolutePath) }

    override suspend fun hiddenByApp(): List<HiddenEntry> =
        mutex.withLock { withContext(Dispatchers.IO) { engine.hiddenByApp() } }

    override suspend fun unhideAllRecorded(): List<HiddenEntry> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (isGranted()) engine.unhideAll() else engine.hiddenByApp()
            }
        }

    override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult =
        guarded { engine.renameInPlace(sourceAbsolutePath, targetAbsolutePath) }

    override suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult =
        guarded { engine.deleteEmptyDirectory(absolutePath) }

    private suspend fun guarded(block: () -> AdvancedResult): AdvancedResult {
        if (!isGranted()) return AdvancedResult.NotGranted
        return mutex.withLock { withContext(Dispatchers.IO) { block() } }
    }
}
