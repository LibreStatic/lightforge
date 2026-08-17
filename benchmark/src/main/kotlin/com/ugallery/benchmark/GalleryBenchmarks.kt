@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package com.ugallery.benchmark

import androidx.benchmark.macro.CompilationMode
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

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
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
