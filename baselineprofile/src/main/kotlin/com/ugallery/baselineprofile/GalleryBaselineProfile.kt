package com.ugallery.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TargetPackage = "com.ugallery.app"
private const val ItemCountExtra = "com.ugallery.app.extra.BENCHMARK_ITEM_COUNT"

@RunWith(AndroidJUnit4::class)
class GalleryBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndTimeline() = rule.collect(
        packageName = TargetPackage,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait { it.putExtra(ItemCountExtra, 100_000) }
        check(device.wait(Until.hasObject(By.res("timeline_grid")), 5_000)) {
            "Timeline grid was not exposed"
        }
        repeat(8) {
            val grid = device.findObject(By.res("timeline_grid"))
                ?: error("Timeline grid disappeared during profile collection")
            grid.fling(Direction.DOWN)
            device.waitForIdle(1_000)
        }
    }
}
