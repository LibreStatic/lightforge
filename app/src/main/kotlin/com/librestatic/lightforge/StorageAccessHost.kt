package com.librestatic.lightforge

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.fragment.app.FragmentActivity
import com.librestatic.lightforge.feature.privatealbum.BiometricGate
import com.librestatic.lightforge.feature.settings.StorageAccessSettings

/**
 * Settings wiring for the "Storage access" page: owns the Hilt view model and re-reads the system
 * permissions every time the app returns from a system screen. Null when there is no activity.
 */
@Composable
fun rememberStorageAccessSettings(): StorageAccessSettings? {
    val activity = LocalActivity.current as? ComponentActivity ?: return null
    val viewModel = remember(activity) { ViewModelProvider(activity)[StorageAccessViewModel::class.java] }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val state by viewModel.state.collectAsState()
    val actions = remember(viewModel, activity) {
        viewModel.actions.copy(requireUnlock = { onSuccess -> authenticateForHiddenItems(activity, onSuccess) })
    }
    return remember(state, actions) { StorageAccessSettings(state, actions) }
}

/**
 * Runs [onSuccess] after the user authenticates, for anything that reveals hidden items while "Protect
 * hidden items with app lock" is on (global or temporary show-hidden, the hidden-folders list). Without a
 * screen lock, PIN or biometrics nothing can be authenticated: say so instead of failing silently.
 */
fun authenticateForHiddenItems(context: Context, onSuccess: () -> Unit) {
    val fragmentActivity = context as? FragmentActivity
    if (fragmentActivity == null || !BiometricGate.canAuthenticate(context)) {
        Toast.makeText(context, context.getString(R.string.destructive_auth_unavailable), Toast.LENGTH_LONG).show()
        return
    }
    BiometricGate.authenticate(
        activity = fragmentActivity,
        title = context.getString(R.string.destructive_auth_title),
        subtitle = context.getString(R.string.destructive_auth_body),
        onSuccess = onSuccess,
        onError = {},
        onFail = {},
    )
}
