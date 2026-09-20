package com.ugallery.feature.privatealbum

/** Strict bounded wire format, independent from Keystore for hostile/corrupt-record tests. */
internal data class PrivateIndexKeyRecord(val authenticatedAlias: String?, val ivOffset: Int) {
    companion object {
        const val MAX_SIZE = 267
        private const val PREFIX = "ugallery.privatealbum.auth.v1."
        private val HEADER = byteArrayOf(85, 71, 73, 75)
        fun parse(bytes: ByteArray): PrivateIndexKeyRecord {
            require(bytes.size in 65..MAX_SIZE && bytes.copyOfRange(0, 4).contentEquals(HEADER)) { "Invalid private index key record" }
            return when (bytes[4].toInt()) {
                1 -> { require(bytes.size == 65); PrivateIndexKeyRecord(null, 5) }
                2 -> {
                    val size = ((bytes[5].toInt() and 255) shl 8) or (bytes[6].toInt() and 255)
                    require(size in (PREFIX.length + 1)..200 && bytes.size == 67 + size)
                    val aliasBytes = bytes.copyOfRange(7, 7 + size)
                    require(aliasBytes.all { it.toInt() in 33..126 })
                    val alias = aliasBytes.toString(Charsets.US_ASCII)
                    require(alias.startsWith(PREFIX) && alias.all { it.isLetterOrDigit() || it in ".-_" })
                    PrivateIndexKeyRecord(alias, 7 + size)
                }
                else -> error("Unsupported private index key record version")
            }
        }
        fun encode(alias: String, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            require(iv.size == 12 && ciphertext.size == 48)
            val name = alias.toByteArray(Charsets.US_ASCII)
            require(name.toString(Charsets.US_ASCII) == alias && name.size <= 200)
            return (HEADER + byteArrayOf(2, (name.size ushr 8).toByte(), name.size.toByte()) + name + iv + ciphertext)
                .also { require(parse(it).authenticatedAlias == alias) }
        }
    }
}
