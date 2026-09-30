package com.librestatic.lightforge

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Read-only navigation smoke. Worker persistence has separate process-recovery acceptance. */
class LocalBackupTasksAppDeviceTest {
    @Test
    fun settingsOpensBackupTasksAndBothBackActionsReturnToTheirParentScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val device = UiDevice.getInstance(instrumentation)
        val evidence =
            File(context.filesDir, "backup-tasks-navigation-${UUID.randomUUID()}").apply {
                mkdirs()
            }
        val steps = mutableListOf<String>()
        fun capture(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun find(selector: BySelector): UiObject2 {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    device.findObject(selector)?.let { if (!it.visibleBounds.isEmpty) return it }
                } catch (_: StaleObjectException) {}
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width() }
                    ?.scroll(Direction.DOWN, .6f)
                device.waitForIdle()
            }
            error("Missing local-backup navigation control $selector")
        }
        fun label(id: Int) = By.text(context.getString(id))
        fun await(selector: BySelector) {
            assertTrue("Missing $selector", device.wait(Until.hasObject(selector), 15_000))
        }
        fun click(selector: BySelector) {
            find(selector).click()
            device.waitForIdle()
        }
        val settings = label(com.librestatic.lightforge.feature.settings.R.string.settings_title)
        val backup = label(com.librestatic.lightforge.feature.settings.R.string.local_backup_title)
        val tasks = label(com.librestatic.lightforge.feature.settings.R.string.local_backup_tasks_title)
        val back = label(com.librestatic.lightforge.feature.settings.R.string.local_backup_back)
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            try {
                // These settings/backup composables do not export resource IDs for their test tags.
                // Real localized accessibility labels, not English literals or coordinates, select
                // them.
                click(
                    By.desc(context.getString(com.librestatic.lightforge.feature.photos.R.string.open_settings))
                )
                await(settings)
                steps += "Settings"
                click(label(com.librestatic.lightforge.feature.settings.R.string.settings_backup))
                click(backup)
                await(backup)
                await(label(com.librestatic.lightforge.feature.settings.R.string.local_backup_local_hint))
                steps += "Local backup"
                capture("local-backup")
                click(tasks)
                await(tasks)
                assertTrue(
                    "Tasks must be a separate route, not the launch button",
                    device.wait(Until.gone(backup), 15_000),
                )
                await(label(com.librestatic.lightforge.feature.settings.R.string.local_backup_task_durable_hint))
                steps += "Tasks"
                capture("tasks")
                click(back)
                await(backup)
                await(tasks) // The Tasks entry returns on the backup parent screen.
                steps += "Back to Local backup"
                click(back)
                await(settings)
                assertTrue(device.wait(Until.gone(tasks), 15_000))
                steps += "Back to Settings"
                capture("returned-settings")
                File(evidence, "result.txt")
                    .writeText(
                        steps.joinToString(" → ") +
                            "\nNo task creation, retry, cancellation, deletion or data seeding requested.\n"
                    )
            } catch (failure: Throwable) {
                runCatching { capture("failure") }.exceptionOrNull()?.let(failure::addSuppressed)
                File(evidence, "failure.txt")
                    .writeText(steps.joinToString(" → ") + "\n" + failure.stackTraceToString())
                throw failure
            }
        }
    }
}
