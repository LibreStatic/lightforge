package com.librestatic.lightforge.feature.settings

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteRestoreTaskBridgeDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun exercise(
        block: suspend (String, File, BackupManifest, Uri, MutableList<String>) -> Unit
    ) = runBlocking {
        val request = UUID.randomUUID().toString()
        val archive = File(context.filesDir, "remote-bridge-test-$request.zip")
        val manifest =
            LocalBackupArchive.create(
                listOf(
                    LocalBackupArchive.Source("sample.jpg", "image/jpeg") {
                        byteArrayOf(1, 2, 3).inputStream()
                    }
                ),
                archive,
            )
        val target = Uri.parse("content://com.librestatic.lightforge.bridge.fixture/tree/$request")
        val scheduled = mutableListOf<String>()
        try {
            block(request, archive, manifest, target, scheduled)
        } finally {
            val id =
                UUID.nameUUIDFromBytes(("remote-restore-v1:" + request).toByteArray()).toString()
            val store = LocalBackupTaskStore(context)
            store.read(id)?.let {
                store.update(id) { it.copy(status = LocalBackupTaskStatus.Cancelled) }
                LocalBackupTaskGrants(context).release(id)
                store.forget(id)
            }
            File(context.filesDir, "remote-restore-receipts/$request.json").delete()
            File(context.filesDir, "remote-restore-receipts/$request.lock").delete()
            archive.delete()
        }
    }

    @Test
    fun repeatedHandoffAcrossInstancesReturnsExactlyOneChild() =
        exercise { request, archive, manifest, target, scheduled ->
            val a =
                RemoteRestoreTaskBridge(context) { scheduled += it }
                    .enqueueOnce(
                        request,
                        archive,
                        manifest,
                        target,
                        false,
                        LocalRestoreOrganizationOptions(),
                    )
            val b =
                RemoteRestoreTaskBridge(context) { scheduled += it }
                    .enqueueOnce(
                        request,
                        archive,
                        manifest,
                        target,
                        false,
                        LocalRestoreOrganizationOptions(),
                    )
            assertEquals(a, b)
            assertEquals(
                a,
                RemoteRestoreTaskBridge(context) { error("Lookup must not schedule") }
                    .existing(request),
            )
            assertEquals(setOf(a), scheduled.toSet())
            assertEquals(1, LocalBackupTaskStore(context).list().count { it.id == a })
        }

    @Test
    fun deathAfterChildCreationBeforeParentReceiptReusesChild() =
        exercise { request, archive, manifest, target, scheduled ->
            val bridge = RemoteRestoreTaskBridge(context) { scheduled += it }
            val a =
                bridge.enqueueOnce(
                    request,
                    archive,
                    manifest,
                    target,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            assertTrue(File(context.filesDir, "remote-restore-receipts/$request.json").delete())
            assertEquals(
                a,
                RemoteRestoreTaskBridge(context) { error("Lookup must not schedule") }
                    .existing(request),
            )
            val b =
                RemoteRestoreTaskBridge(context) { scheduled += it }
                    .enqueueOnce(
                        request,
                        archive,
                        manifest,
                        target,
                        false,
                        LocalRestoreOrganizationOptions(),
                    )
            assertEquals(a, b)
            assertEquals(
                a,
                RemoteRestoreTaskBridge(context) { error("Lookup must not schedule") }
                    .existing(request),
            )
            assertEquals(setOf(a), scheduled.toSet())
        }

    @Test
    fun changingDestinationOrConsentDoesNotReuseAnExistingRequest() =
        exercise { request, archive, manifest, target, scheduled ->
            val bridge = RemoteRestoreTaskBridge(context) { scheduled += it }
            bridge.enqueueOnce(
                request,
                archive,
                manifest,
                target,
                false,
                LocalRestoreOrganizationOptions(),
            )
            assertTrue(
                runCatching {
                        bridge.enqueueOnce(
                            request,
                            archive,
                            manifest,
                            Uri.parse("content://other/tree/changed"),
                            false,
                            LocalRestoreOrganizationOptions(),
                        )
                    }
                    .isFailure
            )
            assertTrue(
                runCatching {
                        bridge.enqueueOnce(
                            request,
                            archive,
                            manifest,
                            target,
                            false,
                            LocalRestoreOrganizationOptions(importGlobalRules = true),
                        )
                    }
                    .isFailure
            )
            assertEquals(1, scheduled.size)
        }

    @Test
    fun forgottenCompletedChildIsNeverCreatedAgain() =
        exercise { request, archive, manifest, target, scheduled ->
            val bridge = RemoteRestoreTaskBridge(context) { scheduled += it }
            val a =
                bridge.enqueueOnce(
                    request,
                    archive,
                    manifest,
                    target,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            val store = LocalBackupTaskStore(context)
            store.update(a) { it.copy(status = LocalBackupTaskStatus.Completed) }
            LocalBackupTaskGrants(context).release(a)
            store.forget(a)
            val b =
                bridge.enqueueOnce(
                    request,
                    archive,
                    manifest,
                    target,
                    false,
                    LocalRestoreOrganizationOptions(),
                )
            assertEquals(a, b)
            assertEquals(
                a,
                RemoteRestoreTaskBridge(context) { error("Lookup must not schedule") }
                    .existing(request),
            )
            assertNull(store.read(a))
            assertEquals(1, scheduled.size)
        }
}
