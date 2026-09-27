package com.librestatic.lightforge.feature.pdfstudio

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfDiskPreviewCacheTest {
    private val hash = "a".repeat(64)

    private fun png(file: File) {
        file.writeBytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + ByteArray(92))
    }

    private fun cache(root: File, bytes: Long = 200, count: Int = 2) =
        PdfDiskPreviewCache(root, bytes, count) { Long.MAX_VALUE }

    private fun file(root: File, page: Int) = File(root, "pdf-previews-v1/$hash-$page.png")

    @Test
    fun lruLimitsBytesAndEntriesAndSurvivesReopen(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-test").toFile()
        var renders = 0
        suspend fun read(c: PdfDiskPreviewCache, p: Int) =
            c.read(
                hash,
                p,
                {
                    renders++
                    png(it)
                },
                { it.readBytes() },
            )
        try {
            val c = cache(root)
            read(c, 0)
            read(c, 1)
            read(c, 0)
            read(c, 2)
            assertEquals(3, renders)
            assertTrue(file(root, 0).exists())
            assertFalse(file(root, 1).exists())
            assertTrue(file(root, 2).exists())
            read(cache(root), 0)
            assertEquals(3, renders)
            assertEquals(200L, File(root, "pdf-previews-v1").listFiles()!!.sumOf { it.length() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentMissesCoalesceAndReadersStayPinned(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-concurrent").toFile()
        var renders = 0
        try {
            val c = cache(root, count = 1)
            coroutineScope {
                (1..12)
                    .map {
                        async(Dispatchers.Default) {
                            c.read(
                                hash,
                                0,
                                {
                                    renders++
                                    delay(10)
                                    png(it)
                                },
                                { it.readBytes() },
                            )
                        }
                    }
                    .awaitAll()
            }
            assertEquals(1, renders)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader = async {
                c.read(
                    hash,
                    0,
                    { error("cached") },
                    {
                        entered.complete(Unit)
                        release.await()
                        assertTrue(it.exists())
                    },
                )
            }
            entered.await()
            val next = async { c.read(hash, 1, { png(it) }, { it.readBytes() }) }
            delay(50)
            assertFalse(next.isCompleted)
            assertTrue(file(root, 0).exists())
            release.complete(Unit)
            reader.await()
            next.await()
            assertFalse(file(root, 0).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun damagedCacheRegeneratesOnceAndInvalidOutputIsRemoved(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-corrupt").toFile()
        var renders = 0
        try {
            val c = cache(root)
            c.read(
                hash,
                0,
                {
                    renders++
                    png(it)
                },
                { it.readBytes() },
            )
            file(root, 0).writeText("damaged")
            c.read(
                hash,
                0,
                {
                    renders++
                    png(it)
                },
                { it.readBytes() },
            )
            assertEquals(2, renders)
            assertTrue(
                runCatching { c.read(hash, 1, { png(it) }, { throw IOException("decode failed") }) }
                    .isFailure
            )
            assertFalse(file(root, 1).exists())
            assertTrue(
                File(root, "pdf-previews-v1").listFiles()!!.none { it.name.endsWith(".part") }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun memoryPressureDoesNotDiscardOrRegenerateAValidDiskEntry(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-memory").toFile()
        var renders = 0
        try {
            val c = cache(root)
            c.read(
                hash,
                0,
                {
                    renders++
                    png(it)
                },
                { it.readBytes() },
            )
            val error =
                runCatching {
                        c.read(
                            hash,
                            0,
                            {
                                renders++
                                png(it)
                            },
                            { throw PdfOperationFailure(PdfFailure.MemoryPressure) },
                        )
                    }
                    .exceptionOrNull()!!
            assertEquals(PdfFailure.MemoryPressure, PdfFailure.from(error))
            assertTrue(file(root, 0).exists())
            assertEquals(1, renders)
            c.read(
                hash,
                0,
                {
                    renders++
                    png(it)
                },
                { it.readBytes() },
            )
            assertEquals(1, renders)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationDeletesPartialAndAllowsAnotherRequest(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-cancel").toFile()
        try {
            val c = cache(root)
            val entered = CompletableDeferred<Unit>()
            val pending = launch {
                c.read(
                    hash,
                    0,
                    {
                        png(it)
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                    { it.readBytes() },
                )
            }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(File(root, "pdf-previews-v1").listFiles()!!.isEmpty())
            c.read(hash, 0, { png(it) }, { it.readBytes() })
            assertTrue(file(root, 0).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedEntryAndInvalidKeysNeverPublish(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-limits").toFile()
        var decoded = false
        var invalidRendered = false
        try {
            val c = cache(root)
            assertTrue(
                runCatching {
                        c.read(
                            hash,
                            0,
                            {
                                png(it)
                                it.appendBytes(ByteArray(101))
                            },
                            {
                                decoded = true
                                it.readBytes()
                            },
                        )
                    }
                    .isFailure
            )
            assertFalse(decoded)
            assertFalse(file(root, 0).exists())
            assertTrue(File(root, "pdf-previews-v1").listFiles()!!.isEmpty())
            assertTrue(
                runCatching {
                        c.read(
                            "../source",
                            0,
                            {
                                invalidRendered = true
                                png(it)
                            },
                            { it.readBytes() },
                        )
                    }
                    .isFailure
            )
            assertTrue(
                runCatching {
                        c.read(
                            hash,
                            100,
                            {
                                invalidRendered = true
                                png(it)
                            },
                            { it.readBytes() },
                        )
                    }
                    .isFailure
            )
            assertFalse(invalidRendered)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lowSpaceStopsBeforeRenderingAndLegacyCleanupDoesNotTouchSources(): Unit = runBlocking {
        val root = Files.createTempDirectory("pdf-cache-space").toFile()
        try {
            val legacy = File(root, "pdf-preview-$hash-0.png").also { png(it) }
            val source = File(root, "original.pdf").also { it.writeText("original") }
            val dir = File(root, "pdf-previews-v1").apply { mkdirs() }
            File(dir, "orphan.part").writeText("partial")
            val error =
                runCatching {
                        PdfDiskPreviewCache(root, 200, 2) { 0 }
                            .read(hash, 0, { error("must not render") }, { it.readBytes() })
                    }
                    .exceptionOrNull()
            assertEquals(PdfFailure.StorageFull, PdfFailure.from(error!!))
            assertFalse(legacy.exists())
            assertEquals("original", source.readText())
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
