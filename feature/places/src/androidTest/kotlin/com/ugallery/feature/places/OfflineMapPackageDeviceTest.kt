package com.ugallery.feature.places

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineMapPackageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var access: Access
    private val controllers = mutableListOf<OfflinePlacesController>()

    @Before
    fun setup() {
        root = File(context.cacheDir, "maps-owned-" + UUID.randomUUID()).apply { mkdirs() }
        source = File(root, "original.pmtiles")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("maps/monaco.pmtiles")
            .use { i -> source.outputStream().use { i.copyTo(it) } }
        access = Access(source)
    }

    @After
    fun cleanup() {
        controllers.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun controller(free: Long = 200L * 1024 * 1024 * 1024): OfflinePlacesController =
        OfflinePlacesController(
                context,
                {},
                access,
                { OfflineMapDeviceFacts(free, 8L * 1024 * 1024 * 1024, false, true, true, true) },
                File(root, "installed"),
            )
            .also { controllers += it }

    private fun hash(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

    private suspend fun installed(c: OfflinePlacesController): OfflineMapPackage {
        val id = c.importPackage(Uri.parse("content://maps.fixture/source"), "Monaco")
        assertEquals(OfflineMapTaskStatus.ReadyForReview, c.run(id))
        assertTrue(c.packs.value.isEmpty())
        c.confirmImport(id)
        assertEquals(OfflineMapTaskStatus.Installed, c.run(id))
        return c.packs.value.single()
    }

    @Test
    fun realPmtilesAndMbtilesReadWithoutModifyingSources() {
        val initial = hash(source)
        val p = OfflineMapValidator.inspect(source)
        assertEquals(OfflineMapFormat.PMTilesVector, p.format)
        assertEquals("protomaps-v4", p.schema)
        assertEquals(15, p.maxZoom)
        assertTrue(p.bounds.contains(43.738, 7.425))
        assertEquals(initial, hash(source))
        val mb = File(root, "actual.mbtiles")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("maps/monaco.mbtiles")
            .use { i -> mb.outputStream().use { i.copyTo(it) } }
        val before = hash(mb)
        val m = OfflineMapValidator.inspect(mb)
        assertEquals(OfflineMapFormat.MBTilesVector, m.format)
        assertEquals(13, m.minZoom)
        assertEquals(15, m.maxZoom)
        assertEquals(before, hash(mb))
    }

    @Test
    fun realImportReviewInstallReopenAndFlowBetweenControllers() = runBlocking {
        val c = controller()
        val other = controller()
        val original = hash(source)
        val pack = installed(c)
        withTimeout(5000) { other.packs.first { it.size == 1 } }
        assertEquals(original, pack.sha256)
        assertEquals(original, hash(c.file(pack)))
        assertEquals(original, hash(source))
        assertFalse(access.grants.contains("content://maps.fixture/source"))
        val reopened = controller()
        assertEquals(pack, reopened.packs.value.single())
        assertEquals(OfflineMapTaskStatus.Installed, reopened.run(pack.id))
    }

    @Test
    fun corruptReplacementRetainsOldMapAndOriginal() = runBlocking {
        val c = controller()
        val old = installed(c)
        val before = hash(c.file(old))
        val bad = File(root, "corrupt.pmtiles").apply { writeBytes(ByteArray(300) { 4 }) }
        access.current = bad
        val id = c.importPackage(Uri.parse("content://maps.fixture/corrupt"), "Replacement", old.id)
        assertEquals(OfflineMapTaskStatus.Failed, c.run(id))
        assertEquals(old, c.packs.value.single())
        assertEquals(before, hash(c.file(old)))
        assertEquals(300, bad.length().toInt())
    }

    @Test
    fun pauseResumeAndCancelPreserveOriginalAndPriorGrants() = runBlocking {
        val c = controller()
        val uri = Uri.parse("content://maps.fixture/prior")
        access.grants += uri.toString()
        val initial = hash(source)
        val id = c.importPackage(uri, "Paused")
        var reads = 0
        access.onRead = { if (++reads == 2) c.pause(id) }
        assertEquals(OfflineMapTaskStatus.Paused, c.run(id))
        assertTrue(c.packs.value.isEmpty())
        assertTrue(File(c.directory, "$id.partial").length() > 0)
        access.onRead = {}
        c.resume(id)
        assertEquals(OfflineMapTaskStatus.ReadyForReview, c.run(id))
        c.cancel(id)
        assertEquals(OfflineMapTaskStatus.Cancelled, c.run(id))
        assertFalse(File(c.directory, "$id.partial").exists())
        assertTrue(access.grants.contains(uri.toString()))
        assertEquals(initial, hash(source))
    }

    @Test
    fun permissionFailureRequiresExplicitRegrantAndReview() = runBlocking {
        val c = controller()
        access.denied = true
        val id = c.importPackage(Uri.parse("content://maps.fixture/revoked"), "Revoked")
        assertEquals(OfflineMapTaskStatus.WaitingPermission, c.run(id))
        access.denied = false
        val next = c.regrant(id, Uri.parse("content://maps.fixture/new-grant"))
        assertNotEquals(id, next)
        assertEquals(OfflineMapTaskStatus.ReadyForReview, c.run(next))
        assertTrue(c.packs.value.isEmpty())
        c.confirmImport(next)
        assertEquals(OfflineMapTaskStatus.Installed, c.run(next))
    }

    @Test
    fun publicationBeforeReceiptRecoversAndCancellationCleansOnlyOwnedFinal() = runBlocking {
        val c = controller()
        val id = c.importPackage(Uri.parse("content://maps.fixture/crash"), "Crash")
        assertEquals(OfflineMapTaskStatus.ReadyForReview, c.run(id))
        c.confirmImport(id)
        assertTrue(File(c.directory, "$id.partial").renameTo(File(c.directory, "$id.pmtiles")))
        val fresh = controller()
        assertEquals(OfflineMapTaskStatus.Installed, fresh.run(id))
        val pack = fresh.packs.value.single()
        assertEquals(hash(source), hash(fresh.file(pack)))
        val second = fresh.importPackage(Uri.parse("content://maps.fixture/cancel"), "Cancel")
        assertEquals(OfflineMapTaskStatus.ReadyForReview, fresh.run(second))
        assertTrue(
            File(c.directory, "$second.partial").renameTo(File(c.directory, "$second.pmtiles"))
        )
        fresh.cancel(second)
        assertEquals(OfflineMapTaskStatus.Cancelled, fresh.run(second))
        assertFalse(File(c.directory, "$second.pmtiles").exists())
        assertTrue(fresh.file(pack).exists())
    }

    @Test
    fun noWorldBytesWithoutOptInAndInsufficientStorageIsDurable() = runBlocking {
        val c = controller(0)
        try {
            c.downloadWorld(false)
            fail("consent required")
        } catch (_: IllegalArgumentException) {}
        assertTrue(c.tasks.value.isEmpty())
        val id = c.downloadWorld(true)
        assertEquals(OfflineMapTaskStatus.WaitingStorage, c.run(id))
        assertFalse(File(c.directory, "$id.partial").exists())
        assertEquals(0L, c.tasks.value.single().copied)
        assertEquals(OfflineMapTaskStatus.WaitingStorage, controller(0).tasks.value.single().status)
    }

    @Test
    fun stylesUseOnlyLocalDataAndEveryGlyphRangeIsBundled() = runBlocking {
        val c = controller()
        val pack = installed(c)
        listOf(false, true).forEach {
            val style =
                OfflineMapStyle.json(pack, c.file(pack), it, 0xffd9e2ff.toInt(), 0xff001945.toInt())
            assertFalse(style.contains("https://"))
            assertFalse(style.contains("http://"))
            assertTrue(
                org.json.JSONObject(style).getString("glyphs").startsWith("asset://places/fonts/")
            )
        }
        try {
            OfflineMapStyle.requireLocal(
                "{\"version\":8,\"glyphs\":\"https://example.invalid/{range}.pbf\"}"
            )
            fail("remote style")
        } catch (_: IllegalArgumentException) {}
        assertEquals(256, context.assets.list("places/fonts/Noto Sans Regular")!!.size)
    }

    @Test
    fun validReplacementRetiresOnlyOldOwnedBytesAndReplaysCleanup() = runBlocking {
        val c = controller()
        val old = installed(c)
        val unrelated = File(c.directory, "unrelated.txt").apply { writeText("keep") }
        val id =
            c.importPackage(Uri.parse("content://maps.fixture/replacement"), "New Monaco", old.id)
        assertEquals(OfflineMapTaskStatus.ReadyForReview, c.run(id))
        assertTrue(c.file(old).exists())
        c.confirmImport(id)
        assertEquals(OfflineMapTaskStatus.Installed, c.run(id))
        assertEquals(id, c.packs.value.single().id)
        assertFalse(c.file(old).exists())
        assertEquals(hash(source), hash(c.file(c.packs.value.single())))
        // Crash after receipt but before unlink: persisted exact retired filename is replayed.
        c.file(old).writeText("owned retired bytes")
        val journal = File(c.directory, "state.json")
        val data =
            org.json
                .JSONObject(journal.readText())
                .put("retired", org.json.JSONArray().put(old.fileName))
        journal.writeText(data.toString())
        val reopened = controller()
        withTimeout(5000) { while (c.file(old).exists()) delay(20) }
        assertEquals("keep", unrelated.readText())
        assertTrue(reopened.file(reopened.packs.value.single()).exists())
    }

    @Test
    fun cancellationInterruptsActualReadOnlySqliteQuery() = runBlocking {
        val mb = File(root, "slow.mbtiles")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("maps/monaco.mbtiles")
            .use { i -> mb.outputStream().use { i.copyTo(it) } }
        android.database.sqlite.SQLiteDatabase.openDatabase(
                mb.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            .use { db ->
                db.execSQL("ALTER TABLE tiles RENAME TO actual_tiles")
                db.execSQL(
                    "CREATE VIEW tiles AS WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT 13 AS zoom_level, x AS tile_column, 0 AS tile_row, X'1a00' AS tile_data FROM n"
                )
            }
        val before = hash(mb)
        val signal = android.os.CancellationSignal()
        val started = android.os.SystemClock.elapsedRealtime()
        val canceller =
            launch(Dispatchers.IO) {
                delay(200)
                signal.cancel()
            }
        try {
            withContext(Dispatchers.IO) { OfflineMapValidator.inspect(mb, signal) }
            fail("SQLite query must stop via CancellationSignal")
        } catch (_: android.os.OperationCanceledException) {}
        canceller.join()
        assertTrue(android.os.SystemClock.elapsedRealtime() - started < 5000)
        assertEquals(before, hash(mb))
        access.current = mb
        val c = controller()
        val id =
            c.importPackage(Uri.parse("content://maps.fixture/sql-cancel"), "Cancelable MBTiles")
        val running = async(Dispatchers.IO) { c.run(id) }
        withTimeout(5000) {
            c.tasks.first {
                it.any { task -> task.id == id && task.status == OfflineMapTaskStatus.Verifying }
            }
        }
        c.cancel(id)
        assertEquals(OfflineMapTaskStatus.Cancelled, withTimeout(5000) { running.await() })
        assertFalse(File(c.directory, "$id.partial").exists())
        assertTrue(c.packs.value.isEmpty())
        assertEquals(before, hash(mb))
    }

    @Test
    fun userPauseAndCancelDuringProviderOpenAreNotOverwrittenByLateResponse() = runBlocking {
        val c = controller()
        val original = hash(source)
        for (cancel in listOf(false, true)) {
            val id =
                c.importPackage(Uri.parse("content://maps.fixture/late-$cancel"), "Late provider")
            access.onOpen = { if (cancel) c.cancel(id) else c.pause(id) }
            assertEquals(
                if (cancel) OfflineMapTaskStatus.Cancelled else OfflineMapTaskStatus.Paused,
                c.run(id),
            )
            assertTrue(c.packs.value.isEmpty())
            assertFalse(File(c.directory, "$id.partial").exists())
            assertEquals(original, hash(source))
            access.onOpen = {}
            if (!cancel) {
                c.resume(id)
                assertEquals(OfflineMapTaskStatus.ReadyForReview, c.run(id))
                c.cancel(id)
                assertEquals(OfflineMapTaskStatus.Cancelled, c.run(id))
            } else {
                assertEquals(OfflineMapTaskStatus.Cancelled, c.run(id))
                assertTrue(c.packs.value.isEmpty())
            }
        }
        for (prior in listOf(false, true)) for (cancel in listOf(false, true)) {
            val uri = Uri.parse("content://maps.fixture/late-take-$prior-$cancel")
            if (prior) access.grants += uri.toString()
            access.onRetain = {
                val pending = c.tasks.value.single { it.source == uri.toString() }
                assertEquals(!prior, pending.ownsReadGrant) // Written BEFORE take returns.
                if (cancel) c.cancel(pending.id) else c.pause(pending.id)
            }
            val id = c.importPackage(uri, "Late take")
            access.onRetain = {}
            assertEquals(
                if (cancel) OfflineMapTaskStatus.Cancelled else OfflineMapTaskStatus.Paused,
                c.run(id),
            )
            assertTrue(c.packs.value.isEmpty())
            c.cancel(id)
            assertEquals(OfflineMapTaskStatus.Cancelled, c.run(id))
            assertEquals(prior, access.hasRead(uri))
        }
        val crashUri = Uri.parse("content://maps.fixture/crash-after-take")
        val death = Error("simulated process death immediately after persisted take")
        access.onRetain = { throw death }
        try {
            c.importPackage(crashUri, "Take crash")
            fail("process death")
        } catch (actual: Error) {
            assertSame(death, actual)
        }
        access.onRetain = {}
        val reopened = controller()
        val crash = reopened.tasks.value.single { it.source == crashUri.toString() }
        assertTrue(crash.ownsReadGrant)
        assertTrue(access.hasRead(crashUri))
        reopened.cancel(crash.id)
        assertEquals(OfflineMapTaskStatus.Cancelled, reopened.run(crash.id))
        assertFalse(access.hasRead(crashUri))
        val shared = Uri.parse("content://maps.fixture/shared-take")
        val first = c.importPackage(shared, "Owner")
        val second = c.importPackage(shared, "Shared")
        c.cancel(first)
        assertEquals(OfflineMapTaskStatus.Cancelled, c.run(first))
        assertTrue(access.hasRead(shared))
        c.cancel(second)
        assertEquals(OfflineMapTaskStatus.Cancelled, c.run(second))
        assertFalse(access.hasRead(shared))
        assertEquals(original, hash(source))
    }

    @Test
    fun mbtilesMetadataHasAnAggregateBoundNotOnlyPerRowLimit() {
        val mb = File(root, "metadata-limit.mbtiles")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("maps/monaco.mbtiles")
            .use { input -> mb.outputStream().use { input.copyTo(it) } }
        android.database.sqlite.SQLiteDatabase.openDatabase(
                mb.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            .use { db ->
                val text = "x".repeat(256 * 1024)
                repeat(17) { index ->
                    db.execSQL(
                        "INSERT INTO metadata(name,value) VALUES(?,?)",
                        arrayOf<Any>("bounded-$index", text),
                    )
                }
            }
        val before = hash(mb)
        try {
            OfflineMapValidator.inspect(mb)
            fail("Aggregate metadata must be bounded to 4 MiB")
        } catch (expected: IllegalArgumentException) {
            assertEquals("LIMIT", expected.message)
        }
        assertEquals(before, hash(mb))
    }

    private class Access(var current: File) : OfflineMapSourceAccess {
        val grants = mutableSetOf<String>()
        var denied = false
        var onRead: () -> Unit = {}
        var onOpen: () -> Unit = {}
        var onRetain: () -> Unit = {}

        override fun hasRead(uri: Uri) = grants.contains(uri.toString())

        override fun retainRead(uri: Uri) {
            if (denied) throw SecurityException()
            grants += uri.toString()
            onRetain()
        }

        override fun releaseRead(uri: Uri) {
            grants -= uri.toString()
        }

        override fun open(uri: Uri): OfflineMapInput {
            onOpen()
            if (denied) throw SecurityException()
            val stream =
                object : FilterInputStream(current.inputStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) onRead()
                        return n
                    }
                }
            return OfflineMapInput(stream, current.length())
        }
    }
}
