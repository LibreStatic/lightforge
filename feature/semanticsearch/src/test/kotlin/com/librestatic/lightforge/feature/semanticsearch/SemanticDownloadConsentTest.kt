package com.librestatic.lightforge.feature.semanticsearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDownloadConsentTest {
    @Test fun automaticDownloadNeedsSemanticSearchAndLocalAnalysisConsent() {
        val automatic = SemanticSelectionMode.Automatic
        assertTrue(SemanticDownloadConsent.automaticDownloadAllowed(enabled = true, localAnalysisAccepted = true, automatic))
        assertFalse(SemanticDownloadConsent.automaticDownloadAllowed(enabled = false, localAnalysisAccepted = true, automatic))
        assertFalse(SemanticDownloadConsent.automaticDownloadAllowed(enabled = true, localAnalysisAccepted = false, automatic))
        assertFalse(SemanticDownloadConsent.automaticDownloadAllowed(enabled = false, localAnalysisAccepted = false, automatic))
    }

    @Test fun manualSelectionNeverDownloadsAutomatically() {
        assertFalse(SemanticDownloadConsent.automaticDownloadAllowed(true, true, SemanticSelectionMode.Manual))
    }

    @Test fun onlyAnExplicitRequestDownloadsWhileSemanticSearchIsOff() {
        assertFalse(SemanticDownloadConsent.downloadAllowed(enabled = false, userInitiated = false))
        assertTrue(SemanticDownloadConsent.downloadAllowed(enabled = false, userInitiated = true))
        assertTrue(SemanticDownloadConsent.downloadAllowed(enabled = true, userInitiated = false))
    }
}
