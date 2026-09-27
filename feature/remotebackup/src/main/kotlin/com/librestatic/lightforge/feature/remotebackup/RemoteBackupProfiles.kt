package com.librestatic.lightforge.feature.remotebackup

import android.content.Context
import com.librestatic.lightforge.core.remotestorage.RemoteProfile

/** Shared metadata reader; credentials remain exclusively in the encrypted vault. */
object RemoteBackupProfiles {
    fun read(context: Context): List<RemoteProfile> = RemoteBackupStore(context).profiles()
}
