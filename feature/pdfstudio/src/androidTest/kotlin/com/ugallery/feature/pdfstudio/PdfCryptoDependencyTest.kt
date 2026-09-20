package com.ugallery.feature.pdfstudio

import java.io.StringReader
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.Signature
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.Assert.*
import org.junit.Test

/** Checks the packaged provider and cross-artifact PKIX/ASN.1 compatibility on Android. */
class PdfCryptoDependencyTest {
    @Test
    fun reviewedProviderSupportsPemKeyRoundTripWithoutGlobalRegistration() {
        val provider = BouncyCastleProvider()
        assertTrue("Obsolete packaged provider: ${provider.info}", provider.info.contains("1.85.2"))
        val generator = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val pem =
            StringWriter().also { out -> JcaPEMWriter(out).use { it.writeObject(pair) } }.toString()
        val parsed = PEMParser(StringReader(pem)).use { it.readObject() }
        val converter = JcaPEMKeyConverter().setProvider(provider)
        val restored =
            when (parsed) {
                is PEMKeyPair -> converter.getKeyPair(parsed).private
                is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                else -> error("Unexpected key representation")
            }
        assertArrayEquals(pair.private.encoded, restored.encoded)
        val message = "local PDF dependency compatibility".toByteArray()
        val signer = Signature.getInstance("SHA256withRSA", provider)
        signer.initSign(restored)
        signer.update(message)
        val signature = signer.sign()
        val verifier = Signature.getInstance("SHA256withRSA", provider)
        verifier.initVerify(pair.public)
        verifier.update(message)
        assertTrue(verifier.verify(signature))
        message[0] = (message[0].toInt() xor 1).toByte()
        verifier.initVerify(pair.public)
        verifier.update(message)
        assertFalse(verifier.verify(signature))
        // Ephemeral key material stays in this test's memory and is never published or logged.
    }

    @Test
    fun absentOptionalJpxDecoderReportsCheckedFailure() {
        try {
            Class.forName("com.gemalto.jp2.JP2Decoder")
            fail("Review optional codec rules when bundling JPEG 2000")
        } catch (_: ClassNotFoundException) {
            // Mirrors the upstream conditional dependency guard.
        }
        val filter =
            com.tom_roush.pdfbox.filter.FilterFactory.INSTANCE.getFilter(
                com.tom_roush.pdfbox.cos.COSName.JPX_DECODE
            )
        try {
            filter.decode(
                java.io.ByteArrayInputStream(byteArrayOf(0)),
                java.io.ByteArrayOutputStream(),
                com.tom_roush.pdfbox.cos.COSDictionary(),
                0,
            )
            fail("Missing optional reader must be reported")
        } catch (_: com.tom_roush.pdfbox.filter.MissingImageReaderException) {
            // No missing-class linkage crash and no implied JPEG 2000 support.
        }
    }
}
