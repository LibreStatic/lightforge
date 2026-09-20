package com.ugallery.feature.ownsync

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.remotestorage.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full sync engine with real SAF recursive reads and real own-server transports; no remote
 * deletion.
 */
@RunWith(AndroidJUnit4::class)
class OwnSyncRemoteDeviceTest {
    private val args
        get() = InstrumentationRegistry.getArguments()

    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Uri.parse("content://com.ugallery.feature.ownsync.test.source")
    private val tree = Uri.parse("$provider/tree/root")

    private suspend fun exercise(protocol: RemoteProtocol) {
        require(args.getString("sftpFixture") == "ugallery-wave34")
        val context = OwnSyncFixtureContext(base)
        val profile =
            if (protocol == RemoteProtocol.SFTP)
                RemoteProfile(
                    name = "Own sync SFTP fixture",
                    protocol = protocol,
                    host = "10.0.2.2",
                    port = 22234,
                    username = "ugalleryfixture",
                    root = "/data/archive",
                    trustedHostKey = requireNotNull(args.getString("sftpPin")),
                )
            else
                RemoteProfile(
                    name = "Own sync SMB fixture",
                    protocol = protocol,
                    host = "10.0.2.2",
                    port = requireNotNull(args.getString("smbPort")).toInt(),
                    username = "ugalleryfixture",
                    root = "",
                    share = "backup",
                    domain = "WORKGROUP",
                )
        val factory = OwnStorageConnectionFactory()
        val credentials =
            object : RemoteCredentialVault {
                override fun save(profileId: String, credentials: RemoteCredentials) {
                    error("not used")
                }

                override fun load(profileId: String) =
                    RemoteCredentials.Password(
                        (if (protocol == RemoteProtocol.SFTP) "ugallery-fixture-password"
                            else requireNotNull(args.getString("smbPassword")))
                            .toCharArray()
                    )

                override fun delete(profileId: String) {
                    error("not used")
                }
            }
        val saf = SafOwnSyncSource(context)
        // Provider and test target share a UID. This fixture substitutes grant acquisition only;
        // actual scan/open uses ContentResolver and DocumentsContract, not in-memory source bytes.
        val source =
            object : OwnSyncSourcePort by saf {
                override fun retain(jobId: String, tree: Uri) {
                    require(tree == this@OwnSyncRemoteDeviceTest.tree)
                }
            }
        val services =
            OwnSyncServices({ listOf(profile) }, factory, credentials, { true }, { source })
        val controller = OwnSyncController(context, services, {})
        val store = OwnSyncStore(context)
        fun <T> remote(block: (RemoteManagedConnection) -> T): T =
            credentials.load(profile.id).use { secret ->
                factory.connect(profile, secret, RemoteCancellation()).use {
                    block(it as RemoteManagedConnection)
                }
            }
        fun snapshot(namespace: String): Map<String, RemoteDigest> = remote { parent ->
            val result = sortedMapOf<String, RemoteDigest>()
            fun visit(dir: RemoteManagedConnection, path: List<String>) {
                dir.list(1000).forEach { entry ->
                    if (entry.regularFile)
                        result[(path + entry.name).joinToString("/")] =
                            dir.openRead(entry.name).use { OwnSyncIO.copy(it) }
                    else
                        dir.directory(listOf(entry.name), false).use {
                            visit(it, path + entry.name)
                        }
                }
            }
            parent.directory(listOf(namespace), false).use { visit(it, emptyList()) }
            result
        }
        suspend fun scan(jobId: String): String =
            controller.rerun(jobId).also {
                assertTrue(OwnSyncRunner(context, services).run(it))
                assertEquals(OwnSyncStatus.AwaitingReview, store.run(it)!!.status)
            }
        suspend fun apply(id: String, mirror: Boolean = false) {
            controller.confirm(id, mirrorConfirmed = mirror)
            assertTrue(OwnSyncRunner(context, services).run(id))
            assertEquals(OwnSyncStatus.Completed, store.run(id)!!.status)
        }
        try {
            base.contentResolver.call(provider, "fixture-reset", null, null)
            val first =
                controller.create("Real $protocol", tree, profile.id, OwnSyncPolicy.ManagedMirror)
            val initial = OwnSyncRunner(context, services).run(first)
            assertTrue(
                "$protocol status=${store.run(first)?.status} failure=${store.run(first)?.failure}",
                initial,
            )
            assertEquals(2, store.run(first)!!.plan.count { it.action == OwnSyncAction.Add })
            apply(first)
            val jobId = store.run(first)!!.jobId
            val namespace = store.job(jobId)!!.namespace
            val before = snapshot(namespace)
            assertTrue(before.keys.containsAll(listOf("Photos/nested.png", "top.pdf")))
            val second = scan(jobId)
            assertEquals(2, store.run(second)!!.plan.count { it.action == OwnSyncAction.Verified })
            apply(second)
            val afterSecond = snapshot(namespace)
            assertEquals(before, afterSecond)
            remote { parent ->
                parent.directory(listOf(namespace), false).use {
                    it.createExclusive("foreign.txt").use { out -> out.write(byteArrayOf(55, 66)) }
                }
            }
            base.contentResolver.call(provider, "fixture-change", null, null)
            val version = scan(jobId)
            assertEquals(1, store.run(version)!!.plan.count { it.action == OwnSyncAction.Add })
            assertEquals(
                1,
                store.run(version)!!.plan.count { it.action == OwnSyncAction.Quarantine },
            )
            apply(version, true)
            val versions =
                store.job(jobId)!!.outputs.filter {
                    it.sourcePath == listOf("Photos", "nested.png")
                }
            assertEquals(2, versions.size)
            assertEquals(1, versions.count { it.quarantine != null })
            base.contentResolver.call(provider, "fixture-remove", null, null)
            val removed = scan(jobId)
            assertEquals(
                1,
                store.run(removed)!!.plan.count { it.action == OwnSyncAction.Quarantine },
            )
            apply(removed, true)
            val recover =
                store.job(jobId)!!.outputs.single {
                    it.digest.sha256 == ownSyncHash(byteArrayOf(9, 8, 7, 6))
                }
            assertNotNull(recover.quarantine)
            val restoration = controller.restoreQuarantine(jobId, recover.id)
            assertTrue(OwnSyncRunner(context, services).run(restoration))
            assertEquals(OwnSyncStatus.Completed, store.run(restoration)!!.status)
            val after = snapshot(namespace)
            assertEquals(RemoteDigest(2, ownSyncHash(byteArrayOf(55, 66))), after["foreign.txt"])
            assertEquals(recover.digest, after[recover.path.drop(1).joinToString("/")])
            versions
                .filter { it.quarantine != null }
                .forEach { output ->
                    assertEquals(
                        output.digest,
                        after[
                            output.path
                                .drop(1)
                                .dropLast(1)
                                .plus(output.quarantine!!)
                                .joinToString("/")],
                    )
                }
            val evidence =
                JSONObject()
                    .put("protocol", protocol.name)
                    .put("namespace", namespace)
                    .put("job", jobId)
                    .put("runs", JSONArray(listOf(first, second, version, removed, restoration)))
                    .put("secondRunZeroWrites", before == afterSecond)
                    .put(
                        "files",
                        JSONObject().apply {
                            after.forEach { (path, digest) ->
                                put(
                                    path,
                                    JSONObject()
                                        .put("size", digest.size)
                                        .put("sha256", digest.sha256),
                                )
                            }
                        },
                    )
            File(
                    base.cacheDir,
                    "sync-real-${protocol.name.lowercase()}-api${android.os.Build.VERSION.SDK_INT}.json",
                )
                .writeText(evidence.toString())
        } finally {
            base.contentResolver.call(provider, "fixture-reset", null, null)
            controller.close()
            context.clean()
        }
    }

    @Test
    fun sftpSafPreviewAdditiveRepeatVersionMirrorAndRestore() =
        runBlocking<Unit> { exercise(RemoteProtocol.SFTP) }

    @Test
    fun smbSafPreviewAdditiveRepeatVersionMirrorAndRestore() =
        runBlocking<Unit> { exercise(RemoteProtocol.SMB) }
}
