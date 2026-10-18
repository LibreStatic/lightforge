package com.librestatic.lightforge.core.mediastore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * Android 12+ "Media management" special access (MANAGE_MEDIA). When granted, the platform
 * auto-approves MediaStore create*Request prompts for this app's batch write/trash/delete calls.
 */
object MediaManagementAccess {
    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    fun requestIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
}
