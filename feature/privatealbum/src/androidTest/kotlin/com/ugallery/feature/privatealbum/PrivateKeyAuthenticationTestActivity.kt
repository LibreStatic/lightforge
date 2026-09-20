package com.ugallery.feature.privatealbum

import android.os.Bundle
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred

/** Test APK only. The callback is exclusively the production BiometricGate callback. */
class PrivateKeyAuthenticationTestActivity : FragmentActivity() {
    private lateinit var button: Button
    private var result: CompletableDeferred<Unit>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        button =
            Button(this).apply {
                text = "Authenticate isolated key fixture"
                contentDescription = "private-auth-fixture-ready"
                setOnClickListener { startPrompt() }
            }
        setContentView(button)
    }

    fun prepareAuthentication(): CompletableDeferred<Unit> {
        check(result == null || result!!.isCompleted)
        return CompletableDeferred<Unit>().also {
            result = it
            button.isEnabled = true
            button.contentDescription = "private-auth-fixture-ready"
        }
    }

    private fun startPrompt() {
        val pending = result ?: return
        if (pending.isCompleted || !button.isEnabled) return
        button.isEnabled = false
        button.contentDescription = "private-auth-fixture-requesting"
        BiometricGate.authenticate(
            this,
            "Authenticate isolated key fixture",
            "This request authorizes only the test workflow",
            onSuccess = {
                button.contentDescription = "private-auth-fixture-authenticated"
                pending.complete(Unit)
            },
            onError = {
                // No provider message, credentials or private data enter evidence.
                pending.completeExceptionally(
                    IllegalStateException("Fixture authentication failed")
                )
            },
            onFail = { /* A rejected attempt is not success; await real authentication or timeout. */
            },
        )
    }

    override fun onDestroy() {
        result?.takeUnless { it.isCompleted }?.cancel()
        super.onDestroy()
    }
}
