@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package com.ugallery.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.ugallery.app"
private const val BENCHMARK_ITEM_COUNT_EXTRA = "com.ugallery.app.extra.BENCHMARK_ITEM_COUNT"
private const val PRODUCTION_TIMELINE_EXTRA = "com.ugallery.app.extra.PRODUCTION_TIMELINE"

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithBaselineProfile() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun coldStartupWithoutProfile() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }
}

@RunWith(AndroidJUnit4::class)
class TimelineScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun flingVirtual100kGrid() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { startActivityAndWait { it.putExtra(BENCHMARK_ITEM_COUNT_EXTRA, 100_000) } },
    ) {
        val grid = device.wait(Until.findObject(By.res("timeline_grid")), 5_000)
            ?: error("Timeline grid was not exposed to UI Automator")
        repeat(12) { grid.fling(Direction.DOWN) }
    }
}

@RunWith(AndroidJUnit4::class)
class TimelineDensityAnchorBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun pinchDensityKeepsVisibleAnchor() {
        var expectedAnchor = -1
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 3,
            setupBlock = {
                killProcess()
                startActivityAndWait {
                    it.putExtra(BENCHMARK_ITEM_COUNT_EXTRA, 100_000)
                }
                val grid = device.wait(Until.findObject(By.res("timeline_grid")), 5_000)
                    ?: error("Timeline grid was not exposed to UI Automator")
                repeat(4) { grid.fling(Direction.DOWN) }
                val visibleBefore = visibleMediaIndices(device).sorted()
                expectedAnchor = visibleBefore.getOrNull(visibleBefore.size / 2)
                    ?: error("No visible media cell exposed an anchor")
            },
        ) {
            val grid = device.wait(Until.findObject(By.res("timeline_grid")), 5_000)
                ?: error("Timeline grid was not exposed to UI Automator")
            grid.pinchClose(0.75f, 200)
            check(device.wait(Until.hasObject(By.text("4×")), 5_000)) {
                "Pinch gesture did not change the grid from three to four columns"
            }
            device.waitForIdle(5_000)
            val visibleAfter = visibleMediaIndices(device)
            check(expectedAnchor in visibleAfter) {
                "Density change lost anchor media_$expectedAnchor; visible=$visibleAfter"
            }
        }
    }
}

private val mediaCellResource = Pattern.compile("media_[0-9]+")

private fun visibleMediaIndices(device: UiDevice): Set<Int> =
    device.findObject(By.res("timeline_grid"))?.visibleBounds?.let { gridBounds ->
        device.findObjects(By.res(mediaCellResource))
            .filter { cell ->
                val bounds = cell.visibleBounds
                !bounds.isEmpty && android.graphics.Rect.intersects(gridBounds, bounds)
            }
            .mapNotNull { cell -> cell.resourceName?.substringAfterLast("media_")?.toIntOrNull() }
            .toSet()
    }.orEmpty()

@RunWith(AndroidJUnit4::class)
class TimelineStress250kBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun flingVirtual250kGrid() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            startActivityAndWait { it.putExtra(BENCHMARK_ITEM_COUNT_EXTRA, 250_000) }
        },
    ) {
        val grid = device.wait(Until.findObject(By.res("timeline_grid")), 5_000)
            ?: error("Timeline grid was not exposed to UI Automator")
        repeat(12) { grid.fling(Direction.DOWN) }
    }
}

@RunWith(AndroidJUnit4::class)
class TimelineMemory250kBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun peakMemoryVirtual250kGrid() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            startActivityAndWait { it.putExtra(BENCHMARK_ITEM_COUNT_EXTRA, 250_000) }
        },
    ) {
        val grid = device.wait(Until.findObject(By.res("timeline_grid")), 5_000)
            ?: error("Timeline grid was not exposed to UI Automator")
        repeat(12) { grid.fling(Direction.DOWN) }
    }
}

