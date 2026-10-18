package com.librestatic.lightforge

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
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
    return remember(state, viewModel) { StorageAccessSettings(state, viewModel.actions) }
}
