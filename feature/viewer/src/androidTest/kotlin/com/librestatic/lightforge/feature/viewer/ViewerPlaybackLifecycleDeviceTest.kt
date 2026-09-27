@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.librestatic.lightforge.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.espresso.IdlingPolicies
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Real ViewerContent + player + Home. No lifecycle injection or controller background shortcut. */
class ViewerPlaybackLifecycleDeviceTest {
    @get:Rule(order = 0) val espressoDeadline = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val master = IdlingPolicies.getMasterIdlingPolicy()
                val resources = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
                try {
                    IdlingPolicies.setMasterPolicyTimeout(15, TimeUnit.SECONDS)
                    IdlingPolicies.setIdlingResourceTimeout(10, TimeUnit.SECONDS)
                    IdlingPolicies.setMasterPolicyTimeoutWhenDebuggerAttached(true)
                    base.evaluate()
                } finally {
                    IdlingPolicies.setMasterPolicyTimeout(master.idleTimeout, master.idleTimeoutUnit)
                    IdlingPolicies.setIdlingResourceTimeout(resources.idleTimeout, resources.idleTimeoutUnit)
                    IdlingPolicies.setMasterPolicyTimeoutWhenDebuggerAttached(master.timeoutIfDebuggerAttached)
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixture: String get() {
        check(context.packageName == "com.librestatic.lightforge.feature.viewer.test")
        val raw = checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid"))
        check(UUID.fromString(raw).toString() == raw)
        return raw
    }
    private fun directory(id: String) = File(context.cacheDir.canonicalFile, "viewer-playback-$id")
    private fun receiptFile(id: String) = File(context.filesDir.canonicalFile, "viewer-playback-$id.json")

    @Test fun activePlaybackStopsAtHomeAndOnlyExplicitPlayResumes() {
        val id = fixture
        val dir = directory(id)
        check(!dir.exists() && !receiptFile(id).exists() && !File(receiptFile(id).path + ".bak").exists())
        val receipt = JSONObject().put("fixture", id).put("package", context.packageName)
            .put("directory", dir.absolutePath).put("phase", "before-create")
            .put("cleanupComplete", false).put("verified", false).put("files", JSONArray())
        save(id, receipt)
        check(dir.mkdir() && dir.canonicalFile == dir)
        var controller: VideoViewerController? = null
        var activity: Activity? = null
        var visible by mutableStateOf(true)
        var verified = false
        var contentInstalled = false
        try {
            val (source, durationUs) = createNineSecondFixture(dir, receipt)
            val durationMillis = durationUs / 1_000
            receipt.put("source", source.absolutePath).put("sourceSha256", hash(source))
                .put("durationMillis", durationMillis).put("phase", "fixture-ready")
            anchorFiles(dir, receipt)
            save(id, receipt)
            val videoFormat = readTrackFormats(source).single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val media = object : ViewerMedia {
                override val viewerId = "viewer-fixture:$id"
                override val mediaKey: MediaKey? = null
                override val kind = MediaKind.Video
                override val generationModified = 0L
                override val timelineSortMillis = 0L
                override val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
                override val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
                override val durationMillis = durationUs / 1_000
                override val isFavorite = false
                override val displayName = source.name
            }
            instrumentation.runOnMainSync {
                controller = VideoViewerController(context).also {
                    it.select(Uri.fromFile(source), autoplay = false, startMuted = false)
                }
            }
            val player = checkNotNull(controller)
            compose.setContent {
                LightforgeTheme {
                    if (visible) {
                        val host = LocalContext.current.activity()
                        SideEffect { activity = host }
                        ViewerContent(media = media, mediaItems = listOf(media), photoState = null,
                            videoController = player, thumbnailLoader = null, isFavorite = false,
                            onBack = {}, onToggleFavorite = null, onShare = { error("Unexpected share") },
                            onShareSanitized = null, onDetails = null, onEdit = null, onRename = null,
                            onCopy = null, onMove = null, onOpenWith = null, onSetAs = null,
                            onPrint = null, onRepairDate = null, onTrash = null,
                            onSelectMedia = { error("Unexpected media selection") })
                    }
                }
            }
            contentInstalled = true
            awaitSample(player, "initial-ready") { it.ready && !it.playing && it.duration in 8_500L..10_000L }
            val root = compose.onRoot().fetchSemanticsNode()
            val host = checkNotNull(activity)
            awaitStage(host, Stage.RESUMED)
            val taskId = host.taskId
            val pid = android.os.Process.myPid()
            receipt.put("taskId", taskId).put("pid", pid)
            click(root, context.getString(R.string.viewer_play))
            val early = awaitSample(player, "early-playing", 2_000) { it.playing && it.position >= 150 }
            receipt.put("early", early.json()).put("phase", "before-home")
            save(id, receipt)
            assertTrue("Home starts before half, not after natural end", early.position < durationMillis / 2)
            val homeOutput = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME"))
                .bufferedReader().use { it.readText() }
            receipt.put("homeShellOutput", homeOutput)
            assertEquals("", homeOutput.trim())
            awaitStage(host, Stage.STOPPED)
            receipt.put("homeStoppedObserved", true).put("stopSample", sample(player).json())
                .put("phase", "stopped")
            save(id, receipt)
            // Decoder time remains real. No Compose polling/capture while the host is STOPPED.
            val backgroundStarted = SystemClock.elapsedRealtime()
            Thread.sleep(durationMillis + 700)
            assertTrue(inStage(host, Stage.STOPPED))
            receipt.put("backgroundMillis", SystemClock.elapsedRealtime() - backgroundStarted)
                .put("backgroundSample", sample(player).json()).put("phase", "before-return")
            save(id, receipt)
            resume(host)
            awaitStage(host, Stage.RESUMED)
            assertSame(host, activity)
            assertEquals(taskId, host.taskId)
            assertEquals(pid, android.os.Process.myPid())
            val returned = sample(player)
            receipt.put("sameActivityTaskPidReturned", true).put("returned", returned.json())
                .put("phase", "returned-observed")
            save(id, receipt)
            assertTrue("Return must be Ready", returned.ready)
            assertFalse("Return must stay paused without user action", returned.playing)
            assertTrue("Return must preserve an intermediate position, not restart or natural end",
                returned.position > 0 && returned.position < durationMillis - 500)
            val stableStarted = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - stableStarted < 1_200) {
                val current = sample(player)
                assertTrue(current.ready)
                assertFalse(current.playing)
                assertEquals("Paused decoder position must remain fixed", returned.position, current.position)
                Thread.sleep(100)
            }
            receipt.put("stableMillis", SystemClock.elapsedRealtime() - stableStarted)
                .put("stable", sample(player).json()).put("phase", "stable-paused")
            save(id, receipt)
            click(root, context.getString(R.string.viewer_play))
            val advanced = awaitSample(player, "explicit-resume", 2_000) {
                it.playing && it.position >= returned.position + 250
            }
            receipt.put("explicitResume", advanced.json()).put("phase", "explicit-play-advanced")
            save(id, receipt)
            click(root, context.getString(R.string.viewer_pause))
            awaitSample(player, "explicit-pause") { it.ready && !it.playing }
            verifyFiles(dir, receipt)
            verified = true
            receipt.put("verified", true)
        } catch (failure: Throwable) {
            receipt.put("failure", failure.toString())
            throw failure
        } finally {
            try {
                activity?.takeIf { !it.isDestroyed && !it.isFinishing }?.let {
                    if (!inStage(it, Stage.RESUMED)) { resume(it); awaitStage(it, Stage.RESUMED) }
                }
                if (contentInstalled) {
                    instrumentation.runOnMainSync { visible = false }
                    compose.waitForIdle()
                }
            } finally {
                instrumentation.runOnMainSync { controller?.close() }
                // Only these two new UUID-local files are eligible for anchoring on setup failure.
                if (receipt.getJSONArray("files").length() == 0) anchorFiles(dir, receipt)
                receipt.put("phase", if (verified) "verified-retained" else "failed-retained")
                save(id, receipt)
                report(id, receipt.getString("phase"))
            }
        }
        // A failure deliberately retains all bytes for independent readback and explicit cleanup.
        cleanup(id, receipt)
        report(id, "PASS")
    }

