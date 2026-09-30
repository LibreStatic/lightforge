package com.librestatic.lightforge.feature.petrecognition

import android.os.CancellationSignal
import java.io.File
import java.security.MessageDigest

object PetModelCatalog {
    const val Fingerprint = "open-noodle-small-9dd4c915-effdet0-40338edf-v1"
    const val RecognitionSha256 = "63f88741ce15406e90f6ce2194f4fc5018345f03cb408c97405a714ae1cc5759"
    const val DetectorSha256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
    const val RecognitionBytes = 43_832_152L
    const val DetectorBytes = 13_836_895L
    // LiteRT fp16 conversion of open-noodle/pet-recognition-small@9dd4c915 recognition/model.onnx; embeddings
    // match the ONNX original (cosine >= 0.9999), so the fingerprint and stored analyses stay valid.
    const val RecognitionUrl = "https://github.com/LibreStatic/lightforge-models/releases/download/pet-recognition-small-tflite-v1/pet-recognition-small-fp16.tflite"
    const val DetectorUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite"

    fun verify(file: File, expectedBytes: Long, expectedSha256: String, signal: CancellationSignal = CancellationSignal()) {
        require(file.isFile && file.length() == expectedBytes) { "Pet model length mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                signal.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedSha256) { "Pet model integrity mismatch" }
    }
}

data class PetModelFiles(val detector: File, val recognition: File) {
    fun verify(signal: CancellationSignal = CancellationSignal()) {
        PetModelCatalog.verify(detector, PetModelCatalog.DetectorBytes, PetModelCatalog.DetectorSha256, signal)
        PetModelCatalog.verify(recognition, PetModelCatalog.RecognitionBytes, PetModelCatalog.RecognitionSha256, signal)
    }
}
