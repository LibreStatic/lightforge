package com.ugallery.app

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.preferences.GallerySettingsRepository
import com.ugallery.core.preferences.VideoScrubbingMode
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Public MediaStore tile → real MainActivity viewer → actual Home/STOP. No player/lifecycle injection. */
class PublicVideoHomeAppDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val device get() = UiDevice.getInstance(instrumentation)
    private var observedDurationMillis = 0L
    private val uuid: String get() {
        check(context.packageName == "com.ugallery.app.pdfacceptance")
        return checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
            check(UUID.fromString(it).toString() == it)
        }
    }
    private fun directory(id: String) = File(context.cacheDir.canonicalFile, "public-video-home-$id")
    private fun receiptFile(id: String) = File(context.filesDir.canonicalFile, "public-video-home-$id.json")

    @Test fun publicVideoStopsAtHomeAndOnlyExplicitPlayResumes(): Unit = runBlocking {
        val id = uuid
        check(context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?.isTouchExplorationEnabled != true) { "Run ordinary public playback after restoring accessibility settings" }
        val dir = directory(id)
        check(!dir.exists() && !receiptFile(id).exists() && !File(receiptFile(id).path + ".bak").exists())
        val settings = GallerySettingsRepository(context)
        val before = settings.settings.first()
        val configured = before.copy(playback = before.playback.copy(autoplayVideos = false,
            loopVideos = false, rememberVideoPosition = false, videoScrubbingMode = VideoScrubbingMode.LegacySeekBar))
        val grantsBefore = resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }.toSet()
        val receipt = JSONObject().put("fixture", id).put("package", context.packageName)
            .put("directory", dir.absolutePath).put("phase", "before-create")
            .put("verified", false).put("cleanupComplete", false).put("files", JSONArray())
        save(id, receipt)
        check(dir.mkdir() && dir.canonicalFile == dir)
        var scenario: ActivityScenario<MainActivity>? = null
        var changedSettings = false
        var failure: Throwable? = null
        val db = GalleryDatabaseFactory.open(context)
        try {
            val (source, durationUs) = createNineSecondFixture(dir, receipt)
            check(receipt.getString("seedSha256") == "ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861")
            anchorFiles(dir, receipt)
            observedDurationMillis = durationUs / 1000
            val name = "$id.mp4"
            val relative = "Movies/public-video-home-$id/"
            val collection = MediaStore.Video.Media.getContentUri("external_primary")
            resolver.query(collection, arrayOf("_id"), "_display_name=? AND relative_path=?", arrayOf(name, relative), null)!!.use {
                check(it.count == 0)
            }
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put("_display_name", name); put("mime_type", "video/mp4"); put("relative_path", relative)
                put("is_pending", 1); put("datetaken", System.currentTimeMillis())
            }))
            receipt.put("uri", uri.toString()).put("name", name).put("relativePath", relative)
                .put("sourceSha256", hash(source)).put("sourceSize", source.length())
                .put("durationMillis", durationUs / 1000).put("phase", "public-pending")
            save(id, receipt)
            resolver.openFileDescriptor(uri, "w")!!.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            }
            check(hash(uri) == hash(source))
            check(resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null) == 1)
            resolver.query(uri, arrayOf("generation_added", "generation_modified"), null, null, null)!!.use {
                check(it.moveToFirst() && it.count == 1)
                receipt.put("generationAdded", it.getLong(0)).put("generationModified", it.getLong(1))
            }
            receipt.put("phase", "public-published"); save(id, receipt)
            settings.update { check(it == before); configured }; changedSettings = true
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var host: MainActivity
            scenario.onActivity { host = it; check(ViewModelProvider(it)[GalleryViewModel::class.java].selectionCount.value == 0L) }
            withTimeout(10_000) {
                ViewModelProvider(host)[GalleryViewModel::class.java].gallerySettings.first { it == configured }
            }
            withTimeout(30_000) { while (db.libraryDao().media("external_primary", ContentUris.parseId(uri)) == null) delay(50) }
            val tag = "media_external_primary_${ContentUris.parseId(uri)}"
            compose.onNodeWithTag("timeline_grid").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performTouchInput { click() }
            val play = context.getString(com.ugallery.feature.viewer.R.string.viewer_play)
            val pause = context.getString(com.ugallery.feature.viewer.R.string.viewer_pause)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(play).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription(play).assertIsDisplayed()
            compose.onNodeWithTag("video_legacy_seek_bar", useUnmergedTree = true).assertExists()
            // Capture semantics while initially paused; subsequent sampling bypasses Espresso idling.
            val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            instrumentation.runOnMainSync {
                val current = ViewModelProvider(host)[GalleryViewModel::class.java].currentMedia.value
                check(current?.key?.volumeName == "external_primary" && current.key.mediaStoreId == ContentUris.parseId(uri))
            }
            awaitStage(host, Stage.RESUMED)
            // Retain the launch action/data/type for ActivityScenario's lifecycle identity filter.
            // MainActivity.onNewIntent stores the actual resume Intent; a blank action loses tracking.
            val resumeIntent = main { Intent(host.intent).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            val task = host.taskId
            val pid = android.os.Process.myPid()
            receipt.put("taskId", task).put("pid", pid)
            touch(root, play)
            compose.mainClock.advanceTimeByFrame()
            receipt.put("afterPlay", diagnostic(root)); save(id, receipt)
            val early = awaitPosition(root, 2_500) { it > 200 && it < durationUs / 2000 }
            check(hasDescription(root, pause))
            receipt.put("earlyPositionMillis", early).put("phase", "playing-before-home"); save(id, receipt)
            check(device.pressHome())
            awaitStage(host, Stage.STOPPED)
            receipt.put("homeStoppedObserved", true).put("phase", "stopped"); save(id, receipt)
            val backgroundStart = SystemClock.elapsedRealtime()
            Thread.sleep(durationUs / 1000 + 700)
            check(inStage(host, Stage.STOPPED))
            receipt.put("backgroundMillis", SystemClock.elapsedRealtime() - backgroundStart)
            context.startActivity(resumeIntent)
            awaitStage(host, Stage.RESUMED)
            check(!host.isDestroyed && host.taskId == task && android.os.Process.myPid() == pid)
            instrumentation.runOnMainSync {
                check(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single() === host)
            }
            // Give the UI one polling interval to display the resumed controller state (never click Play here).
            Thread.sleep(350)
            compose.mainClock.advanceTimeBy(250)
            check(hasDescription(root, play) && !hasDescription(root, pause)) { "Return must show paused Play, not playing Pause" }
            val returned = position(root)
            check(returned >= early - 100 && returned < durationUs / 1000 - 500) { "Return restarted or reached natural end: early=$early return=$returned" }
            val stableStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - stableStart < 1_200) {
                check(hasDescription(root, play) && !hasDescription(root, pause))
                check(abs(position(root) - returned) <= 2) { "Paused progress advanced" }
                Thread.sleep(100)
            }
            receipt.put("sameActivityTaskPidReturned", true).put("returnedPositionMillis", returned)
                .put("stableMillis", SystemClock.elapsedRealtime() - stableStart).put("phase", "stable-paused")
            save(id, receipt)
            touch(root, play)
            val replay = awaitPosition(root, 2_000) { it >= returned + 250 }
            check(hasDescription(root, pause)); touch(root, pause)
            receipt.put("explicitReplayPositionMillis", replay).put("phase", "replay-advanced")
            check(hash(uri) == receipt.getString("sourceSha256")); verifyFiles(dir, receipt)
            receipt.put("verified", true); save(id, receipt)
        } catch (caught: Throwable) {
            failure = caught; receipt.put("failure", caught.toString())
            runCatching { device.takeScreenshot(File(context.filesDir, "public-video-home-$id-failure.png")) }
            runCatching { device.dumpWindowHierarchy(File(context.filesDir, "public-video-home-$id-failure.xml")) }
        } finally {
            fun step(action: () -> Unit) { try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) } }
            step { scenario?.close() }
            if (changedSettings) try { settings.update { check(it == configured); before }; check(settings.settings.first() == before) }
                catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) }
            step { check(grantsBefore == resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }.toSet()) }
            step { if (receipt.getJSONArray("files").length() == 0) anchorFiles(dir, receipt) }
            receipt.put("phase", if (failure == null) "verified-retained" else "failed-retained")
            step { save(id, receipt) }; step { db.close() }
        }
        failure?.let { throw it } // Retain exact UUID files/row for same-UUID explicit cleanup on failure.
        cleanup(id, receipt)
        println("PUBLIC_VIDEO_HOME $id PASS ${receipt}")
    }

    @Test fun cleanupOwnedPublicVideoFixture(): Unit = runBlocking {
        val id = uuid
        cleanup(id, JSONObject(AtomicFile(receiptFile(id)).openRead().bufferedReader().use { it.readText() }))
    }

    private suspend fun cleanup(id: String, receipt: JSONObject) {
        check(receipt.getString("fixture") == id && receipt.getString("package") == context.packageName)
        check(!receipt.getBoolean("cleanupComplete"))
        val dir = directory(id)
        check(receipt.getString("directory") == dir.absolutePath)
        verifyFiles(dir, receipt)
        if (receipt.has("uri")) {
            val uri = Uri.parse(receipt.getString("uri"))
            check(uri.scheme == "content" && uri.authority == "media" && uri.path!!.startsWith("/external_primary/video/media/"))
            check(receipt.getString("name") == "$id.mp4" && receipt.getString("relativePath") == "Movies/public-video-home-$id/")
            check(hash(uri) == receipt.getString("sourceSha256"))
            resolver.query(uri, arrayOf("_display_name", "relative_path", "_size", "owner_package_name", "generation_added", "generation_modified"), null, null, null)!!.use {
                check(it.moveToFirst() && it.count == 1)
                check(it.getString(0) == receipt.getString("name") && it.getString(1) == receipt.getString("relativePath"))
                check(it.getLong(2) == receipt.getLong("sourceSize") && it.getString(3) == context.packageName)
                check(it.getLong(4) == receipt.getLong("generationAdded") && it.getLong(5) == receipt.getLong("generationModified"))
            }
            check(resolver.delete(uri, "_display_name=? AND relative_path=? AND owner_package_name=? AND _size=? AND generation_added=? AND generation_modified=?",
                arrayOf(receipt.getString("name"), receipt.getString("relativePath"), context.packageName,
                    receipt.getLong("sourceSize").toString(), receipt.getLong("generationAdded").toString(), receipt.getLong("generationModified").toString())) == 1)
            resolver.query(uri, arrayOf("_id"), null, null, null)!!.use { check(it.count == 0) }
            val db = GalleryDatabaseFactory.open(context)
            try { db.libraryDao().deleteVideoPlaybackPosition("external_primary", ContentUris.parseId(uri))
                db.libraryDao().deleteMedia("external_primary", ContentUris.parseId(uri))
            } finally { db.close() }
        }
        val files = receipt.getJSONArray("files")
        repeat(files.length()) { index ->
            val f = files.getJSONObject(index); val file = File(dir, f.getString("name"))
            check(file.length() == f.getLong("size") && hash(file) == f.getString("sha256"))
            check(file.delete())
        }
        check(dir.listFiles()!!.isEmpty() && dir.delete())
        receipt.put("cleanupComplete", true).put("phase", "cleanup-complete"); save(id, receipt)
    }
    private fun anchorFiles(dir: File, receipt: JSONObject) {
        check(dir.canonicalFile == dir)
        receipt.put("files", JSONArray(dir.listFiles()!!.sortedBy { it.name }.map { file ->
            check(file.name in setOf("source.mp4", "repeated.mp4") && file.canonicalFile == file && OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
            JSONObject().put("name", file.name).put("size", file.length()).put("sha256", hash(file))
        }))
    }
    private fun verifyFiles(dir: File, receipt: JSONObject) {
        check(dir.canonicalFile == dir)
        val files = receipt.getJSONArray("files")
        val names = (0 until files.length()).map { files.getJSONObject(it).getString("name") }
        check(names.toSet().size == names.size && names.all { it in setOf("source.mp4", "repeated.mp4") })
        check(dir.listFiles()!!.map { it.name }.sorted() == names.sorted())
        repeat(files.length()) { index ->
            val f = files.getJSONObject(index); val file = File(dir, f.getString("name"))
            check(file.canonicalFile == file && OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
            check(file.length() == f.getLong("size") && hash(file) == f.getString("sha256"))
        }
    }
    private fun save(id: String, receipt: JSONObject) {
        val atomic = AtomicFile(receiptFile(id)); val output = atomic.startWrite()
        try { output.write(receipt.toString(2).toByteArray()); output.fd.sync(); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        val fd = Os.open(context.filesDir.path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        println("PUBLIC_VIDEO_HOME $id ${receipt.getString("phase")}")
    }
    private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::nodes)
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun hasDescription(root: SemanticsNode, description: String): Boolean = main {
        nodes(root).any { description in ((if (SemanticsProperties.ContentDescription in it.config) it.config[SemanticsProperties.ContentDescription] else emptyList())) }
    }
    private fun touch(root: SemanticsNode, description: String) {
        val bounds = main {
            nodes(root).filter { description in (if (SemanticsProperties.ContentDescription in it.config) it.config[SemanticsProperties.ContentDescription] else emptyList()) }
                .map { it.boundsInWindow }.filter { it.width > 0 && it.height > 0 }.single()
        }
        println("PUBLIC_VIDEO_TOUCH bounds=$bounds")
        check(device.click(bounds.center.x.toInt(), bounds.center.y.toInt()))
    }
    private fun diagnostic(root: SemanticsNode): JSONObject = main {
        JSONObject().put("clockMillis", compose.mainClock.currentTime).put("root", root.id)
            .put("nodes", JSONArray(nodes(root).mapNotNull { node ->
                val c = node.config
                if (SemanticsProperties.ContentDescription !in c && SemanticsProperties.Text !in c &&
                    SemanticsProperties.ProgressBarRangeInfo !in c) null else JSONObject()
                    .put("id", node.id).put("bounds", node.boundsInWindow.toString())
                    .put("description", if (SemanticsProperties.ContentDescription in c) c[SemanticsProperties.ContentDescription].toString() else "")
                    .put("text", if (SemanticsProperties.Text in c) c[SemanticsProperties.Text].toString() else "")
                    .put("progress", if (SemanticsProperties.ProgressBarRangeInfo in c) c[SemanticsProperties.ProgressBarRangeInfo].toString() else "")
            }))
    }
    private fun position(root: SemanticsNode): Long {
        // Foreground-only recomposition bridge; never idle through real playback or pump while STOPPED.
        compose.mainClock.advanceTimeByFrame()
        return main {
        val seek = nodes(root).single { (if (SemanticsProperties.TestTag in it.config) it.config[SemanticsProperties.TestTag] else "") == "video_legacy_seek_bar" }
        val progress = nodes(seek).mapNotNull { if (SemanticsProperties.ProgressBarRangeInfo in it.config) it.config[SemanticsProperties.ProgressBarRangeInfo] else null }.single()
        check(progress.range.start == 0f && progress.range.endInclusive == 1f)
        // Production Slider exposes unrounded ratio; exact fixture duration is recorded from remux.
        check(observedDurationMillis > 0)
        (progress.current * observedDurationMillis).toLong()
        }
    }
    private fun awaitPosition(root: SemanticsNode, timeout: Long, predicate: (Long) -> Boolean): Long {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var value: Long
        do { value = position(root); if (predicate(value)) return value; Thread.sleep(30) } while (SystemClock.elapsedRealtime() < deadline)
        error("Position timeout: $value; ${diagnostic(root)}")
    }
    private fun inStage(host: Activity, stage: Stage): Boolean = main {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { it === host }
    }
    private fun awaitStage(host: Activity, stage: Stage) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!inStage(host, stage) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
        check(inStage(host, stage)) { "Expected actual $stage" }
    }
    private fun hash(file: File): String = file.inputStream().use(::hash)
    private fun hash(uri: Uri): String = resolver.openInputStream(uri)!!.use(::hash)
    private fun hash(input: InputStream): String = MessageDigest.getInstance("SHA-256").run {
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) }
        digest().joinToString("") { "%02x".format(it) }
    }
    /** Same verified three-pass remux used by Motion Home; no new asset, reencode or speed change. */
    private fun createNineSecondFixture(directory: File, receipt: JSONObject): Pair<File, Long> {
        val seed = File(directory, "source.mp4")
        val repeated = File(directory, "repeated.mp4")
            check(seed.createNewFile())
            instrumentation.context.assets.open("motion_fixture.mp4").use { input -> seed.outputStream().use { output -> input.copyTo(output) } }
            check(seed.length() in 1..1_048_576)
            val seedHash = hash(seed)
            val formats = readTrackFormats(seed)
            check(formats.isNotEmpty() && formats.all {
                val mime = checkNotNull(it.getString(MediaFormat.KEY_MIME))
                mime.startsWith("video/") || mime.startsWith("audio/")
            })
            val videoTrack = formats.indices.single { formats[it].getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            // AAC priming packets have negative PTS in this real seed. Normalize every
            // track by the SAME origin, retaining A/V timing and all compressed samples.
            var timestampOriginUs = 0L
            val timing = MediaExtractor()
            try {
                timing.setDataSource(seed.absolutePath)
                formats.indices.forEach(timing::selectTrack)
                while (timing.sampleTrackIndex >= 0) {
                    val pts = timing.sampleTime
                    check(pts in -250_000L..3_500_000L)
                    timestampOriginUs = minOf(timestampOriginUs, pts)
                    timing.advance()
                }
            } finally { timing.release() }
            val spanUs = formats.maxOf { it.getLong(MediaFormat.KEY_DURATION) } - timestampOriginUs
            check(spanUs in 2_500_000L..3_500_000L)
            receipt.put("timestampOriginUs", timestampOriginUs).put("spanUs", spanUs)
            val muxer = MediaMuxer(repeated.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val counts = IntArray(formats.size)
            val writtenDigests = formats.map { MessageDigest.getInstance("SHA-256") }
            var packetHashes: List<String>? = null
            var firstTimes: List<List<Long>>? = null
            try {
                val trackMap = formats.map(muxer::addTrack)
                muxer.start()
                repeat(3) { repetition ->
                    val extractor = MediaExtractor()
                    val digests = formats.map { MessageDigest.getInstance("SHA-256") }
                    val passCounts = IntArray(formats.size)
                    val times = formats.map { mutableListOf<Long>() }
                    try {
                        extractor.setDataSource(seed.absolutePath)
                        check(extractor.trackCount == formats.size)
                        formats.indices.forEach(extractor::selectTrack)
                        val buffer = ByteBuffer.allocateDirect(seed.length().toInt())
                        val info = MediaCodec.BufferInfo()
                        while (extractor.sampleTrackIndex >= 0) {
                            val track = extractor.sampleTrackIndex
                            val timeUs = extractor.sampleTime
                            check(track in formats.indices && timeUs - timestampOriginUs in 0 until spanUs)
                            val flags = extractor.sampleFlags
                            check(flags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() == 0) { "Unexpected encrypted/partial sample" }
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            check(size > 0 && size <= buffer.capacity())
                            // B-frame PTS need not be monotonic. Preserve their exact order and
                            // timestamps within each repetition, with one common A/V time offset.
                            val sample = buffer.duplicate().apply { position(0); limit(size) }
                            writtenDigests[track].update(sample.duplicate())
                            digests[track].update(sample)
                            times[track].add(timeUs)
                            passCounts[track]++
                            info.set(0, size, timeUs - timestampOriginUs + repetition * spanUs,
                                if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(trackMap[track], buffer, info)
                            extractor.advance()
                        }
                    } finally { extractor.release() }
                    check(passCounts.all { it > 0 })
                    val hashes = digests.map { digest -> digest.digest().joinToString("") { "%02x".format(it) } }
                    if (repetition == 0) {
                        passCounts.copyInto(counts)
                        packetHashes = hashes
                        firstTimes = times.map { it.toList() }
                    } else {
                        check(counts.contentEquals(passCounts) && packetHashes == hashes && firstTimes == times)
                    }
                }
                muxer.stop()
            } finally { muxer.release() }
            val outputFormats = readTrackFormats(repeated)
            check(outputFormats.size == formats.size)
            formats.indices.forEach { index ->
                check(outputFormats[index].getString(MediaFormat.KEY_MIME) == formats[index].getString(MediaFormat.KEY_MIME))
            }
            // Read the actual muxed tracks back: three copies of every compressed sample,
            // not merely a nine-second duration tag or an unchanged first track.
            val verify = MediaExtractor()
            val outputCounts = IntArray(formats.size)
            val outputDigests = formats.map { MessageDigest.getInstance("SHA-256") }
            try {
                verify.setDataSource(repeated.absolutePath)
                formats.indices.forEach(verify::selectTrack)
                val buffer = ByteBuffer.allocateDirect(seed.length().toInt())
                while (verify.sampleTrackIndex >= 0) {
                    val track = verify.sampleTrackIndex
                    buffer.clear()
                    val size = verify.readSampleData(buffer, 0)
                    check(size > 0 && size <= buffer.capacity())
                    outputDigests[track].update(buffer.duplicate().apply { position(0); limit(size) })
                    outputCounts[track]++
                    verify.advance()
                }
            } finally { verify.release() }
            formats.indices.forEach { index ->
                check(outputCounts[index] == counts[index] * 3)
                check(outputDigests[index].digest().contentEquals(writtenDigests[index].digest()))
            }
            val durationUs = outputFormats[videoTrack].getLong(MediaFormat.KEY_DURATION)
            check(durationUs in 8_500_000L..10_000_000L)
            check(abs(durationUs - 3 * spanUs) <= 200_000L)
            val remuxHash = hash(repeated)
            check(repeated.length() in 1..4_194_304)

            receipt.put("seedSha256", seedHash).put("remuxSha256", remuxHash)
                .put("repetitions", 3).put("trackCount", formats.size)
                .put("samplesPerRepetition", JSONArray(counts.toList()))
                .put("packetHashesPerRepetition", JSONArray(checkNotNull(packetHashes)))
            check(hash(seed) == seedHash)
            return repeated to durationUs
    }
    private fun readTrackFormats(file: File): List<MediaFormat> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
        } finally { extractor.release() }
    }
}
