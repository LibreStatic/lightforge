package com.librestatic.lightforge.feature.semanticsearch

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SemanticRealModelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets get() = instrumentation.context.assets
    private val root get() = File(context.filesDir, "model-fixtures")
    private fun archive(model: SemanticModelDescriptor) = File(root, "${model.id}-${model.version}.ugmodel").also {
        check(InstrumentationRegistry.getArguments().getString("modelFixture") == "lightforge-local-models") { "Owned model fixture marker required" }
        check(it.isFile) { "Push signed catalog packages to ${root.absolutePath}" }
    }
    private fun installed(model: SemanticModelDescriptor): InstalledSemanticModel {
        val store = SemanticModelStorage(context)
        store.installVerified(model, archive(model))
        return requireNotNull(store.installedModel(model))
    }
    private fun evidence(name: String, json: JSONObject) {
        File(context.filesDir, "model-evidence").apply { mkdirs() }.resolve(name).writeText(json.toString(2))
    }
    private fun cosine(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()

    @Test fun bothSignedPacksExecuteRealEnglishSpanishContrastiveInference() {
        val report = JSONArray()
        val prompts = listOf("a photo of a cat", "a photo of a hamburger", "a photo of penguins",
            "una foto de un gato", "una hamburguesa", "unos pingüinos")
        for (descriptor in SemanticModelCatalog.models) {
            val before = Debug.getPss()
            val start = SystemClock.elapsedRealtime()
            val model = installed(descriptor)
            try {
                LiteRtSemanticEmbeddingInference(context, model).use { inference ->
                    val textTimings = JSONArray()
                    val textVectors = prompts.map { prompt ->
                        val started = SystemClock.elapsedRealtimeNanos()
                        inference.embedText(prompt).also {
                            textTimings.put((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                        }
                    }
                    val warmStarted = SystemClock.elapsedRealtimeNanos()
                    val warmVector = inference.embedText(prompts.first())
                    val warmTextMillis = (SystemClock.elapsedRealtimeNanos() - warmStarted) / 1_000_000.0
                    assertTrue(cosine(warmVector, textVectors.first()) > .999f)
                    val rows = JSONArray()
                    listOf("cat.jpg", "burger.jpg", "penguins_large.jpg").forEachIndexed { target, file ->
                        val bitmap = assets.open(file).use { BitmapFactory.decodeStream(it) }!!
                        val imageStarted = SystemClock.elapsedRealtimeNanos()
                        val vector = try { inference.embedImage(bitmap) } finally { bitmap.recycle() }
                        val imageMillis = (SystemClock.elapsedRealtimeNanos() - imageStarted) / 1_000_000.0
                        assertEquals(512, vector.size)
                        assertTrue(vector.all(Float::isFinite))
                        assertTrue(kotlin.math.abs(cosine(vector, vector) - 1f) < .01f)
                        val scores = textVectors.map { cosine(vector, it) }
                        assertEquals("$file English ranking", target, (0..2).maxBy { scores[it] })
                        assertEquals("$file Spanish ranking", target + 3, (3..5).maxBy { scores[it] })
                        val compact = CompactSemanticEmbedding.quantize(vector)
                        assertEquals(512, compact.size)
                        assertTrue(compact.any { it != 0.toByte() })
                        rows.put(JSONObject().put("file", file).put("scores", JSONArray(scores)).put("imageInferenceMs", imageMillis))
                    }
                    report.put(JSONObject().put("model", descriptor.id).put("packageSha", descriptor.packageSha256)
                        .put("rows", rows).put("textInferenceMsInPromptOrder", textTimings).put("repeatedFirstTextInferenceMs", warmTextMillis)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - start)
                        .put("pssBeforeKiB", before).put("pssAfterKiB", Debug.getPss()))
                }
            } finally { SemanticModelStorage(context).delete(descriptor) }
        }
        evidence("real-inference.json", JSONObject().put("status", "PASS").put("models", report))
    }

    @Test fun tokenizerMatchesOfficialClipGoldens() {
        val descriptor = SemanticModelCatalog.models.first()
        val model = installed(descriptor)
        try {
            val tokenizer = ClipTokenizer(model.vocabulary, model.merges)
            val goldens = JSONObject(assets.open("semantic-tokenizer-goldens.json").bufferedReader().use { it.readText() })
            goldens.keys().forEach { text ->
                val values = goldens.getJSONArray(text)
                assertArrayEquals(text, IntArray(values.length()) { values.getInt(it) }, tokenizer.encode(text))
            }
        } finally { SemanticModelStorage(context).delete(descriptor) }
    }

    @Test fun installedCorruptionIsRejectedDespiteUnchangedMarkerLengthAndTimestamp() {
        val descriptor = SemanticModelCatalog.models.first()
        val model = installed(descriptor)
        try {
            val before = model.imageModel.lastModified()
            RandomAccessFile(model.imageModel, "rw").use { file ->
                file.seek(4096); val byte = file.readByte(); file.seek(4096); file.writeByte(byte.toInt() xor 1)
            }
            model.imageModel.setLastModified(before)
            assertThrows(IllegalArgumentException::class.java) { LiteRtSemanticEmbeddingInference(context, model) }
            assertEquals(descriptor.packageSha256, model.directory.resolve("complete.marker").readText())
        } finally { SemanticModelStorage(context).delete(descriptor) }
    }

    @Test fun deleteRevokesLoadedNativeLeaseClosesBuffersAndAllowsVerifiedReinstall() {
        val descriptor = SemanticModelCatalog.models.first()
        val model = installed(descriptor)
        val inference = LiteRtSemanticEmbeddingInference(context, model)
        try {
            assertEquals(512, inference.embedText("a cat").size)
            SemanticModelStorage(context).delete(descriptor)
            assertFalse(model.directory.exists())
            assertThrows(IllegalStateException::class.java) { inference.embedText("a cat") }
            val replacement = installed(descriptor)
            LiteRtSemanticEmbeddingInference(context, replacement).use { assertEquals(512, it.embedText("a dog").size) }
        } finally { inference.close(); SemanticModelStorage(context).delete(descriptor) }
    }

    @Test fun alteredArchiveNeverActivatesOrChangesPreviouslyInstalledModel() {
        val descriptor = SemanticModelCatalog.models.first()
        val model = installed(descriptor)
        val bad = File(context.cacheDir, "bad-model-${UUID.randomUUID()}.ugmodel")
        try {
            archive(descriptor).copyTo(bad)
            RandomAccessFile(bad, "rw").use { it.seek(1024); val value = it.readByte(); it.seek(1024); it.writeByte(value.toInt() xor 1) }
            assertThrows(IllegalArgumentException::class.java) { SemanticModelStorage(context).installVerified(descriptor, bad) }
            SemanticModelIntegrity.verify(model)
        } finally { bad.delete(); SemanticModelStorage(context).delete(descriptor) }
    }

    @Test fun cancellationDisconnectsRegisteredConnectionAndRejectsLateRegistration() {
        class Connection : HttpURLConnection(URL("https://github.com/")) {
            var disconnected = false
            override fun disconnect() { disconnected = true }
            override fun usingProxy() = false
            override fun connect() = Unit
        }
        val cancellation = SemanticDownloadCancellation()
        val active = Connection(); cancellation.register(active); cancellation.cancel()
        assertTrue(active.disconnected)
        val late = Connection()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { cancellation.register(late) }
        assertTrue(late.disconnected)
    }
    @Test fun deletionDuringInstallationPreventsLatePublication() {
        val root = File(context.cacheDir, "publication-${UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(root, "model")
        val staging = File(root, "staging").apply { mkdirs(); resolve("owned.txt").writeText("verified staging") }
        try {
            val epoch = SemanticModelAccess.epoch(destination)
            SemanticModelAccess.revoke(destination)
            assertThrows(IllegalStateException::class.java) { SemanticModelAccess.publish(destination, staging, epoch) }
            assertFalse(destination.exists())
            assertEquals("verified staging", staging.resolve("owned.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun publicationRenameFailureRestoresPreviousBytesAndEpoch() {
        val root = File(context.cacheDir, "publication-${UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(root, "model").apply { mkdirs(); resolve("owned.txt").writeText("previous bytes") }
        try {
            val epoch = SemanticModelAccess.epoch(destination)
            assertThrows(IllegalStateException::class.java) {
                SemanticModelAccess.publish(destination, File(root, "missing-staging"), epoch)
            }
            assertEquals("previous bytes", destination.resolve("owned.txt").readText())
            assertEquals(epoch, SemanticModelAccess.epoch(destination))
            assertEquals(listOf("model"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledSignedInstallationLeavesPreviousRealModelUsable() {
        val descriptor = SemanticModelCatalog.models.first()
        val model = installed(descriptor)
        try {
            val cancellation = SemanticDownloadCancellation().apply { cancel() }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                SemanticModelStorage(context).installVerified(descriptor, archive(descriptor), cancellation)
            }
            LiteRtSemanticEmbeddingInference(context, model).use { assertEquals(512, it.embedText("a photo of a cat").size) }
            SemanticModelIntegrity.verify(model)
        } finally { SemanticModelStorage(context).delete(descriptor) }
    }

    @Test fun verifiedPublicationAcknowledgesOldDownloadFailureButNotNewRequest() {
        val descriptor = SemanticModelCatalog.models.first()
        val work = androidx.work.WorkManager.getInstance(context)
        val unique = SemanticModelDownloadWorker.uniqueName(descriptor.id)
        fun failedRequest(): java.util.UUID {
            val request = androidx.work.OneTimeWorkRequestBuilder<SemanticReceiptFailureWorker>().build()
            work.enqueueUniqueWork(unique, androidx.work.ExistingWorkPolicy.REPLACE, request).result.get()
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    work.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
                }
            }
            assertEquals(androidx.work.WorkInfo.State.FAILED, work.getWorkInfoById(request.id).get()?.state)
            return request.id
        }
        val store = SemanticModelStorage(context)
        try {
            val previous = failedRequest()
            installed(descriptor)
            assertTrue(store.supersedesDownloadFailure(descriptor, previous.toString()))
            val later = failedRequest()
            assertFalse(store.supersedesDownloadFailure(descriptor, later.toString()))
            assertTrue(store.supersedesDownloadFailure(descriptor, previous.toString()))
            SemanticModelIntegrity.verify(requireNotNull(store.installedModel(descriptor)))
        } finally { store.delete(descriptor); work.cancelUniqueWork(unique).result.get() }
    }

}


/** Deterministic transport failure only; embeddings always use the real signed native models. */
class SemanticReceiptFailureWorker(context: android.content.Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(context, params) {
    override fun doWork(): Result = Result.failure(androidx.work.Data.Builder().putString(SemanticModelDownloadWorker.KeyError, "owned-download-attempt-failure").build())
}
