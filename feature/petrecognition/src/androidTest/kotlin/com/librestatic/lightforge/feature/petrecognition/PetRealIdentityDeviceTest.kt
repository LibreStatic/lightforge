package com.librestatic.lightforge.feature.petrecognition

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import android.os.Debug
import android.os.OperationCanceledException
import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class PetRealIdentityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(): File {
        check(InstrumentationRegistry.getArguments().getString("petFixture") == "lightforge-pet-models")
        return File(context.filesDir, "pet-fixtures")
    }
    private fun models() = PetModelFiles(File(fixture(), "efficientdet_lite0.tflite"), File(fixture(), "pet-recognition-small.onnx"))
    private fun image(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    private fun sha(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128*1024)
        while (true) { val read = stream.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun evidence(name: String, value: JSONObject) {
        File(context.filesDir, "pet-evidence").apply { mkdirs() }.resolve(name).writeText(value.toString(2))
    }

    @Test fun realMultiAnimalDetectorAndIndividualEncoderKeepIndependentBoxesAndRejectFood() {
        val rows = JSONArray()
        PetRecognitionEngine(context, models()).use { engine ->
            assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"))
            for (name in listOf("cats_and_dogs.jpg", "burger.jpg", "cat.jpg")) {
                val file = File(fixture(), name); val before = sha(file); val bitmap = image(file)
                try {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val observations = engine.analyze(bitmap)
                    val result = JSONArray()
                    observations.forEach { o ->
                        assertEquals(512, o.embedding.size)
                        result.put(JSONObject().put("species", o.species.name).put("detectorScore", o.detectorScore)
                            .put("box", JSONArray(listOf(o.box.left, o.box.top, o.box.right, o.box.bottom))))
                    }
                    if (name == "cats_and_dogs.jpg") {
                        assertTrue("Real separate pet bounding boxes required", observations.size >= 2)
                        for (i in observations.indices) for (j in i+1 until observations.size)
                            assertTrue(PetDetectionDecoder.overlap(observations[i].box, observations[j].box) <= .5f)
                    }
                    if (name == "burger.jpg") assertTrue("Food must not create a pet identity", observations.isEmpty())
                    if (name == "cat.jpg") {
                        assertTrue(observations.isNotEmpty())
                        assertTrue("Recorded detector/refiner disagreement must require explicit review", observations.any { it.species == PetSpecies.Uncertain })
                    }
                    rows.put(JSONObject().put("file",name).put("detections",result)
                        .put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0).put("sourceSha256",before))
                } finally { bitmap.recycle() }
                assertEquals(before,sha(file))
            }
        }
        evidence("real-detection.json",JSONObject().put("status","PASS").put("rows",rows))
    }

    @Test fun actualCatAndDogIdentityCorpusProducesMeasuredSameDifferentScoresWithoutFakeVectors() {
        val manifest = JSONArray(File(fixture(), "pet-corpus-manifest.json").readText())
        val vectors = mutableListOf<FloatArray>(); val rows = mutableListOf<JSONObject>()
        val memoryBefore = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        var memoryAfter = memoryBefore
        PetRecognitionEngine(context, models()).use { engine ->
            for (i in 0 until manifest.length()) {
                val row = manifest.getJSONObject(i); val file = File(fixture(), "corpus/${row.getString("file")}")
                assertEquals(row.getString("sha256"), sha(file))
                val bitmap = image(file)
                try {
                    val started = SystemClock.elapsedRealtimeNanos()
                    vectors += engine.embedCrop(bitmap)
                    rows += JSONObject(row.toString()).put("inferenceMs",(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0)
                } finally { bitmap.recycle() }
            }
            memoryAfter = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        }
        val metrics = JSONObject()
        for (species in listOf("Cat","Dog")) {
            val indices = rows.indices.filter { rows[it].getString("species") == species }
            assertTrue(indices.size >= 16)
            val genuine = mutableListOf<Double>(); val impostor = mutableListOf<Double>(); var correct = 0
            fun score(i: Int,j: Int) = vectors[i].indices.sumOf { (vectors[i][it] * vectors[j][it]).toDouble() }
            for ((position,i) in indices.withIndex()) {
                val nearest = indices.filter { it != i }.maxBy { score(i,it) }
                if (rows[i].getString("identity") == rows[nearest].getString("identity")) correct++
                for (j in indices.drop(position+1)) {
                    if (rows[i].getString("identity") == rows[j].getString("identity")) genuine += score(i,j) else impostor += score(i,j)
                }
            }
            assertTrue("Same-individual signal must exceed different-individual signal on real corpus", genuine.average() > impostor.average() + .1)
            assertTrue("Identity retrieval must beat chance by a wide margin", correct.toDouble()/indices.size >= .5)
            val thresholds = JSONArray()
            for (threshold in listOf(.65,.70,.75,.80,.85,.90,.95)) thresholds.put(JSONObject().put("threshold",threshold)
                .put("FMR",impostor.count { it >= threshold }.toDouble()/impostor.size)
                .put("FNMR",genuine.count { it < threshold }.toDouble()/genuine.size))
            metrics.put(species,JSONObject().put("images",indices.size).put("genuinePairs",genuine.size)
                .put("impostorPairs",impostor.size).put("top1",correct.toDouble()/indices.size)
                .put("genuineMean",genuine.average()).put("impostorMean",impostor.average()).put("thresholds",thresholds)
                .put("trainingMembership",if(species=="Cat") "unknown; functional corpus only" else "evaluation-only according to model author"))
        }
        evidence("real-identity-metrics.json",JSONObject().put("status","PASS").put("modelFingerprint",PetModelCatalog.Fingerprint)
            .put("metrics",metrics).put("rows",JSONArray(rows)).put("pssBeforeKiB",memoryBefore).put("pssAfterKiB",memoryAfter)
            .put("pssIsPeak",false).put("automaticIdentityAssignment",false))
    }

    @Test fun changedModelBytesAndCancellationAreRejectedBeforeNativeParsing() {
        val original = models(); val changed = File(context.cacheDir,"pet-corrupt-${java.util.UUID.randomUUID()}.onnx")
        try {
            original.recognition.copyTo(changed)
            RandomAccessFile(changed,"rw").use { file -> file.seek(4096); val byte=file.readByte(); file.seek(4096); file.writeByte(byte.toInt() xor 1) }
            assertThrows(IllegalArgumentException::class.java) { PetRecognitionEngine(context,PetModelFiles(original.detector,changed)) }
            val cancelled = CancellationSignal().apply { cancel() }
            assertThrows(OperationCanceledException::class.java) { PetRecognitionEngine(context,original,cancelled) }
            assertEquals(PetModelCatalog.RecognitionSha256,sha(original.recognition))
        } finally { changed.delete() }
    }
    @Test fun verifiedPackImportAndDeletionInvalidateLiveNativeSessionWithoutChangingSourceModels() {
        val pack = File(context.cacheDir,"pet-pack-${java.util.UUID.randomUUID()}.zip")
        val store = PetModelStore(context)
        val sources = models()
        // This test package owns its private model namespace; never remove a user's application pack.
        check(context.packageName == "com.librestatic.lightforge.feature.petrecognition.test")
        try {
            java.util.zip.ZipOutputStream(pack.outputStream()).use { zip ->
                for ((name,file) in listOf("detector.tflite" to sources.detector,"recognition.onnx" to sources.recognition)) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); file.inputStream().use { it.copyTo(zip,128*1024) }; zip.closeEntry()
                }
            }
            val cancellation=CancellationSignal()
            assertThrows(Exception::class.java) {
                store.importPack(android.net.Uri.fromFile(pack),cancellation) { if(it > .03f) cancellation.cancel() }
            }
            assertTrue(cancellation.isCanceled)
            assertFalse(store.installed())
            assertTrue(File(context.filesDir,"pet-models").listFiles().orEmpty().none { it.name.startsWith(".install-") })
            store.importPack(android.net.Uri.fromFile(pack))
            assertTrue(store.installed())
            val bitmap = Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888)
            try {
                store.openEngine().use { engine ->
                    assertEquals(512,engine.embedCrop(bitmap).size)
                    store.delete()
                    assertFalse(store.installed())
                    assertThrows(IllegalStateException::class.java) { engine.embedCrop(bitmap) }
                }
            } finally { bitmap.recycle() }
            assertEquals(PetModelCatalog.RecognitionSha256,sha(sources.recognition))
            assertEquals(PetModelCatalog.DetectorSha256,sha(sources.detector))
        } finally { store.delete(); pack.delete() }
    }

    @Test fun duplicateAndIncompleteLocalPacksNeverActivate() {
        val store=PetModelStore(context)
        check(context.packageName == "com.librestatic.lightforge.feature.petrecognition.test")
        val pack=File(context.cacheDir,"pet-invalid-${java.util.UUID.randomUUID()}.zip")
        try {
            java.util.zip.ZipOutputStream(pack.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("../recognition.onnx")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
            }
            var traversalRejected = false
            try { store.importPack(android.net.Uri.fromFile(pack)) }
            catch (_: IllegalArgumentException) { traversalRejected = true }
            catch (_: java.util.zip.ZipException) { traversalRejected = true } // Android 14+ rejects before the importer sees this entry.
            assertTrue("Traversal is rejected by the importer or the platform ZIP validator", traversalRejected)
            assertFalse(store.installed())
            java.util.zip.ZipOutputStream(pack.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("recognition.onnx")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { store.importPack(android.net.Uri.fromFile(pack)) }
            assertFalse(store.installed())
            assertTrue(File(context.filesDir,"pet-models").listFiles().orEmpty().none { it.name.startsWith(".install-") })
        } finally { pack.delete() }
    }

    @Test fun aColdProcessManifestHasNoEagerTelemetryProviderAndEngineDisablesNativeTelemetry() {
        @Suppress("DEPRECATION")
        val providers = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue("Eager ORT telemetry provider must be removed before process startup", providers.none { it.name == "ai.onnxruntime.TelemetryInitializer" })
        PetRecognitionEngine(context, models()).use {
            assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"))
        }
        evidence("telemetry-disabled.json", JSONObject().put("status","PASS")
            .put("package",context.packageName).put("eagerTelemetryProviderAbsent",true)
            .put("ORT_DISABLE_TELEMETRY",Os.getenv("ORT_DISABLE_TELEMETRY")))
    }

}
