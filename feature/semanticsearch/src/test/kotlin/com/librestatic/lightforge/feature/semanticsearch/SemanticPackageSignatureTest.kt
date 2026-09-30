package com.librestatic.lightforge.feature.semanticsearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticPackageSignatureTest {
    @Test fun releasePublicKeyAcceptsItsSignatureAndRejectsTampering() {
        val signature = "MEUCIQCmbH3YGKjBcAQFnnTUz+NkBf0p7iAAZmSSJbKeKyf7VAIgWXTRK0W8r1QA6r+ytko7jA8vO5FIQsrpqA51hEQcCtw="
        assertTrue(SemanticPackageSignature.verify("ugallery-semantic-verifier-test".toByteArray(), signature))
        assertFalse(SemanticPackageSignature.verify("tampered".toByteArray(), signature))
    }
}