    @Test fun cleanupOwnedPlaybackFixture() {
        val id = fixture
        val receipt = JSONObject(AtomicFile(receiptFile(id)).openRead().bufferedReader().use { it.readText() })
        cleanup(id, receipt)
    }

    private fun cleanup(id: String, receipt: JSONObject) {
        check(receipt.getString("fixture") == id && receipt.getString("package") == context.packageName)
        check(!receipt.getBoolean("cleanupComplete"))
        val dir = directory(id)
        check(receipt.getString("directory") == dir.absolutePath && dir.canonicalFile == dir)
        verifyFiles(dir, receipt)
        receipt.put("phase", "before-cleanup")
        save(id, receipt)
        val files = receipt.getJSONArray("files")
        repeat(files.length()) { index ->
            val item = files.getJSONObject(index)
            val file = File(dir, item.getString("name"))
            check(file.length() == item.getLong("size") && hash(file) == item.getString("sha256"))
            check(file.delete() && !file.exists())
        }
        check(dir.listFiles()!!.isEmpty() && dir.delete() && !dir.exists())
        receipt.put("cleanupComplete", true).put("phase", "cleanup-complete")
        save(id, receipt)
        report(id, "CLEANUP_COMPLETE")
    }
    private fun anchorFiles(dir: File, receipt: JSONObject) {
        check(dir.canonicalFile == dir)
        val files = dir.listFiles()!!.sortedBy { it.name }
        check(files.all { it.name in setOf("source.mp4", "repeated.mp4") })
        receipt.put("files", JSONArray(files.map { file ->
            check(file.canonicalFile == file && OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
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
            val item = files.getJSONObject(index)
            val file = File(dir, item.getString("name"))
            check(file.canonicalFile == file && OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
            check(file.length() == item.getLong("size") && hash(file) == item.getString("sha256"))
        }
    }
    private fun save(id: String, receipt: JSONObject) {
        val atomic = AtomicFile(receiptFile(id))
        val stream = atomic.startWrite()
        try {
            stream.write(receipt.toString(2).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        val descriptor = Os.open(context.filesDir.path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) } finally { Os.close(descriptor) }
        report(id, receipt.getString("phase"))
    }
    private data class Sample(val ready: Boolean, val playing: Boolean, val position: Long, val duration: Long) {
        fun json() = JSONObject().put("ready", ready).put("playing", playing)
            .put("positionMillis", position).put("durationMillis", duration)
    }
    private fun sample(controller: VideoViewerController): Sample {
        var result: Sample? = null
        instrumentation.runOnMainSync {
            val state = controller.state.value as? VideoViewerState.Ready
            result = Sample(state != null, state?.isPlaying == true, controller.currentPositionMillis(), state?.durationMillis ?: 0)
        }
        return checkNotNull(result)
    }
    private fun awaitSample(controller: VideoViewerController, label: String, timeout: Long = 5_000,
        predicate: (Sample) -> Boolean): Sample {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var value: Sample
        do {
            compose.mainClock.advanceTimeByFrame()
            value = sample(controller)
            if (predicate(value)) return value
            Thread.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("$label timed out: ${value.json()}")
    }
    private fun click(root: SemanticsNode, description: String) {
        compose.mainClock.advanceTimeBy(100)
        instrumentation.runOnMainSync {
            val matches = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                val config = node.config
                if (SemanticsProperties.ContentDescription in config &&
                    description in config[SemanticsProperties.ContentDescription] &&
                    SemanticsActions.OnClick in config) matches += node
                node.children.forEach(::visit)
            }
            root.children.forEach(::visit)
            val node = matches.single()
            check(SemanticsProperties.Disabled !in node.config)
            check(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        }
    }
    private fun Context.activity(): Activity = generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().first()
    private fun inStage(activity: Activity, stage: Stage): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { it === activity }
        }
        return found
    }
    private fun awaitStage(activity: Activity, stage: Stage) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!inStage(activity, stage) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Expected actual $stage for task=${activity.taskId}", inStage(activity, stage))
    }
    private fun resume(activity: Activity) {
        check(!activity.isDestroyed && !activity.isFinishing)
        context.startActivity(Intent().setComponent(activity.componentName).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) }
        }
        digest().joinToString("") { "%02x".format(it) }
    }
    private fun report(id: String, phase: String) = println("VIEWER_PLAYBACK $id $phase")

    /** Same verified three-pass remux used by Motion Home; no new asset, reencode or speed change. */
    private fun createNineSecondFixture(directory: File, receipt: JSONObject): Pair<File, Long> {
        val seed = File(directory, "source.mp4")
        val repeated = File(directory, "repeated.mp4")
            check(seed.createNewFile())
            instrumentation.context.assets.open("h264.mp4").use { input -> seed.outputStream().use { output -> input.copyTo(output) } }
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
