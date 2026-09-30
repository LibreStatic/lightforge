package com.librestatic.lightforge.feature.semanticsearch

import java.io.File
import java.security.MessageDigest

/** File pins derived from the verified, signed catalog archives, not their local marker. */
internal object SemanticModelIntegrity {
    data class Pin(val bytes: Long, val sha256: String)
    fun pins(model: SemanticModelDescriptor): Map<String, Pin> {
        require(model.version == "1.0.0")
        return when (model.id) {
            "tinyclip-balanced" -> mapOf(
                "image.tflite" to Pin(33398176L, "b2b47bcd6d2bbaed289d9c368565a54fbf0ee7b27b1ec8d7576dbc848a992ba6"),
                "text.tflite" to Pin(60773852L, "42e43829f80c4655fcd913f30cb1177923cbfd801f249ba450554af320c74487"),
                "vocab.json" to Pin(1059962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349"),
                "merges.txt" to Pin(524619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
                "NOTICE.txt" to Pin(147L, "a2e817e88eb8ced0d2e9ffad4d9d4d97e729af7e3442b3ff3743a390180919e3"),
            )
            "tinyclip-quality" -> mapOf(
                "image.tflite" to Pin(154647128L, "25a806faac523ce71183b819aaace03ee9e5f0f1b24dcd0e48f738cfc7604f6a"),
                "text.tflite" to Pin(178210680L, "6db51c1aef2de15b3680b52134a2565c77d6aac5340ca24b6632243bd21ed82c"),
                "vocab.json" to Pin(1059962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349"),
                "merges.txt" to Pin(524619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
                "NOTICE.txt" to Pin(149L, "891708f6156321070883f3a32c908774d92d94434fa1a2e4187dc6e1d975a4b6"),
            )
            else -> error("Unknown semantic model")
        }
    }
    fun verify(model: InstalledSemanticModel) {
        pins(model.descriptor).forEach { (name, pin) ->
            val file = model.directory.resolve(name)
            require(file.isFile && file.length() == pin.bytes && sha256(file) == pin.sha256) { "Semantic model integrity failed" }
        }
    }
    fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
