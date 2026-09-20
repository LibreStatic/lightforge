package com.ugallery.feature.motionphotos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.IdlingPolicies
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Real Home/STOP/return while Motion is playing. Same Activity/task/PID, not recreation or process death. */
class MotionPhotoPlaybackLifecycleDeviceTest {
    // Cooperative Espresso errors unwind this test and its normal cleanup; no abandoned timeout thread.
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

    @Test fun earlyPlaybackStopsAtHomeAndExplicitPlayResumesIntermediateFrame() {
        val fixture = UUID.randomUUID().toString()
        val evidence = File(context.filesDir, "motion-playback-lifecycle-$fixture").apply { check(mkdir()) }
        val playbackFixture = createNineSecondFixture(fixture, evidence)
        val source = playbackFixture.first
        val sourceHash = MotionPhotoSession.hash(source)
        val durationUs = playbackFixture.second
        check(durationUs in 8_500_000L..10_000_000L)
        val durationMillis = durationUs / 1000
        val beforeOutputs = outputs()
        val journal = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications"))
        val beforeJournal = journal.listEntries().sortedBy { it.id }
        File(evidence, "fixture.json").writeText(JSONObject().put("fixture", fixture).put("source", source.canonicalPath)
            .put("sha256", sourceHash).put("durationUs", durationUs).toString(2))
        report("fixture=$fixture source=${source.canonicalPath} sha256=$sourceHash evidence=${evidence.path}")
        var visible by mutableStateOf(true)
        var activity: Activity? = null
        var passed = false
        var cleaned = false
        var stoppedObserved = false
        var sameActivityReturned = false
        var pausedMillis: Long? = null
        var pausedPreview: String? = null
        var backgroundMillis = 0L
        var stableMillis = 0L
        var explicitPlayAdvanced = false
        val flowStartedWall = SystemClock.elapsedRealtime()
        try {
            compose.setContent {
                UGalleryTheme {
                    if (visible) {
                        val host = LocalContext.current.activity()
                        SideEffect { activity = host }
                        MotionPhotoContent(input = MotionPhotoFixtures.input(source), keyFrameTimeUs = null,
                            onSetKeyFrame = { _, _ -> error("Playback must not save a keyframe") }, onBack = {},
                            onOpen = { _, _, _ -> error("Playback must not hand off a result") },
                            onShare = { _, _, _ -> error("Playback must not hand off a result") })
                    }
                }
            }
            awaitTag("motion-frame-5")
            val host = checkNotNull(activity)
            awaitStage(host, Stage.RESUMED)
            val taskId = host.taskId
            val pid = android.os.Process.myPid()
            click("motion-frame-0")
            compose.onNodeWithTag("motion-frame-0").assertIsSelected()
            assertEquals(0L, positionMillis())
            // Acquire the real host tree before playback. Afterwards sample fresh descendants on
            // main without Espresso idle rounds, which can consume half this three-second clip.
            val playbackRoot = compose.onRoot().fetchSemanticsNode()
            val playStartedWall = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                val play = playbackNodes(playbackRoot).getValue("motion-play")
                check(SemanticsProperties.Disabled !in play.config)
                check(checkNotNull(play.config[SemanticsActions.OnClick].action).invoke())
            }
            var beforeHomeSample = false to 0L
            val earlyDeadline = SystemClock.elapsedRealtime() + 2_000
            do {
                // Pump Compose's test-owned frame clock only so labels recompose. The media
                // decoder/player and elapsedRealtime deadlines continue on actual Android time.
                compose.mainClock.advanceTimeByFrame()
                instrumentation.runOnMainSync { beforeHomeSample = playbackSample(playbackRoot) }
                if (beforeHomeSample.first && beforeHomeSample.second >= 150) break
                Thread.sleep(20)
            } while (SystemClock.elapsedRealtime() < earlyDeadline)
            report("early-observation sample=$beforeHomeSample realElapsedMillis=${SystemClock.elapsedRealtime() - playStartedWall}")
            assertTrue("Actual Play must advance before Home", beforeHomeSample.first && beforeHomeSample.second >= 150)
            val beforeHome = beforeHomeSample.second
            report("beforeHomeMillis=$beforeHome playing=${beforeHomeSample.first} task=$taskId pid=$pid; this is not the STOP timestamp")
            assertTrue("Home must start early, not after the natural end", beforeHome in 150 until durationMillis / 2)
            val result = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME"))
                .bufferedReader().use { it.readText().trim() }
            assertEquals("", result)
            awaitStage(host, Stage.STOPPED)
            stoppedObserved = true
            val backgroundStarted = SystemClock.elapsedRealtime()
            // No Compose capture/idle calls while the real host is STOPPED. Stay out longer than the entire clip.
            Thread.sleep(durationMillis + 700)
            assertTrue(inStage(host, Stage.STOPPED))
            backgroundMillis = SystemClock.elapsedRealtime() - backgroundStarted
            assertTrue(backgroundMillis > durationMillis)
            resume(host)
            awaitStage(host, Stage.RESUMED)
            assertSame(host, activity); assertEquals(taskId, host.taskId); assertEquals(pid, android.os.Process.myPid())
            sameActivityReturned = true
            awaitPlaying(false)
            val paused = positionMillis()
            pausedMillis = paused
            report("returned-position pausedMillis=$paused durationMillis=$durationMillis realElapsedMillis=${SystemClock.elapsedRealtime() - flowStartedWall} backgroundMillis=$backgroundMillis")
            assertTrue("Returned position must be intermediate, not restarted or naturally ended", paused > 0 && paused < durationMillis - 100)
            val pausedText = text("motion-time")
            val selected = selection()
            awaitTag("motion-selected-frame")
            positionPreview()
            // The STOP timestamp is sampled only after return; asynchronous frame decode may finish before its image settles.
            var lastPreview = previewHash()
            var repeats = 0
            compose.waitUntil(5_000) {
                val current = previewHash()
                repeats = if (current == lastPreview) repeats + 1 else 0
                lastPreview = current
                repeats >= 2
            }
            pausedPreview = lastPreview
            val stableStarted = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - stableStarted < 1_200) {
                assertFalse(isPlaying())
                assertEquals(pausedText, text("motion-time"))
                assertEquals(selected, selection())
                Thread.sleep(150)
            }
            stableMillis = SystemClock.elapsedRealtime() - stableStarted
            assertEquals(pausedPreview, previewHash())
            report("returnedPausedMillis=$paused previewSha256=$pausedPreview selected=$selected backgroundMillis=$backgroundMillis stableMillis=$stableMillis")
            instrumentation.runOnMainSync {
                val play = playbackNodes(playbackRoot).getValue("motion-play")
                check(checkNotNull(play.config[SemanticsActions.OnClick].action).invoke())
            }
            var resumeSample = false to paused
            val resumeDeadline = SystemClock.elapsedRealtime() + 2_000
            do {
                // Drive the UI's 100ms polling cadence in one scheduler call, not repeated
                // Espresso synchronization; this never seeks/advances the real media player.
                compose.mainClock.advanceTimeBy(100)
                instrumentation.runOnMainSync { resumeSample = playbackSample(playbackRoot) }
                if (resumeSample.first && resumeSample.second >= paused + 250) break
                Thread.sleep(20)
            } while (SystemClock.elapsedRealtime() < resumeDeadline)
            report("explicit-resume sample=$resumeSample pausedMillis=$paused")
            assertTrue("Explicit Play must advance at least 250ms while playing", resumeSample.first && resumeSample.second >= paused + 250)
            explicitPlayAdvanced = true
            instrumentation.runOnMainSync {
                check(playbackSample(playbackRoot).first)
                val pause = playbackNodes(playbackRoot).getValue("motion-play")
                check(checkNotNull(pause.config[SemanticsActions.OnClick].action).invoke())
            }
            awaitPlaying(false)
            assertTrue(positionMillis() > paused)
            assertEquals(sourceHash, MotionPhotoSession.hash(source))
            assertEquals(beforeOutputs, outputs())
            assertEquals(beforeJournal, journal.listEntries().sortedBy { it.id })
            passed = true
        } finally {
            try {
                activity?.takeIf { !it.isDestroyed && !it.isFinishing }?.let { host ->
                    if (!inStage(host, Stage.RESUMED)) { resume(host); awaitStage(host, Stage.RESUMED) }
                }
                instrumentation.runOnMainSync { visible = false }
                compose.waitForIdle()
                if (passed) {
                    assertEquals(sourceHash, MotionPhotoSession.hash(source))
                    assertEquals(beforeOutputs, outputs())
                    assertEquals(beforeJournal, journal.listEntries().sortedBy { it.id })
                    check(source.delete() && !source.exists())
                    cleaned = true
                    report("PASS fixture=$fixture sourceHash=$sourceHash outputsUnchanged=true journalUnchanged=true cleanupComplete=true")
                } else report("FAIL retained fixture=$fixture source=${source.canonicalPath} sha256=$sourceHash")
            } finally {
                File(evidence, "result.json").writeText(JSONObject().put("fixture", fixture)
                    .put("status", if (passed && cleaned) "PASS" else "FAIL").put("homeStoppedObserved", stoppedObserved)
                    .put("sameActivityTaskPidReturned", sameActivityReturned).put("backgroundMillis", backgroundMillis)
                    .put("pausedMillis", pausedMillis).put("pausedPreviewSha256", pausedPreview).put("stableMillis", stableMillis)
                    .put("explicitPlayAdvanced", explicitPlayAdvanced).put("sourceSha256", sourceHash)
                    .put("outputsUnchanged", passed).put("journalUnchanged", passed).put("cleanupComplete", cleaned).toString(2))
            }
        }
    }

    /** Remux exactly three copies: unchanged compressed tracks and playback rate, longer real extent. */
    private fun createNineSecondFixture(id: String, evidence: File): Pair<File, Long> {
        val directory = File(context.cacheDir, "motion-home-remux-$id")
        check(directory.mkdir())
        val seed = File(directory, "source.mp4")
        val repeated = File(directory, "repeated.mp4")
        val source = File(context.cacheDir, "motion-home-nine-$id.jpg")
        report("remux-fixture id=$id temporary=${directory.canonicalPath} source=${source.canonicalPath}")
        try {
            check(seed.createNewFile())
            instrumentation.context.assets.open("motion_fixture.mp4").use { input -> seed.outputStream().use { output -> input.copyTo(output) } }
            check(seed.length() in 1..1_048_576)
            val seedHash = MotionPhotoSession.hash(seed)
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
            report("remux-timing originUs=$timestampOriginUs spanUs=$spanUs")
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
            val remuxHash = MotionPhotoSession.hash(repeated)
            check(repeated.length() in 1..4_194_304)
            val clip = repeated.readBytes()
            val padding = 7
            val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:C="http://ns.google.com/photos/1.0/camera/" xmlns:G="http://ns.google.com/photos/1.0/container/" xmlns:I="http://ns.google.com/photos/1.0/container/item/"><rdf:RDF><rdf:Description C:MotionPhoto="1" C:MotionPhotoVersion="1" C:MotionPhotoPresentationTimestampUs="1000000"><G:Directory><rdf:Seq><rdf:li><G:Item I:Semantic="Primary" I:Mime="image/jpeg" I:Length="0" I:Padding="$padding"/></rdf:li><rdf:li><G:Item I:Semantic="MotionPhoto" I:Mime="video/mp4" I:Length="${clip.size}"/></rdf:li></rdf:Seq></G:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            val jpeg = try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray() }
                finally { bitmap.recycle() }
            val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
            val header = byteArrayOf(-1, -31) + ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() + payload
            check(source.createNewFile())
            source.writeBytes(jpeg.copyOfRange(0, 2) + header + jpeg.copyOfRange(2, jpeg.size) + ByteArray(padding) + clip)
            File(evidence, "remux.json").writeText(JSONObject().put("fixture", id).put("repetitions", 3)
                .put("seedSha256", seedHash).put("seedPath", seed.canonicalPath).put("remuxSha256", remuxHash)
                .put("remuxPath", repeated.canonicalPath).put("spanUs", spanUs).put("timestampOriginUs", timestampOriginUs).put("durationUs", durationUs)
                .put("trackCount", formats.size).put("samplesPerRepetition", org.json.JSONArray(counts.toList()))
                .put("packetHashesPerRepetition", org.json.JSONArray(checkNotNull(packetHashes))).put("source", source.canonicalPath)
                .put("sourceSha256", MotionPhotoSession.hash(source)).toString(2))
            check(MotionPhotoSession.hash(seed) == seedHash && MotionPhotoSession.hash(repeated) == remuxHash)
            check(seed.delete() && !seed.exists())
            check(repeated.delete() && !repeated.exists())
            check(directory.listFiles()!!.isEmpty() && directory.delete() && !directory.exists())
            report("remux-ready durationUs=$durationUs spanUs=$spanUs tracks=${formats.size} samples=${counts.toList()} temporaryCleanupComplete=true")
            return source to durationUs
        } catch (failure: Throwable) {
            report("remux-failed retained temporary=${directory.canonicalPath} source=${source.canonicalPath}")
            throw failure
        }
    }
    private fun readTrackFormats(file: File): List<MediaFormat> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
        } finally { extractor.release() }
    }

    private fun Context.activity(): Activity = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
    private fun inStage(activity: Activity, stage: Stage): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { it === activity } }
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
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) { awaitTag(tag); compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
    private fun playbackNodes(root: SemanticsNode): Map<String, SemanticsNode> {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val found = mutableMapOf<String, SemanticsNode>()
        fun visit(node: SemanticsNode) {
            val config = node.config
            if (SemanticsProperties.TestTag in config) {
                val tag = config[SemanticsProperties.TestTag]
                if (tag == "motion-play" || tag == "motion-time") check(found.put(tag, node) == null)
            }
            node.children.forEach(::visit)
        }
        // children obtains current descendant semantics; never reuse a pre-Play label/config.
        root.children.forEach(::visit)
        check(found.keys == setOf("motion-play", "motion-time"))
        return found
    }
    private fun playbackSample(root: SemanticsNode): Pair<Boolean, Long> {
        val labels = playbackNodes(root).mapValues { (_, node) ->
            node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
        }
        val playing = labels.getValue("motion-play").contains(context.getString(R.string.motion_pause))
        val position = Regex("[0-9]+").find(labels.getValue("motion-time"))!!.value.toLong()
        return playing to position
    }
    private fun isPlaying() = text("motion-play").contains(context.getString(R.string.motion_pause))
    private fun awaitPlaying(expected: Boolean) = compose.waitUntil(5_000) { isPlaying() == expected }
    private fun positionMillis(): Long = Regex("[0-9]+").find(text("motion-time"))!!.value.toLong()
    private fun selection(): Set<Int> = (0..5).filter { index ->
        compose.onNodeWithTag("motion-frame-$index").fetchSemanticsNode().config[SemanticsProperties.Selected]
    }.toSet()
    private fun positionPreview() {
        report("preview-position begin")
        val container = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("motion-selected-frame")))
        val offset = container.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        check(offset.isFinite() && offset >= 0f)
        if (offset > 0f) {
            report("preview-position scrollBy=${-offset}")
            container.performSemanticsAction(SemanticsActions.ScrollBy) { action -> check(action(0f, -offset)) }
        }
        compose.onNodeWithTag("motion-selected-frame").assertIsDisplayed()
        report("preview-position complete")
    }
    private fun previewHash(): String {
        report("preview-display-capture begin")
        val target = compose.onNodeWithTag("motion-selected-frame").assertIsDisplayed()
        val before = target.fetchSemanticsNode()
        val size = before.size
        val rootPosition = before.positionInRoot
        val screenPosition = before.positionOnScreen
        val visibleBounds = before.boundsInRoot
        check(size.width > 0 && size.height > 0 && screenPosition.x.isFinite() && screenPosition.y.isFinite())
        check(abs(visibleBounds.left - rootPosition.x) <= 0.01f &&
            abs(visibleBounds.top - rootPosition.y) <= 0.01f &&
            abs(visibleBounds.right - rootPosition.x - size.width) <= 0.01f &&
            abs(visibleBounds.bottom - rootPosition.y - size.height) <= 0.01f) {
            "Full Motion preview is clipped: root=$rootPosition size=$size visible=$visibleBounds"
        }
        val left = screenPosition.x.roundToInt()
        val top = screenPosition.y.roundToInt()
        // Do not substitute a smaller visible region or a source bitmap for this exact display crop.
        check(abs(screenPosition.x - left) <= 0.01f && abs(screenPosition.y - top) <= 0.01f) {
            "Motion preview is not aligned to display pixels: $screenPosition"
        }
        val display = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Display screenshot unavailable" }
        try {
            check(left >= 0 && top >= 0 && size.width <= display.width - left && size.height <= display.height - top) {
                "Full Motion preview $screenPosition/$size outside display ${display.width}x${display.height}"
            }
            val after = target.fetchSemanticsNode()
            check(screenPosition == after.positionOnScreen && rootPosition == after.positionInRoot &&
                size == after.size && visibleBounds == after.boundsInRoot) { "Motion preview moved during display capture" }
            val bitmap = Bitmap.createBitmap(display, left, top, size.width, size.height)
            return try {
                val buffer = ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(buffer)
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(ByteBuffer.allocate(8).putInt(bitmap.width).putInt(bitmap.height).array())
                val hash = digest.digest(buffer.array()).joinToString("") { byte -> "%02x".format(byte) }
                report("preview-display-capture complete rect=[$left,$top,${left + size.width},${top + size.height}] sha256=$hash")
                hash
            } finally { if (bitmap !== display) bitmap.recycle() }
        } finally { display.recycle() }
    }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    private fun outputs(): List<List<String?>> = listOf("images" to "Pictures/UGallery/Motion/", "video" to "Movies/UGallery/Motion/").flatMap { (collection, path) ->
        val columns = arrayOf("_id", "owner_package_name", "_display_name", "relative_path", "mime_type",
            "generation_added", "generation_modified", "_size", "is_pending", "is_trashed")
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "owner_package_name=? AND relative_path=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, path))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        checkNotNull(context.contentResolver.query(Uri.parse("content://media/external/$collection/media"), columns, query, null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(listOf(collection) + columns.indices.map { cursor.getString(it) }) }
        }
    }
}
