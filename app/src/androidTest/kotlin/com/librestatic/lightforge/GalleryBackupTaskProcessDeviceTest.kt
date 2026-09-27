package com.librestatic.lightforge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.feature.settings.LocalBackupArchive
import com.librestatic.lightforge.feature.settings.LocalBackupTaskStatus
import com.librestatic.lightforge.feature.settings.LocalBackupTaskStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Two explicit phases run only through the coordinator's real force-stop probe. */
class GalleryBackupTaskProcessDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val authority = "com.librestatic.lightforge.pdfacceptance.test.backuptasks"
    private val base
        get() = Uri.parse("content://$authority")

    private val record
        get() = File(context.filesDir, "gallery-backup-process-probe.json")

    private fun command(name: String) = context.contentResolver.call(base, name, null, null)

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun guard() {
        assertEquals("com.librestatic.lightforge.pdfacceptance", context.packageName)
    }

    private suspend fun bootstrapRunningTarget() {
        val result = CompletableDeferred<Pair<Int, String?>>()
        val intent =
            Intent()
                .setComponent(
                    ComponentName(
                        "com.librestatic.lightforge.pdfacceptance.test",
                        "com.librestatic.lightforge.GalleryBackupTaskProbeReceiver",
                    )
                )
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("command", "bootstrap")
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.complete(resultCode to resultData)
                }
            },
            Handler(Looper.getMainLooper()),
            Activity.RESULT_CANCELED,
            null,
            null,
        )
        val delivered = withTimeout(15000) { result.await() }
        assertEquals(Activity.RESULT_OK, delivered.first)
        assertEquals("completed=fixture-bootstrap", delivered.second)
    }

    @Test
    fun prepareInterruptedPublication() =
        runBlocking<Unit> {
            guard()
            assertEquals(
                "prepare",
                InstrumentationRegistry.getArguments().getString("backupProbePhase"),
            )
            val controller = GalleryBackupTaskWorker.controller(context)
            try {
                bootstrapRunningTarget()
                command("fixture-reset")
                val root = DocumentsContract.buildDocumentUri(authority, "root")
                val original =
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        root,
                        "image/jpeg",
                        "source.jpg",
                    )!!
                val payload = ByteArray(4 * 1024 * 1024).also { java.util.Random(33).nextBytes(it) }
                context.contentResolver.openOutputStream(original)!!.use { it.write(payload) }
                val destination =
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        root,
                        "application/zip",
                        "process.zip",
                    )!!
                command("fixture-grant-process")
                command("fixture-arm-process")
                val id =
                    controller.enqueueBackup(
                        listOf(original.toString()),
                        destination,
                        "process.zip",
                        false,
                    )
                record.writeText(
                    JSONObject()
                        .put("task", id)
                        .put("pid", Process.myPid())
                        .put("source", original.toString())
                        .put("destination", destination.toString())
                        .put("sha", hash(payload))
                        .toString()
                )
                withTimeout(120000) {
                    while (command("fixture-process-state")?.getBoolean("blocked") != true) delay(
                        50
                    )
                }
                val task = LocalBackupTaskStore(context).read(id)!!
                assertEquals(LocalBackupTaskStatus.Running, task.status)
                assertTrue(LocalBackupTaskStore(context).archive(id).exists())
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "BACKUP PROCESS READY task=$id pid=${Process.myPid()} phase=${task.phase}\n",
                        )
                    },
                )
                // Coordinator records PID/checkpoint, force-stops this acceptance app, then
                // releases
                // the independent provider gate. Returning normally here would not prove process
                // death.
                withTimeout(180000) { while (true) delay(1000) }
            } finally {
                controller.close()
            }
        }

    @Test
    fun verifyReopenedTaskAfterRealProcessDeath() =
        runBlocking<Unit> {
            guard()
            assertEquals(
                "verify",
                InstrumentationRegistry.getArguments().getString("backupProbePhase"),
            )
            val saved = JSONObject(record.readText())
            assertNotEquals(saved.getInt("pid"), Process.myPid())
            val id = saved.getString("task")
            val controller = GalleryBackupTaskWorker.controller(context)
            try {
                controller.reconcile()
                val store = LocalBackupTaskStore(context)
                withTimeout(180000) {
                    while (store.read(id)!!.status != LocalBackupTaskStatus.Completed) {
                        val state = store.read(id)!!
                        check(
                            state.status !in
                                setOf(
                                    LocalBackupTaskStatus.Failed,
                                    LocalBackupTaskStatus.NeedsReview,
                                    LocalBackupTaskStatus.WaitingPermission,
                                )
                        ) {
                            "Task recovery failed: ${state.status}/${state.failure}"
                        }
                        delay(100)
                    }
                }
                val source = Uri.parse(saved.getString("source"))
                val destination = Uri.parse(saved.getString("destination"))
                val expectedArchive = store.archive(id).readBytes()
                assertArrayEquals(
                    expectedArchive,
                    context.contentResolver.openInputStream(destination)!!.use { it.readBytes() },
                )
                assertEquals(
                    saved.getString("sha"),
                    hash(context.contentResolver.openInputStream(source)!!.use { it.readBytes() }),
                )
                assertEquals(1, LocalBackupArchive.inspect(store.archive(id)).entries.size)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "BACKUP PROCESS RECOVERY PASS task=$id oldPid=${saved.getInt("pid")} newPid=${Process.myPid()} files=${store.read(id)!!.filesDone}\n",
                        )
                    },
                )
                controller.forget(id)
                record.delete()
            } finally {
                controller.close()
            }
        }
}
