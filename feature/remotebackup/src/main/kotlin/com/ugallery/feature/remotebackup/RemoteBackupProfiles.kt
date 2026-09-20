package com.ugallery.feature.remotebackup

import android.content.Context
import com.ugallery.core.remotestorage.RemoteProfile

/** Shared metadata reader; credentials remain exclusively in the encrypted vault. */
object RemoteBackupProfiles {
    fun read(context: Context): List<RemoteProfile> = RemoteBackupStore(context).profiles()
}
