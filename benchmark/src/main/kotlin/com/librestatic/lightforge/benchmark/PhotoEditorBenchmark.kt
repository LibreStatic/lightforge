package com.librestatic.lightforge.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the photo editor from the viewer and switches between its tabs on the production runtime.
 *
 * Precondition: the device library holds photos (see docs/benchmarks/README.md, seeded dataset).
 * It uses the first library photo that offers an Edit action instead of publishing a fixture the
 * way the profile journeys do: the macrobenchmark module has no MediaStore fixture helper, the
 * editor cost does not depend on which photo it is, and a seeded device is already required by
 * every other Production* benchmark.
 */
@RunWith(AndroidJUnit4::class)
class PhotoEditorBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun openEditorAndSwitchTabs() {
        prepareProductionTarget()
        val context = InstrumentationRegistry.getInstrumentation().context
        val editLabel = targetString(context, "viewer_edit")
        val tabLabels = listOf(
            "photo_editor_adjust", "photo_editor_tab_actions", "photo_editor_filters",
            "photo_editor_tab_more", "photo_editor_export_tab", "photo_editor_automatic",
        ).map { targetString(context, it) }
        val titleLabel = targetString(context, "photo_editor_title")
        rule.measureRepeated(
            packageName = PRODUCTION_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                killProcess()
                startActivityAndWait { it.putExtra(PRODUCTION_TIMELINE, true) }
                check(device.wait(Until.hasObject(By.res("timeline_grid")), 30_000)) {
                    "Production timeline did not appear; seed photos first"
                }
                openFirstEditablePhoto(device, editLabel)
            },
        ) {
            device.findObject(By.desc(editLabel)).click()
            check(device.wait(Until.hasObject(By.text(titleLabel)), 30_000)) { "Photo editor did not open" }
            device.waitForIdle(2_000)
            tabLabels.forEach { label ->
                findEditorTab(device, label)?.click()
                device.waitForIdle(2_000)
            }
        }
    }
}

private const val PRODUCTION_TIMELINE = "com.librestatic.lightforge.extra.PRODUCTION_TIMELINE"

/** The editor exposes no test tags: find a tab by label in the horizontally scrolling bottom strip. */
private fun findEditorTab(device: UiDevice, label: String): UiObject2? {
    val strip = device.findObjects(By.scrollable(true)).lastOrNull {
        it.visibleBounds.top > device.displayHeight * 4 / 5
    } ?: return null
    strip.findObject(By.text(label))?.let { return it }
    for (direction in listOf(Direction.RIGHT, Direction.LEFT)) {
        strip.scroll(direction, 1.0f)
        device.waitForIdle(1_000)
        strip.findObject(By.text(label))?.let { return it }
    }
    return null
}

/** Taps visible timeline cells in order until the viewer shows the Edit action (skips videos). */
private fun openFirstEditablePhoto(device: UiDevice, editLabel: String) {
    val cells = By.res(Pattern.compile("media_.*_[0-9]+"))
    val candidates = device.findObjects(cells).sortedWith(
        // Plain photos first (RAW opens a heavier develop pipeline), then timeline order.
        compareBy({ it.contentDescription?.contains("RAW") == true }, { it.visibleBounds.top }, { it.visibleBounds.left }),
    )
    check(candidates.isNotEmpty()) { "No timeline media cells; seed photos first" }
    val names = candidates.take(8).mapNotNull { it.resourceName }
    for (name in names) {
        device.findObject(By.res(name))?.click() ?: continue
        if (device.wait(Until.hasObject(By.desc(editLabel)), 5_000)) return
        device.pressBack()
        device.wait(Until.hasObject(By.res("timeline_grid")), 5_000)
    }
    error("None of the first library items offers Edit; seed photos first")
}
