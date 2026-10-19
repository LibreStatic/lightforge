package com.librestatic.lightforge

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Opens [view] in another app. Lightforge's own activities are excluded so the file never reopens in a
 * second MainActivity, which would run a competing library scan.
 */
internal fun startExternalViewer(context: Context, view: Intent) {
    val own = context.packageName
    val ownComponents = context.packageManager.queryIntentActivities(view, 0)
        .map { it.activityInfo }
        .filter { it.packageName == own }
        .map { ComponentName(it.packageName, it.name) }
    val launch = if (ownComponents.isEmpty()) {
        view
    } else {
        Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownComponents.toTypedArray())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(launch)
    } catch (_: ActivityNotFoundException) {
        // No viewer for this type: nothing to open.
    }
}
