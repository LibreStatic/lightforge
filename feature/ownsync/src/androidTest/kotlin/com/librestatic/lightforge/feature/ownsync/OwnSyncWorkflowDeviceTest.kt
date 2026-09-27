package com.librestatic.lightforge.feature.ownsync

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.remotestorage.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnSyncWorkflowDeviceTest {
    private lateinit var context: OwnSyncFixtureContext
    private lateinit var source: OwnSyncFixtureSource
    private lateinit var remote: OwnSyncFixtureRemote
    private lateinit var services: OwnSyncServices
    private lateinit var controller: OwnSyncController
    private lateinit var store: OwnSyncStore

    @Before
    fun setup() {
        context = OwnSyncFixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        source = OwnSyncFixtureSource()
        remote = OwnSyncFixtureRemote()
        services = ownSyncFixtureServices(source, remote)
        controller = OwnSyncController(context, services, {})
        store = OwnSyncStore(context)
    }

    @After
    fun cleanup() {
        controller.close()
        context.clean()
    }

    private suspend fun preview(policy: OwnSyncPolicy = OwnSyncPolicy.Additive): String {
        val id =
            controller.create(
                "Fixture",
                Uri.parse(OwnSyncFixtureSource.Tree),
                services.profiles().single().id,
                policy,
            )
        assertTrue(OwnSyncRunner(context, services).run(id))
        assertEquals(OwnSyncStatus.AwaitingReview, store.run(id)!!.status)
        return id
    }

    private suspend fun finish(id: String, mirror: Boolean = false) {
        controller.confirm(id, mirrorConfirmed = mirror)
        assertTrue(OwnSyncRunner(context, services).run(id))
        assertEquals(OwnSyncStatus.Completed, store.run(id)!!.status)
    }

    @Test
    fun nestedAdditiveSecondRunPerformsZeroWrites() =
        runBlocking<Unit> {
            val id = preview()
            finish(id)
            val job = store.job(store.run(id)!!.jobId)!!
            assertEquals(2, remote.writes)
            assertTrue(remote.files.containsKey("${job.namespace}/nested/same.png"))
            assertTrue(remote.files.containsKey("${job.namespace}/another/same.png"))
            val second = controller.rerun(job.id)
            OwnSyncRunner(context, services).run(second)
            assertEquals(2, store.run(second)!!.plan.count { it.action == OwnSyncAction.Verified })
            finish(second)
            assertEquals(2, remote.writes)
        }

    @Test
    fun newVersionAndForeignCollisionPreserveBoth() =
        runBlocking<Unit> {
            val id = preview()
            finish(id)
            val job = store.job(store.run(id)!!.jobId)!!
            val old = remote.files["${job.namespace}/nested/same.png"]!!.copyOf()
            source.files[listOf("nested", "same.png")] = byteArrayOf(7, 8, 9)
            val second = controller.rerun(job.id)
            OwnSyncRunner(context, services).run(second)
            assertTrue(store.run(second)!!.plan.any { it.action == OwnSyncAction.Conflict })
            finish(second)
            assertArrayEquals(old, remote.files["${job.namespace}/nested/same.png"])
            assertEquals(3, store.job(job.id)!!.outputs.size)
        }

    @Test
    fun mirrorRequiresReviewThenQuarantineCanBeRestored() =
        runBlocking<Unit> {
            val id = preview(OwnSyncPolicy.ManagedMirror)
            finish(id)
            val job = store.job(store.run(id)!!.jobId)!!
            source.files.remove(listOf("nested", "same.png"))
            remote.files["${job.namespace}/foreign.txt"] = byteArrayOf(99)
            val second = controller.rerun(job.id)
            OwnSyncRunner(context, services).run(second)
            assertEquals(
                1,
                store.run(second)!!.plan.count { it.action == OwnSyncAction.Quarantine },
            )
            assertTrue(runCatching { controller.confirm(second) }.isFailure)
            finish(second, true)
            assertEquals(1, remote.moves)
            assertArrayEquals(byteArrayOf(99), remote.files["${job.namespace}/foreign.txt"])
            val output = store.job(job.id)!!.outputs.single { it.quarantine != null }
            val restore = controller.restoreQuarantine(job.id, output.id)
            assertTrue(OwnSyncRunner(context, services).run(restore))
            assertArrayEquals(byteArrayOf(1, 2, 3), remote.files[output.path.joinToString("/")])
            assertNull(store.job(job.id)!!.outputs.single { it.id == output.id }.quarantine)
        }

    @Test
    fun incompleteSourceNeverCreatesRetirements() =
        runBlocking<Unit> {
            val id = preview(OwnSyncPolicy.ManagedMirror)
            finish(id)
            val jobId = store.run(id)!!.jobId
            source.files.clear()
            source.incomplete = true
            val second = controller.rerun(jobId)
            OwnSyncRunner(context, services).run(second)
            assertTrue(store.run(second)!!.plan.none { it.action == OwnSyncAction.Quarantine })
            assertTrue(runCatching { controller.confirm(second) }.isFailure)
            controller.confirm(second, allowPartial = true)
            assertTrue(OwnSyncRunner(context, services).run(second))
            assertEquals(0, remote.moves)
        }

    @Test
    fun externalDestinationModificationIsNeverQuarantinedOrOverwritten() =
        runBlocking<Unit> {
            val id = preview(OwnSyncPolicy.ManagedMirror)
            finish(id)
            val job = store.job(store.run(id)!!.jobId)!!
            val path = "${job.namespace}/nested/same.png"
            remote.files[path] = byteArrayOf(77)
            source.files.remove(listOf("nested", "same.png"))
            val second = controller.rerun(job.id)
            OwnSyncRunner(context, services).run(second)
            assertTrue(store.run(second)!!.plan.none { it.action == OwnSyncAction.Quarantine })
            finish(second)
            assertArrayEquals(byteArrayOf(77), remote.files[path])
            assertEquals(0, remote.moves)
        }

    @Test
    fun sourceChangesAfterReviewRequireNewReviewWithoutWrites() =
        runBlocking<Unit> {
            val id = preview()
            source.files[listOf("nested", "same.png")] = byteArrayOf(88)
            controller.confirm(id)
            assertFalse(OwnSyncRunner(context, services).run(id))
            assertEquals("changed", store.run(id)!!.failure)
            assertEquals(0, remote.writes)
        }

    @Test
    fun pausedEntryRestartsButCompletedEntriesAreReusedAfterControllerReopen() =
        runBlocking<Unit> {
            source.files[listOf("another", "same.png")] = ByteArray(512 * 1024) { 9 }
            val id = preview()
            controller.confirm(id)
            var triggered = false
            remote.onWrite = {
                if (remote.writes == 2 && !triggered) {
                    triggered = true
                    store.update(id) { it.copy(pauseRequested = true) }
                }
            }
            OwnSyncRunner(context, services).run(id)
            assertEquals(OwnSyncStatus.Paused, store.run(id)!!.status)
            val old =
                remote.files.filterKeys { it.contains(".partial") }.mapValues { it.value.copyOf() }
            assertEquals(1, store.job(store.run(id)!!.jobId)!!.outputs.size)
            remote.onWrite = null
            controller.close()
            controller = OwnSyncController(context, services, {})
            controller.resume(id)
            assertTrue(OwnSyncRunner(context, services).run(id))
            assertEquals(3, remote.writes)
            old.forEach { (key, bytes) -> assertArrayEquals(bytes, remote.files[key]) }
            assertEquals(2, store.job(store.run(id)!!.jobId)!!.outputs.size)
        }

    @Test
    fun revokedSourceRegrantMustMatchOriginalTree() =
        runBlocking<Unit> {
            val id = preview()
            controller.confirm(id)
            source.revoked = true
            OwnSyncRunner(context, services).run(id)
            assertEquals(OwnSyncStatus.WaitingPermission, store.run(id)!!.status)
            assertTrue(
                runCatching { controller.regrant(id, Uri.parse("content://fixture/tree/other")) }
                    .isFailure
            )
            controller.regrant(id, Uri.parse(OwnSyncFixtureSource.Tree))
            assertTrue(OwnSyncRunner(context, services).run(id))
        }

    @Test
    fun cancelFromNeedsReviewCompletesWithoutChangingRemoteBytes() =
        runBlocking<Unit> {
            val id = preview()
            store.update(id) { it.copy(status = OwnSyncStatus.NeedsReview, failure = "io") }
            remote.files["foreign"] = byteArrayOf(44)
            controller.cancel(id)
            assertTrue(OwnSyncRunner(context, services).run(id))
            assertEquals(OwnSyncStatus.Cancelled, store.run(id)!!.status)
            assertArrayEquals(byteArrayOf(44), remote.files["foreign"])
            assertEquals(0, remote.writes)
        }

    @Test
    fun corruptRecordRemainsVisibleAndCannotResume() =
        runBlocking<Unit> {
            val id = preview()
            File(store.directory(id), "run.json").writeText("broken")
            val record = store.runs().single()
            assertEquals(OwnSyncStatus.NeedsReview, record.status)
            assertEquals("corrupt", record.failure)
            assertFalse(OwnSyncRunner(context, services).run(id))
            assertTrue(runCatching { controller.resume(id) }.isFailure)
        }

    @Test
    fun readbackCorruptionNeverBecomesOwnedOutput() =
        runBlocking<Unit> {
            val id = preview()
            controller.confirm(id)
            remote.corruptReadback = true
            assertFalse(OwnSyncRunner(context, services).run(id))
            assertEquals(0, store.job(store.run(id)!!.jobId)!!.outputs.size)
            assertEquals(OwnSyncStatus.NeedsReview, store.run(id)!!.status)
        }

    @Test
    fun interruptedMoveIntentCanBeReversedAfterCancellation() =
        runBlocking<Unit> {
            val id = preview(OwnSyncPolicy.ManagedMirror)
            finish(id)
            val jobId = store.run(id)!!.jobId
            source.files.remove(listOf("nested", "same.png"))
            val second = controller.rerun(jobId)
            OwnSyncRunner(context, services).run(second)
            controller.confirm(second, mirrorConfirmed = true)
            remote.afterMove = { throw IOException("fixture interrupted after server move") }
            OwnSyncRunner(context, services).run(second)
            assertEquals(OwnSyncStatus.NeedsReview, store.run(second)!!.status)
            controller.cancel(second)
            OwnSyncRunner(context, services).run(second)
            val intent = store.run(second)!!.plan.single { it.action == OwnSyncAction.Quarantine }
            assertNotNull(intent.staging)
            remote.afterMove = null
            val recovery = controller.recoverCancelledMove(second, intent.id)
            assertTrue(OwnSyncRunner(context, services).run(recovery))
            assertArrayEquals(byteArrayOf(1, 2, 3), remote.files[intent.path.joinToString("/")])
        }

    @Test
    fun cancellingParentClosesRegisteredBlockingConnectionPromptly() =
        runBlocking<Unit> {
            val id =
                controller.create(
                    "Blocked fixture",
                    Uri.parse(OwnSyncFixtureSource.Tree),
                    services.profiles().single().id,
                    OwnSyncPolicy.Additive,
                )
            val entered = java.util.concurrent.CountDownLatch(1)
            val released = java.util.concurrent.CountDownLatch(1)
            val blocked =
                OwnSyncServices(
                    services.profiles,
                    RemoteConnectionFactory { _, _, token ->
                        token.register(java.io.Closeable { released.countDown() })
                        entered.countDown()
                        if (!released.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw IOException("fixture timeout")
                        token.check()
                        remote.connection()
                    },
                    services.credentials,
                    services.networkAllowed,
                    services.sourceFactory,
                )
            val runner = OwnSyncRunner(context, blocked)
            val task = launch(Dispatchers.IO) { runner.run(id) }
            try {
                assertTrue(
                    withContext(Dispatchers.IO) {
                        entered.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    }
                )
                task.cancel()
                withTimeout(3000) { task.join() }
                assertEquals(0L, released.count)
                assertEquals(OwnSyncStatus.Paused, store.run(id)!!.status)
                assertFalse(runner.needsRetry(id))
            } finally {
                runner.cancel()
                task.cancelAndJoin()
            }
        }

    @Test
    fun alteredJournalCannotRedirectAPlanOutsideItsOwnedNamespace() =
        runBlocking<Unit> {
            val id = preview()
            val file = File(store.directory(id), "run.json")
            val json = org.json.JSONObject(file.readText())
            json
                .getJSONArray("plan")
                .getJSONObject(0)
                .put("path", org.json.JSONArray(listOf("foreign", "photo.png")))
            file.writeText(json.toString())
            val before = file.readBytes()
            assertEquals("corrupt", store.runs().single().failure)
            assertFalse(OwnSyncRunner(context, services).run(id))
            assertEquals(0, remote.writes)
            assertEquals(0, remote.moves)
            assertArrayEquals(before, file.readBytes())
        }
}
