package com.librestatic.lightforge.feature.permissions

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.model.GrantLevel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PermissionCoordinatorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation: UiAutomation = instrumentation.uiAutomation
    private val packageName = context.packageName

    @Test
    fun b_foregroundResumeRevalidatesPlatformGrant() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val coordinator = PermissionCoordinator(context)
        val lifecycleOwner = TestLifecycleOwner()

        automation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_IMAGES)
        automation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_VIDEO)
        instrumentation.runOnMainSync {
            lifecycleOwner.registry.addObserver(coordinator)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        assertEquals(GrantLevel.Full, coordinator.access.value.images)
        assertEquals(GrantLevel.Full, coordinator.access.value.videos)
    }

    @Test
    fun a_api34SelectedGrantNeverClaimsFullAccess() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        automation.grantRuntimePermission(
            packageName,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        val access = PermissionCoordinator(context).revalidate()
        assertEquals(GrantLevel.Selected, access.images)
        assertEquals(GrantLevel.Selected, access.videos)
    }

    @Test
    fun c_api30To32LegacyGrantAppliesToImagesAndVideos() {
        assumeTrue(Build.VERSION.SDK_INT in 30..32)
        val coordinator = PermissionCoordinator(context)
        assertEquals(GrantLevel.None, coordinator.access.value.images)
        assertEquals(GrantLevel.None, coordinator.access.value.videos)

        automation.grantRuntimePermission(packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        val access = coordinator.revalidate()

        assertEquals(GrantLevel.Full, access.images)
        assertEquals(GrantLevel.Full, access.videos)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
