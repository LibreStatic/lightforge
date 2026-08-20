package com.ugallery.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M2ManagementUiDeviceTest {
    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun launch() {
        device = UiDevice.getInstance(instrumentation)
        context = instrumentation.targetContext
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} $permission",
            ).close()
        }
        device.executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}",
        )
        assertTrue(device.wait(Until.hasObject(By.res("timeline_grid")), 10_000))
    }

    @Test fun longPressExposesLocalizedScalableActionsAndCancelRetry() {
        val selectAll = context.getString(R.string.selection_select_all)
        val moveToTrash = context.getString(R.string.selection_trash)
        val grid = device.findObject(By.res("timeline_grid"))
        val bounds = grid.visibleBounds
        val mediaX = bounds.left + bounds.width() / 6
        val mediaY = bounds.top + bounds.width() / 3
        device.executeShellCommand("input swipe $mediaX $mediaY $mediaX $mediaY 800")
        val more = context.getString(com.ugallery.feature.viewer.R.string.viewer_more)
        assertTrue(device.wait(Until.hasObject(By.desc(more)), 5_000))
        device.findObject(By.desc(more)).click()
        assertTrue(device.wait(Until.hasObject(By.text(selectAll)), 5_000))
        device.findObject(By.text(selectAll)).click()
        assertTrue(device.wait(Until.hasObject(By.text(moveToTrash)), 5_000))
        assertTrue(device.hasObject(By.text(moveToTrash)))
        assertTrue(device.hasObject(By.text(context.getString(R.string.selection_add_album))))

        device.findObject(By.desc(more)).click()
        assertTrue(
            device.wait(
                Until.hasObject(By.text(context.getString(R.string.selection_clear))),
                5_000,
            ),
        )
        device.pressBack()

        device.findObject(By.text(moveToTrash)).click()
        assertTrue(device.wait(Until.hasObject(By.res("android", "button2")), 5_000))
        device.findObject(By.res("android", "button2")).click()
        assertTrue(
            device.wait(
                Until.hasObject(By.text(context.getString(R.string.action_retry))),
                5_000,
            ),
        )
    }

    @Test fun viewerDetailsOpensAsScrollableSheetWithoutCrashing() {
        val grid = device.findObject(By.res("timeline_grid"))
        val bounds = grid.visibleBounds
        device.click(
            bounds.left + bounds.width() / 6,
            bounds.top + bounds.width() / 3,
        )

        val detailsAction = context.getString(com.ugallery.feature.viewer.R.string.viewer_details)
        assertTrue(device.wait(Until.hasObject(By.text(detailsAction)), 5_000))
        device.findObject(By.text(detailsAction)).click()

        val detailsTitle = context.getString(com.ugallery.feature.details.R.string.details_title)
        assertTrue(device.wait(Until.hasObject(By.text(detailsTitle)), 5_000))
        assertTrue(device.hasObject(By.pkg(context.packageName)))
    }

}
