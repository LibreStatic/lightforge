package com.librestatic.lightforge.feature.settings

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LocalBackupSessionCleanupTest {
    @Test(timeout = 5000)
    fun leavingDuringOperationWaitsForRollbackBeforeDeletingStaging() = runBlocking {
        val directory = Files.createTempDirectory("backup-cleanup-order").toFile()
        val started = CompletableDeferred<Unit>()
        val rollingBack = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation =
            launch(Dispatchers.IO) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        rollingBack.complete(Unit)
                        release.await()
                    }
                }
            }
        started.await()
        val cleanup =
            LocalBackupSessionCleanup.closeAfterOperation(operation, false) {
                directory.deleteRecursively()
            }
        rollingBack.await()
        assertTrue(directory.exists())
        assertFalse(cleanup.isCompleted)
        release.complete(Unit)
        cleanup.join()
        assertFalse(directory.exists())
        assertTrue(operation.isCompleted)
    }

    @Test(timeout = 5000)
    fun configurationRecreationWaitsForCancellationButRetainsItsSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val operation =
            launch(Dispatchers.IO) {
                started.complete(Unit)
                awaitCancellation()
            }
        started.await()
        var closed = false
        LocalBackupSessionCleanup.closeAfterOperation(operation, true) { closed = true }.join()
        assertTrue(operation.isCompleted)
        assertFalse(closed)
    }
}
