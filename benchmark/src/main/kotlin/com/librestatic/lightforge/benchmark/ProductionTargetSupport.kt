package com.librestatic.lightforge.benchmark

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val PRODUCTION_PACKAGE = "com.librestatic.lightforge"
private const val PRODUCTION_EXTRA = "com.librestatic.lightforge.extra.PRODUCTION_TIMELINE"

/**
 * Makes a production-runtime launch reach the timeline without clearing app data: grants media
 * access through the instrumentation, then launches once, skips the first-run wizard if it shows
 * and force-stops. Call it before `measureRepeated` so cold-start iterations stay cold.
 */
internal fun prepareProductionTarget() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(PRODUCTION_PACKAGE, it) }
    val device = UiDevice.getInstance(instrumentation)
    device.executeShellCommand("am force-stop $PRODUCTION_PACKAGE")
    device.pressHome()
    device.executeShellCommand(
        "am start -W -n $PRODUCTION_PACKAGE/.MainActivity --ez $PRODUCTION_EXTRA true",
    )
    // The wizard is not under the production root, so it exposes no test tags: match its Skip label.
    val context = instrumentation.context
    val skip = By.text(targetString(context, "onboarding_skip"))
    check(waitForAny(device, 30_000, By.res("timeline_grid"), skip)) {
        "Neither the timeline nor the onboarding wizard appeared"
    }
    if (device.hasObject(skip)) {
        device.findObject(skip).click()
        // The first gallery frame offers local analysis opt-in; decline so it never covers the benchmark.
        val decline = By.text(targetString(context, "local_analysis_opt_out_decline"))
        device.wait(Until.findObject(decline), 15_000)?.click()
    }
    checkNotNull(device.wait(Until.findObject(By.res("timeline_grid")), 60_000)) {
        "Production timeline did not appear after onboarding"
    }
    device.waitForIdle(2_000)
    device.executeShellCommand("am force-stop $PRODUCTION_PACKAGE")
}

internal fun targetString(context: Context, name: String): String {
    val resources = context.packageManager.getResourcesForApplication(PRODUCTION_PACKAGE)
    val id = resources.getIdentifier(name, "string", PRODUCTION_PACKAGE)
    check(id != 0) { "Missing target string $name" }
    return resources.getString(id)
}

private fun waitForAny(device: UiDevice, timeoutMs: Long, vararg selectors: androidx.test.uiautomator.BySelector): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (selectors.any { device.hasObject(it) }) return true
        Thread.sleep(250)
    }
    return false
}
