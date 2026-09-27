package com.librestatic.lightforge.core.security

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Immutable valid v1 bytes: generated independently using AES-256-GCM, two chunks. */
class PrivateAlbumFooterRegressionTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val plaintext = "Private album authenticated footer regression fixture.".toByteArray()
    private fun legacyFixture() = Base64.getDecoder().decode(
        "VUdQQwEAAAAgAAECAwQFBgcICQoLAAECAwQFBgcICQoLAAAAMBdwv22kkac77C31/tzJGRj3vuJahBI8HUwCgaV7Bm/G5jnkdMDpTinbE1iX8teVPwABAgMEBQYHCAkKCgAAACbDqukyar89z6QyaDelluA9uduteDEB6sfftveCXT21t/05o1U2/wAAAAIAAAAAAAAANiUlSXUG56Vs04Xot5J+j+0BEaYB0wdbpEeNutzKAkmP",
    )

    @Test fun validLegacyFixtureStillDecrypts() {
        val output = ByteArrayOutputStream()
        val digest = PrivateAlbumCrypto.decryptStream(legacyFixture().inputStream(), output, key)
        assertArrayEquals(plaintext, output.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(plaintext), digest)
    }

    @Test fun forgedZeroChunkFooterMustNotAuthenticateNonemptyCiphertextAsEmpty() {
        val original = legacyFixture()
        val forged = original.copyOf()
        val footerOffset = forged.size - 44
        ByteBuffer.wrap(forged, footerOffset, 44)
            .putInt(0).putLong(0)
            .put(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)))
        // This mutation knows no data key and changes only the unauthenticated footer.
        assertArrayEquals(original.copyOfRange(0, footerOffset), forged.copyOfRange(0, footerOffset))
        val output = ByteArrayOutputStream()
        try {
            val digest = PrivateAlbumCrypto.decryptStream(forged.inputStream(), output, key)
            assertEquals("Observed legacy bypass returns empty plaintext", 0, output.size())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)), digest)
            fail("Forged zero-chunk footer was accepted despite nonempty encrypted chunks")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Reject before releasing plaintext", 0, output.size())
        }
    }
}
