package com.librestatic.lightforge.feature.settings

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalBackupTasksDeviceTest {
    private val authority = "com.librestatic.lightforge.feature.settings.test.localbackup"
    private val root
        get() = DocumentsContract.buildDocumentUri(authority, "root")

    private val tree
        get() = DocumentsContract.buildTreeDocumentUri(authority, "root")

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val directory =
            File(base.cacheDir, "durable-backup-test-${UUID.randomUUID()}").apply { mkdirs() }

        override fun getFilesDir() = directory

        override fun getApplicationContext(): Context = this
    }

    private fun fixture(
        block: suspend (FixtureContext, LocalBackupTaskController, LocalBackupTaskStore) -> Unit
    ) = runBlocking {
        val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val controller = LocalBackupTaskController(context) {}
        command(context, "fixture-reset")
        try {
            block(context, controller, LocalBackupTaskStore(context))
        } finally {
            command(context, "fixture-reset")
            controller.close()
            context.directory.deleteRecursively()
        }
    }

    private fun command(context: Context, name: String) =
        context.contentResolver.call(Uri.parse("content://$authority"), name, null, null)

    private fun created(context: Context, name: String, bytes: ByteArray): Uri {
        val uri =
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                "application/octet-stream",
                name,
            )!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    private fun bytes(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun archive(context: FixtureContext, payload: ByteArray): File =
        File(context.directory, "fixture.zip").also {
            LocalBackupArchive.create(
                listOf(
                    LocalBackupArchive.Source("original.jpg", "image/jpeg") {
                        payload.inputStream()
                    }
                ),
                it,
            )
        }

    private suspend fun await(condition: () -> Boolean) =
        withTimeout(15000) { while (!condition()) delay(20) }

    @Test
    fun backupAndRestoreSurviveControllerReopenAndHistoryRemovalPreservesPublishedBytes() =
        fixture { context, controller, store ->
            val payload = ByteArray(150000) { (it % 251).toByte() }
            val source = created(context, "original.jpg", payload)
            val destination = created(context, "backup.zip", byteArrayOf())
            val backup =
                controller.enqueueBackup(
                    listOf(source.toString()),
                    destination,
                    "backup.zip",
                    false,
                )
            assertEquals(LocalBackupTaskStatus.Queued, store.read(backup)!!.status)
            assertTrue(LocalBackupTaskRunner(context, null).run(backup))
            assertEquals(
                LocalBackupTaskStatus.Completed,
                LocalBackupTaskStore(context).read(backup)!!.status,
            )
            val verified = LocalBackupArchive.inspect(store.archive(backup))
            assertEquals(1, verified.entries.size)
            val restore =
                controller.enqueueRestore(
                    store.archive(backup),
                    "backup.zip",
                    tree,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            assertTrue(LocalBackupTaskRunner(context, null).run(restore))
            val done = LocalBackupTaskStore(context).read(restore)!!
            assertEquals(LocalBackupTaskStatus.Completed, done.status)
            assertArrayEquals(payload, bytes(context, Uri.parse(done.outputs.single().uri)))
            val exported = bytes(context, destination)
            controller.forget(backup)
            assertNull(store.read(backup))
            assertArrayEquals(exported, bytes(context, destination))
            assertArrayEquals(payload, bytes(context, source))
        }

    @Test
    fun pauseRetainsTaskAndEmptyDestinationThenResumeCompletes() =
        fixture { context, controller, store ->
            val source = created(context, "slow.jpg", ByteArray(200000) { (it % 127).toByte() })
            val destination = created(context, "paused.zip", byteArrayOf())
            val id =
                controller.enqueueBackup(
                    listOf(source.toString()),
                    destination,
                    "paused.zip",
                    false,
                )
            command(context, "fixture-hold-source")
            val running =
                kotlinx.coroutines.coroutineScope {
                    val work = async { LocalBackupTaskRunner(context, null).run(id) }
                    await { command(context, "fixture-source-state")!!.getBoolean("opened") }
                    controller.pause(id)
                    delay(80)
                    command(context, "fixture-release-source")
                    work.await()
                }
            assertTrue(running)
            assertEquals(LocalBackupTaskStatus.Paused, store.read(id)!!.status)
            assertArrayEquals(byteArrayOf(), bytes(context, destination))
            controller.resume(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.Completed, store.read(id)!!.status)
            assertTrue(bytes(context, destination).isNotEmpty())
        }

    @Test
    fun revokedDestinationWaitsAndRetryUsesSameTaskAndArchive() =
        fixture { context, controller, store ->
            val payload = ByteArray(12345) { it.toByte() }
            val id =
                controller.enqueueRestore(
                    archive(context, payload),
                    "fixture.zip",
                    tree,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            command(context, "fixture-deny-access")
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.WaitingPermission, store.read(id)!!.status)
            val before = store.archive(id).readBytes()
            command(context, "fixture-allow-access")
            controller.resume(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.Completed, store.read(id)!!.status)
            assertArrayEquals(before, store.archive(id).readBytes())
            assertArrayEquals(
                payload,
                bytes(context, Uri.parse(store.read(id)!!.outputs.single().uri)),
            )
        }

    @Test
    fun cancellationRetainsExternallyChangedOutputInsteadOfOverwritingOrDeleting() =
        fixture { context, controller, store ->
            val source = created(context, "source.jpg", byteArrayOf(1, 2, 3))
            val destination = created(context, "changed.zip", byteArrayOf())
            val id =
                controller.enqueueBackup(
                    listOf(source.toString()),
                    destination,
                    "changed.zip",
                    false,
                )
            val foreign = byteArrayOf(88, 89, 90)
            context.contentResolver.openOutputStream(destination, "wt")!!.use { it.write(foreign) }
            controller.cancel(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.NeedsReview, store.read(id)!!.status)
            assertArrayEquals(foreign, bytes(context, destination))
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes(context, source))
        }

    @Test
    fun cancellationRemovesOnlyOwnedEmptyOutputAndCannotResurrectTerminalTask() =
        fixture { context, controller, store ->
            val source = created(context, "source.jpg", byteArrayOf(1, 2, 3))
            val destination = created(context, "cancel.zip", byteArrayOf())
            val unrelated = created(context, "keep.jpg", byteArrayOf(9))
            val id =
                controller.enqueueBackup(
                    listOf(source.toString()),
                    destination,
                    "cancel.zip",
                    false,
                )
            controller.cancel(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.Cancelled, store.read(id)!!.status)
            assertArrayEquals(byteArrayOf(9), bytes(context, unrelated))
            try {
                controller.resume(id)
                fail("Terminal task resurrected")
            } catch (_: IllegalStateException) {}
            assertEquals(LocalBackupTaskStatus.Cancelled, store.read(id)!!.status)
        }

    @Test
    fun corruptDurableSnapshotRemainsVisibleAndProtectsMatchingGalleryJournal() =
        fixture { context, controller, store ->
            val id = UUID.randomUUID().toString()
            store.create(LocalBackupTask(id, LocalBackupTaskKind.RestoreGallery, 1, "damaged"))
            val file = File(store.directory(id), "task.json")
            file.writeText("{invalid")
            val listed = store.list().single()
            assertEquals(LocalBackupTaskStatus.NeedsReview, listed.status)
            assertEquals("corrupt", listed.failure)
            assertTrue(id in store.retainedGalleryOperations())
            try {
                controller.resume(id)
                fail("Corrupt task executed")
            } catch (_: Exception) {}
            assertEquals("{invalid", file.readText())
        }

    @Test
    fun organizationReceiptPreventsRepeatedStagesAfterLostCommitResponse() =
        fixture { context, controller, store ->
            val payload = byteArrayOf(1, 2, 3, 4)
            var receipt: LocalRestoreGalleryResult? = null
            var stages = 0
            var commits = 0
            var pauses = 0
            val port =
                object : LocalBackupDurableOrganizationPort {
                    override suspend fun export(sources: List<LocalBackupSourceRef>) =
                        LocalBackupSidecar(byteArrayOf(4, 5, 6))

                    override suspend fun review(sidecar: ByteArray, manifest: BackupManifest) =
                        LocalBackupOrganizationReview(emptyList(), 1, 0, 0, 0, true)

                    override suspend fun committedResult(operationId: String) = receipt

                    override suspend fun beginRestore(
                        sidecar: ByteArray,
                        manifest: BackupManifest,
                        options: LocalRestoreOrganizationOptions,
                    ): LocalRestoreGallerySession = error("Durable operation ID required")

                    override suspend fun resumeRestore(
                        operationId: String,
                        sidecar: ByteArray,
                        manifest: BackupManifest,
                        options: LocalRestoreOrganizationOptions,
                    ) =
                        object : LocalRestoreGallerySession {
                            override suspend fun stage(
                                entry: BackupManifest.Entry,
                                input: InputStream,
                            ) {
                                assertArrayEquals(payload, input.readBytes())
                                stages++
                            }

                            override suspend fun commit(): LocalRestoreGalleryResult {
                                commits++
                                receipt = LocalRestoreGalleryResult(1, 2)
                                throw IOException("Lost commit response")
                            }

                            override suspend fun pause() {
                                pauses++
                            }

                            override suspend fun abort() {
                                error("Interruption must not cancel")
                            }
                        }
                }
            val file = File(context.directory, "organized.zip")
            LocalBackupArchive.createOrganized(
                listOf(
                    LocalBackupArchive.Source("photo.jpg", "image/jpeg") { payload.inputStream() }
                ),
                file,
                port,
            )
            val id =
                controller.enqueueRestore(
                    file,
                    "organized.zip",
                    null,
                    true,
                    LocalRestoreOrganizationOptions(),
                )
            LocalBackupTaskRunner(context, port).run(id)
            assertEquals(LocalBackupTaskStatus.Failed, store.read(id)!!.status)
            controller.resume(id)
            LocalBackupTaskRunner(context, port).run(id)
            assertEquals(LocalBackupTaskStatus.Completed, store.read(id)!!.status)
            assertEquals(1, stages)
            assertEquals(1, commits)
            assertEquals(1, pauses)
            assertEquals(2, store.read(id)!!.importedObjects)
        }

    @Test
    fun verifiedRestoredFileTruncatedToValidPrefixRequiresReviewOnResumeAndCancel() =
        fixture { context, controller, store ->
            val payload = ByteArray(1000) { it.toByte() }
            val id =
                controller.enqueueRestore(
                    archive(context, payload),
                    "fixture.zip",
                    tree,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            LocalBackupTaskRunner(context, null).run(id)
            val completed = store.read(id)!!
            assertTrue(completed.outputs.single().verified)
            val destination = Uri.parse(completed.outputs.single().uri)
            // Simulate the durable entry checkpoint before the enclosing task reached terminal
            // state.
            store.update(id) {
                it.copy(
                    status = LocalBackupTaskStatus.Paused,
                    phase = LocalBackupTaskPhase.Restoring,
                    pauseRequested = true,
                )
            }
            val changed = payload.copyOf(10)
            context.contentResolver.openOutputStream(destination, "wt")!!.use { it.write(changed) }
            controller.resume(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.NeedsReview, store.read(id)!!.status)
            assertArrayEquals(changed, bytes(context, destination))
            controller.cancel(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.NeedsReview, store.read(id)!!.status)
            assertArrayEquals(changed, bytes(context, destination))
        }

    @Test
    fun sessionArchiveOwnershipIsAtomicAndSurvivesSessionCleanupBeforeWorkerStarts() =
        fixture { context, controller, store ->
            val storage = LocalBackupStorage(context)
            val staged = storage.newArchive()
            val payload = byteArrayOf(1, 2, 3, 4)
            val manifest =
                LocalBackupArchive.create(
                    listOf(
                        LocalBackupArchive.Source("photo.jpg", "image/jpeg") {
                            payload.inputStream()
                        }
                    ),
                    staged,
                )
            val id =
                controller.enqueueRestore(
                    staged,
                    "owned.zip",
                    tree,
                    false,
                    LocalRestoreOrganizationOptions(),
                    manifest,
                )
            assertEquals(LocalBackupTaskPhase.Preparing, store.read(id)!!.phase)
            if (staged.exists())
                assertTrue(
                    java.nio.file.Files.isSameFile(staged.toPath(), store.archive(id).toPath())
                )
            // Android may deny hard links; approved atomic rename consumes only owned staging.
            // Either route must preserve exactly the reviewed archive, not just a destination name.
            assertEquals(manifest, LocalBackupArchive.inspect(store.archive(id)))
            storage.close()
            assertFalse(staged.exists())
            assertTrue(store.archive(id).exists())
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.Completed, store.read(id)!!.status)
            assertArrayEquals(
                payload,
                bytes(context, Uri.parse(store.read(id)!!.outputs.single().uri)),
            )
        }

    @Test
    fun preparationRecordPrecedesPrivateCallerCopyAndCancelDoesNotMoveCallerFile() =
        fixture { context, controller, store ->
            val source = archive(context, byteArrayOf(1, 2, 3))
            val original = source.readBytes()
            val id =
                controller.enqueueRestore(
                    source,
                    "private.zip",
                    tree,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            assertEquals(LocalBackupTaskPhase.Preparing, store.read(id)!!.phase)
            assertEquals(source.canonicalPath, store.read(id)!!.snapshotSource)
            assertFalse(store.archive(id).exists())
            assertArrayEquals(original, source.readBytes())
            controller.cancel(id)
            LocalBackupTaskRunner(context, null).run(id)
            assertEquals(LocalBackupTaskStatus.Cancelled, store.read(id)!!.status)
            assertArrayEquals(original, source.readBytes())
        }
}
