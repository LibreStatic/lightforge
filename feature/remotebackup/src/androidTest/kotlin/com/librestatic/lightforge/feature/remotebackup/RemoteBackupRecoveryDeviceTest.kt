package com.librestatic.lightforge.feature.remotebackup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.remotestorage.*
import com.librestatic.lightforge.feature.settings.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteBackupRecoveryDeviceTest {
    private class OwnedContext(base: Context) : ContextWrapper(base) {
        val directory =
            File(base.cacheDir, "remote-recovery-${UUID.randomUUID()}").apply { mkdirs() }

        override fun getFilesDir() = directory

        override fun getApplicationContext(): Context = this
    }

    private fun profile() =
        RemoteProfile(
            name = "fixture",
            protocol = RemoteProtocol.SFTP,
            host = "fixture.invalid",
            username = "user",
            root = "/owned",
        )

    private fun services(bridge: RemoteRestoreBridge) =
        RemoteBackupServices(
            RemoteConnectionFactory { _, _, _ -> error("No network") },
            object : RemoteCredentialVault {
                override fun save(profileId: String, credentials: RemoteCredentials) =
                    error("No secret write")

                override fun load(profileId: String): RemoteCredentials? = null

                override fun delete(profileId: String) = error("No secret deletion")
            },
            null,
            bridge,
            { true },
        )

    @Test
    fun cancellationRecoversExistingLocalReceiptInsteadOfHidingRunningChild() =
        runBlocking<Unit> {
            val context = OwnedContext(InstrumentationRegistry.getInstrumentation().targetContext)
            val store = RemoteBackupStore(context)
            val id = UUID.randomUUID().toString()
            val child = UUID.randomUUID().toString()
            val bridge =
                object : RemoteRestoreBridge {
                    override suspend fun existing(requestId: String): String {
                        assertEquals(id, requestId)
                        return child
                    }

                    override suspend fun enqueueOnce(
                        requestId: String,
                        archive: File,
                        manifest: BackupManifest,
                        destination: Uri?,
                        gallery: Boolean,
                        options: LocalRestoreOrganizationOptions,
                    ): String = error("Cancellation must never create a child")
                }
            val dependencies = services(bridge)
            val controller = RemoteBackupController(context, dependencies) {}
            try {
                store.create(
                    RemoteBackupTask(
                        id,
                        profile(),
                        RemoteBackupDirection.Download,
                        1,
                        status = RemoteBackupStatus.Failed,
                        phase = RemoteBackupPhase.RestoreHandoff,
                        name = "fixture.lightforge.zip",
                    )
                )
                controller.cancel(id)
                RemoteBackupRunner(context, dependencies).run(id)
                assertEquals(RemoteBackupStatus.RestoringLocally, store.get(id)!!.status)
                assertEquals(child, store.get(id)!!.localTaskId)
            } finally {
                controller.close()
                context.directory.deleteRecursively()
            }
        }

    @Test
    fun ungrantedFolderIsDurablyVisibleBeforeWorkerAndCannotBeRedirected() =
        runBlocking<Unit> {
            val context = OwnedContext(InstrumentationRegistry.getInstrumentation().targetContext)
            val store = RemoteBackupStore(context)
            val id = UUID.randomUUID().toString()
            val dependencies =
                services(
                    RemoteRestoreBridge { _, _, _, _, _, _ ->
                        error("Unconfirmed permission must not create child")
                    }
                )
            val scheduled = mutableListOf<String>()
            val controller = RemoteBackupController(context, dependencies) { scheduled += it }
            try {
                val archive = store.archive(id)
                val manifest =
                    LocalBackupArchive.create(
                        listOf(
                            LocalBackupArchive.Source("original.pdf", "application/pdf") {
                                "%PDF fixture".byteInputStream()
                            }
                        ),
                        archive,
                    )
                val digest = RemoteBackupIO.digest(archive) {}
                store.create(
                    RemoteBackupTask(
                        id,
                        profile(),
                        RemoteBackupDirection.Download,
                        1,
                        status = RemoteBackupStatus.AwaitingRestoreReview,
                        phase = RemoteBackupPhase.Verify,
                        name = "fixture.lightforge.zip",
                        totalBytes = digest.size,
                        archiveSha = digest.sha256,
                        files = manifest.entries.size,
                    )
                )
                val destination = Uri.parse("content://ungranted.remote.fixture/tree/owned")
                try {
                    controller.restoreReviewed(
                        id,
                        false,
                        destination,
                        LocalRestoreOrganizationOptions(),
                    )
                    fail()
                } catch (_: SecurityException) {}
                assertEquals(RemoteBackupStatus.WaitingPermission, store.get(id)!!.status)
                assertEquals(destination.toString(), store.get(id)!!.restoreDestination)
                assertTrue(scheduled.isEmpty())
                try {
                    controller.regrantDestination(
                        id,
                        Uri.parse("content://ungranted.remote.fixture/tree/another"),
                    )
                    fail()
                } catch (_: IllegalArgumentException) {}
                assertEquals(destination.toString(), store.get(id)!!.restoreDestination)
                assertTrue(archive.exists())
            } finally {
                controller.close()
                context.directory.deleteRecursively()
            }
        }

    @Test
    fun atomicBackupProfilesAndProbeResidualsRemainVisibleAfterInterruptedReplacement() {
        val context = OwnedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val store = RemoteBackupStore(context)
            val profile = profile()
            store.saveProfile(profile)
            store.saveProbe(
                RemoteProfileProbe(profile.id, residuals = listOf("probe-retained.partial"))
            )
            val root = File(context.filesDir, "remote-backup")
            for (name in listOf("profiles.json", "probes.json")) assertTrue(
                File(root, name).renameTo(File(root, "$name.bak"))
            )
            val reopened = RemoteBackupStore(context)
            assertEquals(profile, reopened.profiles().single())
            assertEquals(listOf("probe-retained.partial"), reopened.probes().single().residuals)
        } finally {
            context.directory.deleteRecursively()
        }
    }

    @Test
    fun sourceRegrantRejectsDifferentUrisBeforeAnyPermissionOrTaskChange() =
        runBlocking<Unit> {
            val context = OwnedContext(InstrumentationRegistry.getInstrumentation().targetContext)
            val store = RemoteBackupStore(context)
            val dependencies =
                services(RemoteRestoreBridge { _, _, _, _, _, _ -> error("No handoff") })
            val controller = RemoteBackupController(context, dependencies) {}
            val id = UUID.randomUUID().toString()
            try {
                store.create(
                    RemoteBackupTask(
                        id,
                        profile(),
                        RemoteBackupDirection.Upload,
                        1,
                        status = RemoteBackupStatus.WaitingPermission,
                        sources = listOf("content://fixture/original"),
                        name = "fixture.lightforge.zip",
                    )
                )
                try {
                    controller.regrantSources(id, listOf(Uri.parse("content://fixture/other")))
                    fail()
                } catch (_: IllegalArgumentException) {}
                assertEquals(RemoteBackupStatus.WaitingPermission, store.get(id)!!.status)
                assertEquals(listOf("content://fixture/original"), store.get(id)!!.sources)
            } finally {
                controller.close()
                context.directory.deleteRecursively()
            }
        }
}
