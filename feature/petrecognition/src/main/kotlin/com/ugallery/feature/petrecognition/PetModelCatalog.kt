package com.ugallery.feature.petrecognition

import android.os.CancellationSignal
import java.io.File
import java.security.MessageDigest

object PetModelCatalog {
    const val Fingerprint = "open-noodle-small-9dd4c915-effdet0-40338edf-v1"
    const val RecognitionSha256 = "6a5e2373ab348bed588cef4072f3914ca9c8bacde3e8d0651019e8dad86b24ba"
    const val DetectorSha256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
    const val RecognitionBytes = 89_227_604L
    const val DetectorBytes = 13_836_895L
    const val RecognitionUrl = "https://huggingface.co/open-noodle/pet-recognition-small/resolve/9dd4c915be29a81b116b3e30eb996c59d0e7ede0/recognition/model.onnx"
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
