package com.ugallery.feature.privatealbum

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation contract only: no Keystore, authentication prompt or migration is exercised. */
class PrivateKeyProtectionUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixPhasesDispatchOnlyTheirExplicitActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = mutableStateOf(PrivateKeyProtectionPhase.Confirmation)
        var confirmCalls = 0
        var dismissCalls = 0
        compose.setContent {
            UGalleryTheme(darkTheme = false) {
                PrivateKeyProtectionContent(
                    phase = phase.value,
                    onConfirm = {
                        confirmCalls++
                        // Caller synchronously owns the in-flight transition.
                        phase.value = PrivateKeyProtectionPhase.Working
                    },
                    onDismiss = { dismissCalls++ },
                )
            }
        }
        fun counts(confirm: Int, dismiss: Int) = compose.runOnIdle {
            assertEquals(confirm, confirmCalls)
            assertEquals(dismiss, dismissCalls)
        }
        fun status(resource: Int) {
            compose.onNodeWithTag("private-key-protection-status")
                .assertTextEquals(context.getString(resource))
        }
        fun confirmLabel(resource: Int) {
            compose.onNodeWithTag("private-key-protection-confirm")
                .assertTextEquals(context.getString(resource)).assertIsEnabled()
        }

        status(R.string.private_key_protection_body)
        confirmLabel(R.string.private_key_protection_protect)
        compose.onNodeWithTag("private-key-protection-cancel")
            .assertTextEquals(context.getString(R.string.private_key_protection_cancel)).performClick()
        counts(0, 1)
        compose.onNodeWithTag("private-key-protection-confirm").performClick()
        counts(1, 1)

        status(R.string.private_key_protection_working)
        compose.onNodeWithTag("private-key-protection-progress").assertExists()
        compose.onNodeWithTag("private-key-protection-confirm")
            .assertTextEquals(context.getString(R.string.private_key_protection_working_button))
            .assertIsNotEnabled().performClick()
        compose.onNodeWithTag("private-key-protection-cancel").assertDoesNotExist()
        // A real back event targets the dialog window; Working must not dismiss it.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("private-key-protection-dialog").assertExists()
        counts(1, 1)

        compose.runOnIdle { phase.value = PrivateKeyProtectionPhase.AuthenticationRequired }
        status(R.string.private_key_protection_authentication_required)
        confirmLabel(R.string.private_key_protection_continue)
        compose.onNodeWithTag("private-key-protection-progress").assertDoesNotExist()
        compose.onNodeWithTag("private-key-protection-cancel").performClick()
        counts(1, 2)
        compose.onNodeWithTag("private-key-protection-confirm").performClick()
        counts(2, 2)

        compose.runOnIdle { phase.value = PrivateKeyProtectionPhase.Failed }
        status(R.string.private_key_protection_failed)
        confirmLabel(R.string.private_key_protection_retry)
        compose.onNodeWithTag("private-key-protection-cancel").performClick()
        counts(2, 3)
        compose.onNodeWithTag("private-key-protection-confirm").performClick()
        counts(3, 3)

        compose.runOnIdle { phase.value = PrivateKeyProtectionPhase.CredentialsRequired }
        status(R.string.private_key_protection_credentials_required)
        confirmLabel(R.string.private_key_protection_retry)
        compose.onNodeWithTag("private-key-protection-cancel").performClick()
        counts(3, 4)
        compose.onNodeWithTag("private-key-protection-confirm").performClick()
        counts(4, 4)

        compose.runOnIdle { phase.value = PrivateKeyProtectionPhase.Complete }
        status(R.string.private_key_protection_complete)
        compose.onNodeWithTag("private-key-protection-confirm").assertDoesNotExist()
        compose.onNodeWithTag("private-key-protection-cancel").assertDoesNotExist()
        compose.onNodeWithTag("private-key-protection-progress").assertDoesNotExist()
        compose.onNodeWithTag("private-key-protection-done")
            .assertTextEquals(context.getString(R.string.private_key_protection_done))
            .assertIsEnabled().performClick()
        counts(4, 5)
    }
}
