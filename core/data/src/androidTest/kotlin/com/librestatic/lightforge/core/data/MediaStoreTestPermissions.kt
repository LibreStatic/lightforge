package com.librestatic.lightforge.core.data

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/** The test provider follows the same public permission split as the production reader. */
internal fun grantMediaStoreTestPermissions() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val permissions = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    permissions.forEach {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
    }
}