@RunWith(AndroidJUnit4::class)
class ProductionTimelineScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun scrollIndexedPhysicalLibrary() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_IMAGES")
            device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_VIDEO")
            startActivityAndWait { it.putExtra(PRODUCTION_TIMELINE_EXTRA, true) }
            check(device.wait(Until.hasObject(By.res("timeline_grid")), 30_000)) {
                "Production timeline did not finish initial indexing"
            }
        },
    ) {
        repeat(12) {
            val grid = device.findObject(By.res("timeline_grid"))
                ?: error("Production timeline disappeared")
            grid.fling(Direction.DOWN)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ProductionTimelineAnchorBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun pinchAndRotationKeepMediaAnchor() {
        var expectedAnchor = ""
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 1,
            setupBlock = {
                killProcess()
                device.executeShellCommand("pm clear $TARGET_PACKAGE")
                device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_IMAGES")
                device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_VIDEO")
                startActivityAndWait { it.putExtra(PRODUCTION_TIMELINE_EXTRA, true) }
                check(device.wait(Until.hasObject(By.res("timeline_grid")), 30_000))
                repeat(4) {
                    device.findObject(By.res("timeline_grid"))!!.fling(Direction.DOWN)
                }
                device.waitForIdle(5_000)
                expectedAnchor = waitForStableProductionAnchor(device)
                    ?: error("Production timeline exposed no leading media anchor")
            },
        ) {
            val grid = device.findObject(By.res("timeline_grid"))
                ?: error("Production timeline disappeared")
            grid.pinchClose(0.75f, 200)
            device.waitForIdle(5_000)
            val visibleAfterPinch = visibleProductionMedia(device)
            check(expectedAnchor in visibleAfterPinch) {
                "Pinch lost $expectedAnchor; visible=$visibleAfterPinch"
            }
            try {
                device.setOrientationLeft()
                check(device.wait(Until.hasObject(By.res("timeline_grid")), 5_000))
                device.waitForIdle(5_000)
                check(expectedAnchor in visibleProductionMedia(device)) {
                    "Rotation lost $expectedAnchor"
                }
            } finally {
                device.unfreezeRotation()
            }
        }
    }
}

private val productionMediaCellResource = Pattern.compile("media_.*_[0-9]+")

private fun visibleProductionMedia(device: UiDevice): Set<String> =
    device.findObject(By.res("timeline_grid"))?.visibleBounds?.let { gridBounds ->
        device.findObjects(By.res(productionMediaCellResource))
            .filter { cell ->
                val bounds = cell.visibleBounds
                !bounds.isEmpty && android.graphics.Rect.intersects(gridBounds, bounds)
            }
            .mapNotNull { it.resourceName }
            .toSet()
    }.orEmpty()

private fun firstVisibleProductionMedia(device: UiDevice): String? {
    val visibleResources = visibleProductionMedia(device)
    return device.findObjects(By.res(productionMediaCellResource))
        .filter { it.resourceName in visibleResources }
        .minByOrNull { cell ->
            val bounds = cell.visibleBounds
            bounds.top.toLong() * 10_000L + bounds.left
        }
        ?.resourceName
}

private fun waitForStableProductionAnchor(device: UiDevice): String? {
    val deadline = System.nanoTime() + 10_000_000_000L
    var candidate: String? = null
    var unchangedSamples = 0
    while (System.nanoTime() < deadline) {
        val current = centralVisibleProductionMedia(device)
        if (current != null && current == candidate) {
            unchangedSamples += 1
            if (unchangedSamples >= 5) return current
        } else {
            candidate = current
            unchangedSamples = 0
        }
        Thread.sleep(250)
    }
    return null
}

private fun centralVisibleProductionMedia(device: UiDevice): String? {
    val grid = device.findObject(By.res("timeline_grid")) ?: return null
    val visibleResources = visibleProductionMedia(device)
    val centerX = grid.visibleBounds.centerX()
    val centerY = grid.visibleBounds.centerY()
    return device.findObjects(By.res(productionMediaCellResource))
        .filter { it.resourceName in visibleResources }
        .minByOrNull { cell ->
            val bounds = cell.visibleBounds
            val dx = bounds.centerX() - centerX
            val dy = bounds.centerY() - centerY
            dx.toLong() * dx + dy.toLong() * dy
        }
        ?.resourceName
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndTimeline() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.findObject(By.res("timeline_grid")), 5_000)?.apply {
            repeat(4) { fling(Direction.DOWN) }
        }
    }
}
