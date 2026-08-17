package com.ugallery.feature.permissions

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.model.GrantLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionCoordinatorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation: UiAutomation = instrumentation.uiAutomation
    private val packageName = context.packageName

    @After
    fun revokeTestGrants() {
        mediaPermissions.forEach(::revokeIfGranted)
    }

    @Test
    fun foregroundResumeRevalidatesPlatformGrantAndRevocation() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        mediaPermissions.forEach(::revokeIfGranted)
        val coordinator = PermissionCoordinator(context)
        val lifecycleOwner = TestLifecycleOwner()
        assertEquals(GrantLevel.None, coordinator.access.value.images)
        assertEquals(GrantLevel.None, coordinator.access.value.videos)

        automation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_IMAGES)
        automation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_VIDEO)
        instrumentation.runOnMainSync {
            lifecycleOwner.registry.addObserver(coordinator)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        assertEquals(GrantLevel.Full, coordinator.access.value.images)
        assertEquals(GrantLevel.Full, coordinator.access.value.videos)

        automation.revokeRuntimePermission(packageName, Manifest.permission.READ_MEDIA_IMAGES)
        automation.revokeRuntimePermission(packageName, Manifest.permission.READ_MEDIA_VIDEO)
        instrumentation.runOnMainSync {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        assertEquals(GrantLevel.None, coordinator.access.value.images)
        assertEquals(GrantLevel.None, coordinator.access.value.videos)
    }

    @Test
    fun api34SelectedGrantNeverClaimsFullAccess() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        mediaPermissions.forEach(::revokeIfGranted)
        automation.grantRuntimePermission(
            packageName,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        val access = PermissionCoordinator(context).revalidate()
        assertEquals(GrantLevel.Selected, access.images)
        assertEquals(GrantLevel.Selected, access.videos)
    }

    private fun revokeIfGranted(permission: String) {
        if (context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            automation.revokeRuntimePermission(packageName, permission)
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        val mediaPermissions = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }
}
