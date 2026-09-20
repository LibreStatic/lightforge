package com.ugallery.feature.pdfstudio

import android.content.*
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Test-only noncooperative processing fixture, using the production lease and kill path. */
class PdfDeadlineTestService : PdfProcessingService() {
    private var limit = 4_000L

    override fun operationTimeoutMillis(operation: String) = limit

    private fun stall(): Nothing {
        android.util.Log.i(
            "PdfDeadline",
            "noncooperative fixture entered renderer=${Process.myPid()}",
        )
        while (true) Thread.yield()
    }

    private fun partial(output: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(output.fileDescriptor))
            .use { it.write("incomplete fixture".toByteArray()) }
    }

    override fun onBind(intent: Intent?): IPdfProcessor.Stub {
        val normal = super.onBind(intent)
        if (intent?.getBooleanExtra("test_stall", false) != true) return normal
        limit = if (intent.getBooleanExtra("test_long", false)) 60_000L else 4_000L
        return object : IPdfProcessor.Stub() {
            override fun cancel(id: String) = normal.cancel(id)

            override fun inspect(source: ParcelFileDescriptor): String =
                bounded("inspect") { stall() }

            override fun preview(
                source: ParcelFileDescriptor,
                page: Int,
                width: Int,
                output: ParcelFileDescriptor,
            ): String =
                bounded("preview") {
                    partial(output)
                    stall()
                }

            override fun exportPdf(
                id: String,
                manifest: ParcelFileDescriptor,
                compact: Boolean,
                sources: MutableList<ParcelFileDescriptor>,
                output: ParcelFileDescriptor,
                progress: IPdfProgress?,
            ): String =
                bounded("export", id) {
                    partial(output)
                    stall()
                }
        }
    }
}

class PdfOperationDeadlineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(stall: Boolean, long: Boolean = false) =
        object : ContextWrapper(context) {
            override fun bindService(
                intent: Intent,
                connection: ServiceConnection,
                flags: Int,
            ): Boolean =
                super.bindService(
                    Intent(intent)
                        .setClass(context, PdfDeadlineTestService::class.java)
                        .putExtra("test_stall", stall)
                        .putExtra("test_long", long),
                    connection,
                    flags,
                )
        }

    @Test
    fun threeNoncooperativeExportsReleaseHostWorkersAndSuccessorsRender(): Unit = runBlocking {
        val host = Process.myPid()
        repeat(3) { attempt ->
            val output = File(context.cacheDir, "deadline-$attempt-${newId()}.pdf")
            val started = SystemClock.elapsedRealtime()
            try {
                val error =
                    runCatching {
                            withTimeout(15_000) {
                                IsolatedPdfEngine(fixture(true))
                                    .export(
                                        PdfProject(name = "Deadline"),
                                        emptyList(),
                                        output,
                                        false,
                                    )
                            }
                        }
                        .exceptionOrNull()
                assertNotNull(error)
                assertEquals(PdfFailure.RendererUnavailable, PdfFailure.from(error!!))
                assertFalse(output.exists())
                assertEquals(host, Process.myPid())
                android.util.Log.i(
                    "PdfDeadline",
                    "export attempt=$attempt elapsedMs=${SystemClock.elapsedRealtime() - started} failure=${PdfFailure.from(error)} host=$host",
                )
                val next = IsolatedPdfEngine(fixture(false))
                withTimeout(15_000) {
                    next.export(PdfProject(name = "After deadline"), emptyList(), output, false)
                    assertEquals(1, next.inspect(output).size)
                }
            } finally {
                output.delete()
            }
        }
    }

    @Test
    fun noncooperativeInspectAndPreviewReturnRendererFailureAndRecover(): Unit = runBlocking {
        val source = File(context.cacheDir, "deadline-source-${newId()}.pdf")
        val output = File(context.cacheDir, "deadline-preview-${newId()}.png")
        val normal = IsolatedPdfEngine(fixture(false))
        try {
            normal.export(PdfProject(name = "Input"), emptyList(), source, false)
            val hash = PdfProjectRepository.sha256(source)
            for (preview in listOf(false, true)) {
                val start = SystemClock.elapsedRealtime()
                val error =
                    runCatching {
                            withTimeout(15_000) {
                                val engine = IsolatedPdfEngine(fixture(true))
                                if (preview) engine.preview(source, 0, output)
                                else engine.inspect(source)
                            }
                        }
                        .exceptionOrNull()
                assertNotNull(error)
                assertEquals(PdfFailure.RendererUnavailable, PdfFailure.from(error!!))
                assertFalse(output.exists())
                assertEquals(hash, PdfProjectRepository.sha256(source))
                assertEquals(1, normal.inspect(source).size)
                normal.preview(source, 0, output)
                assertTrue(output.length() > 0)
                output.delete()
                android.util.Log.i(
                    "PdfDeadline",
                    "${if (preview) "preview" else "inspect"} deadline/recovery elapsedMs=${SystemClock.elapsedRealtime() - start} host=${Process.myPid()}",
                )
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun cancellationKillsPinnedNoncooperativeRendererWithinGrace(): Unit = runBlocking {
        val pinned = fixture(true, long = true)
        val connected = CompletableDeferred<IPdfProcessor>()
        val death = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    binder!!.linkToDeath({ death.countDown() }, 0)
                    connected.complete(IPdfProcessor.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        assertTrue(
            pinned.bindService(
                Intent(context, PdfProcessingService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        )
        val output = File(context.cacheDir, "deadline-cancel-${newId()}.pdf")
        var job: Job? = null
        try {
            withTimeout(15_000) { connected.await() }
            job = launch {
                IsolatedPdfEngine(pinned)
                    .export(PdfProject(name = "Cancel"), emptyList(), output, false)
            }
            withTimeout(15_000) { while (output.length() == 0L) delay(20) }
            val start = SystemClock.elapsedRealtime()
            job.cancelAndJoin()
            assertFalse(output.exists())
            assertTrue(
                "Pinned process must die before its 60s test deadline",
                death.await(12, TimeUnit.SECONDS),
            )
            val elapsed = SystemClock.elapsedRealtime() - start
            assertTrue("Cancellation must allow the configured grace", elapsed >= 4_000L)
            android.util.Log.i(
                "PdfDeadline",
                "pinned cancellation elapsedMs=$elapsed; host=${Process.myPid()}",
            )
        } finally {
            job?.cancelAndJoin()
            pinned.unbindService(connection)
            output.delete()
        }
    }
}
