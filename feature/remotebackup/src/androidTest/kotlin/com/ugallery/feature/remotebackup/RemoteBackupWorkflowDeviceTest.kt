package com.ugallery.feature.remotebackup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.remotestorage.*
import com.ugallery.feature.settings.*
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Local deterministic protocol doubles, not substitutes for the separate real SSH/SMB server tests.
 */
class RemoteBackupWorkflowDeviceTest {
    private class TestContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "remote-flow-${UUID.randomUUID()}").apply { mkdirs() }

        override fun getFilesDir() = root

        override fun getApplicationContext(): Context = this
    }

    private class Vault : RemoteCredentialVault {
        private val values = mutableMapOf<String, CharArray>()

        override fun save(profileId: String, credentials: RemoteCredentials) {
            values[profileId] = (credentials as RemoteCredentials.Password).value.copyOf()
        }

        override fun load(profileId: String): RemoteCredentials? =
            values[profileId]?.let { RemoteCredentials.Password(it.copyOf()) }

        override fun delete(profileId: String) {
            values.remove(profileId)?.fill('\u0000')
        }
    }

    private class FakeServer(val directory: File) : RemoteConnectionFactory {
        var capabilities = RemoteCapabilities(true, true, true)
        var rejectAuthentication = false
        var changedKey: String? = null
        var hold = false
        var blocked = CountDownLatch(1)
        var gate = CountDownLatch(1)
        var creates = 0

        override fun connect(
            profile: RemoteProfile,
            credentials: RemoteCredentials,
            cancellation: RemoteCancellation,
        ): RemoteConnection {
            cancellation.check()
            if (rejectAuthentication) throw RemoteStorageException(RemoteFailure.AUTHENTICATION)
            changedKey?.let {
                if (profile.trustedHostKey != it)
                    throw RemoteStorageException(RemoteFailure.IDENTITY_CHANGED, it)
            }
            return object : RemoteConnection {
                init {
                    if (hold) cancellation.register(Closeable { gate.countDown() })
                }

                override val capabilities
                    get() = this@FakeServer.capabilities

                override val identity = "fixture-verified"

                private fun file(name: String) = File(directory, RemoteNames.requireChild(name))

                override fun list(limit: Int): List<RemoteEntry> {
                    val all = directory.listFiles().orEmpty().toList()
                    require(all.size <= limit)
                    return all.map {
                        RemoteEntry(it.name, it.length(), it.lastModified(), it.isFile)
                    }
                }

                override fun stat(name: String) =
                    file(name)
                        .takeIf { it.exists() }
                        ?.let { RemoteEntry(it.name, it.length(), it.lastModified(), it.isFile) }

                override fun openRead(name: String, offset: Long): InputStream =
                    file(name).inputStream().apply { require(skip(offset) == offset) }

                override fun createExclusive(name: String): OutputStream {
                    cancellation.check()
                    creates++
                    val target = file(name)
                    if (!target.createNewFile())
                        throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
                    return object : FilterOutputStream(target.outputStream()) {
                        private var count = 0L

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            cancellation.check()
                            out.write(bytes, offset, length)
                            count += length
                            if (hold && count >= 65536) {
                                blocked.countDown()
                                gate.await(15, TimeUnit.SECONDS)
                                cancellation.check()
                            }
                        }
                    }
                }

                override fun publishNoReplace(
                    staging: String,
                    destination: String,
                    expected: RemoteDigest,
                ) {
                    cancellation.check()
                    assertEquals(
                        expected,
                        RemoteBackupIO.digest(file(staging)) { cancellation.check() },
                    )
                    if (!file(destination).createNewFile())
                        throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
                    file(staging).inputStream().use { input ->
                        file(destination).outputStream().use { input.copyTo(it) }
                    }
                    assertEquals(
                        expected,
                        RemoteBackupIO.digest(file(destination)) { cancellation.check() },
                    )
                }

                override fun close() {}
            }
        }
    }

    private suspend fun fixture(
        block:
            suspend (
                TestContext,
                RemoteBackupController,
                RemoteBackupStore,
                FakeServer,
                RemoteBackupServices,
                Uri,
            ) -> Unit
    ) {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        val context = TestContext(actual)
        val sourceId = UUID.randomUUID().toString()
        val source = File(actual.cacheDir, "remote-source-$sourceId")
        source.writeBytes(ByteArray(2 * 1024 * 1024).also { java.util.Random(54).nextBytes(it) })
        val uri = Uri.parse("content://com.ugallery.feature.remotebackup.test.sources/$sourceId")
        val server = FakeServer(File(context.root, "server").apply { mkdirs() })
        val services =
            RemoteBackupServices(
                server,
                Vault(),
                object : LocalBackupOrganizationPort {
                    override suspend fun export(sources: List<LocalBackupSourceRef>) =
                        LocalBackupSidecar("fixture-sidecar".toByteArray())

                    override suspend fun review(sidecar: ByteArray, manifest: BackupManifest) =
                        LocalBackupOrganizationReview(
                            emptyList(),
                            manifest.entries.size,
                            0,
                            0,
                            0,
                            true,
                        )

                    override suspend fun beginRestore(
                        sidecar: ByteArray,
                        manifest: BackupManifest,
                        options: LocalRestoreOrganizationOptions,
                    ): LocalRestoreGallerySession = error("Application bridge is tested separately")
                },
                RemoteRestoreBridge { request, _, _, _, _, _ -> "local-$request" },
                { true },
            )
        val controller = RemoteBackupController(context, services) {}
        try {
            controller.saveProfile(
                RemoteProfile(
                    id = ProfileId,
                    name = "fixture",
                    protocol = RemoteProtocol.SFTP,
                    host = "fixture.invalid",
                    username = "fixture",
                    root = "/owned",
                ),
                RemoteCredentials.Password("fixture-secret".toCharArray()),
            )
            block(context, controller, RemoteBackupStore(context), server, services, uri)
        } finally {
            controller.close()
            context.root.deleteRecursively()
            source.delete()
        }
    }

    private suspend fun prepared(
        context: Context,
        controller: RemoteBackupController,
        store: RemoteBackupStore,
        services: RemoteBackupServices,
        uri: Uri,
        organization: Boolean = false,
    ): String {
        val id = controller.enqueueBackup(ProfileId, listOf(uri.toString()), organization)
        assertTrue(RemoteBackupRunner(context, services).run(id))
        assertEquals(RemoteBackupStatus.AwaitingUploadReview, store.get(id)!!.status)
        return id
    }

    @Test
    fun completeArchiveUploadListDownloadReviewAndIdempotentLocalHandoff() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri, true)
                assertEquals(0, server.creates)
                assertEquals(2, controller.review(id).manifest.version)
                controller.confirmUpload(id)
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.Completed, store.get(id)!!.status)
                val remote = controller.listArchives(ProfileId).single()
                assertArrayEquals(
                    store.archive(id).readBytes(),
                    File(server.directory, remote.name).readBytes(),
                )
                val download = controller.enqueueDownload(ProfileId, remote)
                RemoteBackupRunner(context, services).run(download)
                assertEquals(RemoteBackupStatus.AwaitingRestoreReview, store.get(download)!!.status)
                assertArrayEquals(
                    store.archive(id).readBytes(),
                    controller.review(download).archive.readBytes(),
                )
                controller.restoreReviewed(download, true, null, LocalRestoreOrganizationOptions())
                RemoteBackupRunner(context, services).run(download)
                assertEquals("local-$download", store.get(download)!!.localTaskId)
                assertEquals(RemoteBackupStatus.RestoringLocally, store.get(download)!!.status)
                assertTrue(store.archive(download).exists())
            }
        }

    @Test
    fun unsupportedPublicationPreflightDoesNotReadSourcesOrCreateRemoteObjects() =
        runBlocking<Unit> {
            fixture { _, controller, store, server, _, uri ->
                server.capabilities = RemoteCapabilities(false, true, true)
                try {
                    controller.enqueueBackup(ProfileId, listOf(uri.toString()), false)
                    fail()
                } catch (e: RemoteStorageException) {
                    assertEquals(RemoteFailure.UNSUPPORTED, e.failure)
                }
                assertTrue(store.list().isEmpty())
                assertEquals(0, server.creates)
            }
        }

    @Test
    fun pausedUploadClosesSocketAndResumesWithNewPartialWithoutDeletingPrevious() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                controller.confirmUpload(id)
                server.hold = true
                val running =
                    async(Dispatchers.IO) { RemoteBackupRunner(context, services).run(id) }
                assertTrue(
                    withContext(Dispatchers.IO) { server.blocked.await(10, TimeUnit.SECONDS) }
                )
                controller.pause(id)
                withTimeout(10000) { running.await() }
                assertEquals(RemoteBackupStatus.Paused, store.get(id)!!.status)
                val old = store.get(id)!!.staging!!
                assertTrue(File(server.directory, old).length() > 0)
                server.hold = false
                controller.resume(id)
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.Completed, store.get(id)!!.status)
                assertNotEquals(old, store.get(id)!!.staging)
                assertTrue(File(server.directory, old).exists())
            }
        }

    @Test
    fun rejectedCredentialsAreVisibleAndCanBeReplacedWithoutRedirectingTask() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                controller.confirmUpload(id)
                server.rejectAuthentication = true
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.WaitingCredentials, store.get(id)!!.status)
                controller.updateCredentials(
                    ProfileId,
                    RemoteCredentials.Password("replacement-fixture".toCharArray()),
                )
                server.rejectAuthentication = false
                controller.resume(id)
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.Completed, store.get(id)!!.status)
                assertEquals("fixture.invalid", store.get(id)!!.profile.host)
            }
        }

    @Test
    fun changedHostKeyRequiresExplicitTaskTrustBeforeAnyNewWrite() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                controller.confirmUpload(id)
                val key = "SHA256:" + "A".repeat(43)
                server.changedKey = key
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.WaitingIdentity, store.get(id)!!.status)
                assertEquals(0, server.creates)
                controller.resume(id)
                assertEquals(RemoteBackupStatus.WaitingIdentity, store.get(id)!!.status)
                controller.trustTask(id, key)
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.Completed, store.get(id)!!.status)
            }
        }

    @Test
    fun existingDestinationAndCancelledTaskNeverOverwriteOrResurrect() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                val foreign =
                    File(server.directory, store.get(id)!!.name).apply {
                        writeText("existing user bytes")
                    }
                controller.confirmUpload(id)
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.NeedsReview, store.get(id)!!.status)
                assertEquals(0, server.creates)
                controller.cancel(id)
                RemoteBackupRunner(context, services).run(id)
                assertEquals(RemoteBackupStatus.Cancelled, store.get(id)!!.status)
                controller.resume(id)
                assertEquals(RemoteBackupStatus.Cancelled, store.get(id)!!.status)
                assertEquals("existing user bytes", foreign.readText())
            }
        }

    @Test
    fun changedPartialDownloadIsRetainedForReviewNotSilentlyReplaced() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                val archive =
                    File(server.directory, "remote.ugallery.zip").apply {
                        writeBytes(store.archive(id).readBytes())
                    }
                val download =
                    controller.enqueueDownload(
                        ProfileId,
                        RemoteEntry(archive.name, archive.length(), archive.lastModified(), true),
                    )
                store.partial(download).writeBytes(byteArrayOf(9, 8, 7))
                RemoteBackupRunner(context, services).run(download)
                assertEquals(RemoteBackupStatus.NeedsReview, store.get(download)!!.status)
                assertArrayEquals(byteArrayOf(9, 8, 7), store.partial(download).readBytes())
            }
        }

    @Test
    fun validDownloadPrefixAndReopenedControllerContinueDurableTask() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                val archive =
                    File(server.directory, "remote.ugallery.zip").apply {
                        writeBytes(store.archive(id).readBytes())
                    }
                val download =
                    controller.enqueueDownload(
                        ProfileId,
                        RemoteEntry(archive.name, archive.length(), archive.lastModified(), true),
                    )
                store.partial(download).writeBytes(archive.readBytes().copyOfRange(0, 65536))
                val scheduled = mutableListOf<String>()
                val reopened = RemoteBackupController(context, services) { scheduled += it }
                try {
                    reopened.reconcile()
                    assertTrue(download in scheduled)
                    RemoteBackupRunner(context, services).run(download)
                    assertEquals(
                        RemoteBackupStatus.AwaitingRestoreReview,
                        store.get(download)!!.status,
                    )
                    assertArrayEquals(archive.readBytes(), store.archive(download).readBytes())
                } finally {
                    reopened.close()
                }
            }
        }

    @Test
    fun corruptJournalRemainsVisibleAndEndpointMutationIsRejected() =
        runBlocking<Unit> {
            fixture { context, controller, store, _, services, uri ->
                val id = prepared(context, controller, store, services, uri)
                assertThrows(IllegalArgumentException::class.java) {
                    store.update(id) { it.copy(profile = it.profile.copy(host = "other.invalid")) }
                }
                File(store.directory(id), "task.json").writeText("broken")
                assertEquals(RemoteBackupStatus.NeedsReview, store.list().single().status)
                assertEquals("corrupt", store.list().single().failure)
                val scheduled = mutableListOf<String>()
                val reopened = RemoteBackupController(context, services) { scheduled += it }
                try {
                    reopened.reconcile()
                    assertTrue(scheduled.isEmpty())
                } finally {
                    reopened.close()
                }
            }
        }

    @Test
    fun lostRestoreResponseUsesStableRequestAndNeverDuplicatesTheLocalChild() =
        runBlocking<Unit> {
            fixture { context, controller, store, server, services, uri ->
                val id = prepared(context, controller, store, services, uri, true)
                val archive =
                    File(server.directory, "remote.ugallery.zip").apply {
                        writeBytes(store.archive(id).readBytes())
                    }
                val download =
                    controller.enqueueDownload(
                        ProfileId,
                        RemoteEntry(archive.name, archive.length(), archive.lastModified(), true),
                    )
                RemoteBackupRunner(context, services).run(download)
                val receipts = mutableMapOf<String, String>()
                var first = true
                val bridge = RemoteRestoreBridge { request, _, _, _, _, _ ->
                    val child = receipts.getOrPut(request) { UUID.randomUUID().toString() }
                    if (first) {
                        first = false
                        throw IOException("Fixture response loss")
                    }
                    child
                }
                val linkedServices =
                    RemoteBackupServices(server, services.credentials, null, bridge, { true })
                controller.restoreReviewed(download, true, null, LocalRestoreOrganizationOptions())
                RemoteBackupRunner(context, linkedServices).run(download)
                assertEquals(RemoteBackupStatus.Failed, store.get(download)!!.status)
                controller.resume(download)
                RemoteBackupRunner(context, linkedServices).run(download)
                assertEquals(1, receipts.size)
                assertEquals(receipts[download], store.get(download)!!.localTaskId)
            }
        }

    companion object {
        private const val ProfileId = "34bb9080-418c-4e63-aa0c-ce20f854c565"
    }
}
