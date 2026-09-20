package com.ugallery.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalBackupTasksContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "backup-tasks-ui-${UUID.randomUUID()}").apply { mkdirs() }

        override fun getFilesDir() = root

        override fun getApplicationContext(): Context = this
    }

    @Test
    fun historyPauseResumeAndConfirmedCancellationUseDurableTaskState() {
        val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = LocalBackupTaskStore(context)
        val id = UUID.randomUUID().toString()
        store.create(
            LocalBackupTask(id, LocalBackupTaskKind.Backup, 1, "Queued backup", filesTotal = 2)
        )
        val scheduled = mutableListOf<String>()
        val controller =
            LocalBackupTaskController(context) { synchronized(scheduled) { scheduled += it } }
        try {
            compose.setContent {
                UGalleryTheme { LocalBackupTasksContent(controller, onBack = {}) }
            }
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag("local-backup-task-pause-$id")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("local-backup-task-pause-$id").performScrollTo().performClick()
            compose.waitUntil(10000) { store.read(id)!!.status == LocalBackupTaskStatus.Paused }
            compose.onNodeWithTag("local-backup-task-resume-$id").performScrollTo().performClick()
            compose.waitUntil(10000) { synchronized(scheduled) { id in scheduled } }
            assertEquals(LocalBackupTaskStatus.Queued, store.read(id)!!.status)
            compose.onNodeWithTag("local-backup-task-cancel-$id").performScrollTo().performClick()
            compose.onNodeWithTag("local-backup-task-cancel-confirm").performClick()
            compose.waitUntil(10000) { store.read(id)!!.cancelRequested }
            assertEquals(LocalBackupTaskStatus.Cancelling, store.read(id)!!.status)
        } finally {
            controller.close()
            context.root.deleteRecursively()
        }
    }

    @Test
    fun darkLargeTextCorruptTaskRemainsVisibleWithoutUnsafeActions() {
        val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = LocalBackupTaskStore(context)
        val id = UUID.randomUUID().toString()
        store.create(LocalBackupTask(id, LocalBackupTaskKind.RestoreGallery, 1, "corrupt"))
        File(store.directory(id), "task.json").writeText("bad snapshot")
        val controller = LocalBackupTaskController(context) { error("Corrupt task scheduled") }
        try {
            compose.setContent {
                UGalleryTheme(darkTheme = true) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, 1.6f)
                    ) {
                        LocalBackupTasksContent(controller, onBack = {})
                    }
                }
            }
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag("local-backup-task-$id")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("local-backup-task-$id").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("local-backup-task-resume-$id").assertDoesNotExist()
            compose.onNodeWithTag("local-backup-task-cancel-$id").assertDoesNotExist()
            compose.onNodeWithTag("local-backup-task-forget-$id").assertDoesNotExist()
            assertTrue(id in store.retainedGalleryOperations())
        } finally {
            controller.close()
            context.root.deleteRecursively()
        }
    }
}
