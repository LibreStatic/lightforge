package com.ugallery.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.remotestorage.*
import com.ugallery.feature.settings.LocalBackupArchive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Explicit two-phase real-process probe. Only the coordinator's isolated throttled fixture runs it.
 */
class RemoteBackupProcessDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val arguments
        get() = InstrumentationRegistry.getArguments()

    private val record
        get() = File(context.filesDir, "remote-backup-process-probe.json")

    private fun guard(phase: String) {
        assertEquals("com.ugallery.app.pdfacceptance", context.packageName)
        assertEquals("ugallery-wave34", arguments.getString("sftpFixture"))
        assertEquals(phase, arguments.getString("remoteProbePhase"))
    }

    private fun hash(input: InputStream): Pair<Long, String> =
        input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var bytes = 0L
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                bytes += read
                require(bytes <= 16L * 1024 * 1024)
                digest.update(buffer, 0, read)
            }
            bytes to digest.digest().joinToString("") { value -> "%02x".format(value) }
        }

    private fun task(id: String): JSONObject {
        require(UUID.fromString(id).toString() == id)
        return JSONObject(File(context.filesDir, "remote-backup/$id/task.json").readText())
    }

    private fun archive(id: String) =
        File(context.filesDir, "remote-backup/$id/archive.ugallery.zip")

    @Test
    fun prepareRealInterruptedRemoteUpload() =
        runBlocking<Unit> {
            guard("prepare")
            val sourceName = "ugallery-remote-process-${UUID.randomUUID()}"
            val resolver = context.contentResolver
            val source =
                requireNotNull(
                    resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "$sourceName.png")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$sourceName/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )
                )
            val bitmap = Bitmap.createBitmap(1700, 1700, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(1700 * 1700)
            val random = java.util.Random(343434)
            for (index in pixels.indices) pixels[index] = random.nextInt() or 0xff000000.toInt()
            bitmap.setPixels(pixels, 0, 1700, 0, 0, 1700, 1700)
            resolver.openOutputStream(source)!!.use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            pixels.fill(0)
            assertEquals(
                1,
                resolver.update(
                    source,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ),
            )
            val original = hash(requireNotNull(resolver.openInputStream(source)))
            assertTrue(
                "Owned random PNG must be 8–12 MiB, actual=${original.first}",
                original.first in 8L * 1024 * 1024..12L * 1024 * 1024,
            )
            val profile =
                RemoteProfile(
                    name = sourceName,
                    protocol = RemoteProtocol.SFTP,
                    host = "10.0.2.2",
                    port = 22237,
                    username = "ugalleryfixture",
                    root = "/data/archive",
                    trustedHostKey = requireNotNull(arguments.getString("sftpPin")),
                )
            val database = GalleryDatabaseFactory.open(context)
            val controller = GalleryRemoteBackupWorker.controller(context, database)
            try {
                controller.saveProfile(
                    profile,
                    RemoteCredentials.Password("ugallery-fixture-password".toCharArray()),
                )
                val id = controller.enqueueBackup(profile.id, listOf(source.toString()), false)
                withTimeout(120000) {
                    while (task(id).getString("status") != "AwaitingUploadReview") {
                        val status = task(id).getString("status")
                        assertTrue(
                            "Preparation status=$status",
                            status in setOf("Queued", "Running", "AwaitingUploadReview"),
                        )
                        delay(50)
                    }
                }
                val before = hash(archive(id).inputStream())
                val saved =
                    JSONObject()
                        .put("task", id)
                        .put("pid", Process.myPid())
                        .put("profile", profile.id)
                        .put("source", source.toString())
                        .put("sourceSha", original.second)
                        .put("sourceBytes", original.first)
                        .put("archiveSha", before.second)
                        .put("archiveBytes", before.first)
                record.writeText(saved.toString())
                controller.confirmUpload(id)
                withTimeout(90000) {
                    while (true) {
                        val state = task(id)
                        assertTrue(
                            "Upload status=${state.getString("status")}",
                            state.getString("status") in setOf("Queued", "Running"),
                        )
                        if (state.getString("phase") == "Transfer" && !state.isNull("staging")) {
                            saved.put("oldStage", state.getString("staging"))
                            record.writeText(saved.toString())
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "stream",
                                        "REMOTE_PROCESS_READY task=$id pid=${Process.myPid()} staging=${state.getString("staging")}\n",
                                    )
                                },
                            )
                            break
                        }
                        delay(20)
                    }
                }
                // Normal return never proves process death. The external coordinator kills only
                // this app.
                withTimeout(180000) { while (true) delay(1000) }
            } finally {
                controller.close()
                database.close()
            }
        }

    @Test
    fun verifyRemoteUploadAfterRealProcessDeath() =
        runBlocking<Unit> {
            guard("verify")
            val saved = JSONObject(record.readText())
            val id = saved.getString("task")
            assertNotEquals(saved.getInt("pid"), Process.myPid())
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val scenario = ActivityScenario.launch<MainActivity>(intent)
            val database = GalleryDatabaseFactory.open(context)
            val controller = GalleryRemoteBackupWorker.controller(context, database)
            try {
                controller.reconcile()
                withTimeout(150000) {
                    while (task(id).getString("status") != "Completed") {
                        val status = task(id).getString("status")
                        assertTrue(
                            "Reopened upload status=$status",
                            status in setOf("Queued", "Running", "Completed"),
                        )
                        delay(100)
                    }
                }
                val state = task(id)
                val original =
                    hash(
                        requireNotNull(
                            context.contentResolver.openInputStream(
                                Uri.parse(saved.getString("source"))
                            )
                        )
                    )
                assertEquals(saved.getString("sourceSha"), original.second)
                assertEquals(saved.getLong("sourceBytes"), original.first)
                val localArchive = hash(archive(id).inputStream())
                assertEquals(saved.getString("archiveSha"), localArchive.second)
                assertEquals(saved.getLong("archiveBytes"), localArchive.first)
                val manifest = LocalBackupArchive.inspect(archive(id))
                assertEquals(1, manifest.entries.size)
                assertEquals(original.second, manifest.entries.single().sha256)
                val oldStage = saved.getString("oldStage")
                val newStage = state.getString("staging")
                assertNotEquals(oldStage, newStage)
                val residuals = state.getJSONArray("residuals")
                assertTrue((0 until residuals.length()).any { residuals.getString(it) == oldStage })
                val profile =
                    RemoteProfile(
                        id = saved.getString("profile"),
                        name = "process verification",
                        protocol = RemoteProtocol.SFTP,
                        host = "10.0.2.2",
                        port = 22234,
                        username = "ugalleryfixture",
                        root = "/data/archive",
                        trustedHostKey = requireNotNull(arguments.getString("sftpPin")),
                    )
                val token = RemoteCancellation()
                try {
                    RemoteCredentials.Password("ugallery-fixture-password".toCharArray()).use {
                        credentials ->
                        OwnStorageConnectionFactory().connect(profile, credentials, token).use {
                            connection ->
                            val old = hash(connection.openRead(oldStage))
                            assertEquals(
                                requireNotNull(arguments.getString("remoteOldStageSha")),
                                old.second,
                            )
                            assertEquals(
                                requireNotNull(arguments.getString("remoteOldStageBytes")).toLong(),
                                old.first,
                            )
                            assertTrue(old.first > 0 && old.first < localArchive.first)
                            assertEquals(
                                localArchive,
                                hash(connection.openRead(state.getString("name"))),
                            )
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "stream",
                                        "REMOTE_PROCESS_RECOVERY_PASS task=$id oldPid=${saved.getInt("pid")} newPid=${Process.myPid()} archiveSha=${localArchive.second} sourceSha=${original.second} oldStage=$oldStage oldStageBytes=${old.first} oldStageSha=${old.second} newStage=$newStage\n",
                                    )
                                },
                            )
                        }
                    }
                } finally {
                    token.cancel()
                }
                // Keep the exact task, source and both remote partial names as inspectable probe
                // evidence.
            } finally {
                controller.close()
                database.close()
                scenario.close()
            }
        }
}
