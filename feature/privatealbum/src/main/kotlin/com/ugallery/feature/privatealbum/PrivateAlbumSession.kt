package com.ugallery.feature.privatealbum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** UI authorization only: never saved, never a replacement for authentication-bound keys. */
class PrivateAlbumSession(private val onRevoke: () -> Unit = {}) {
    var isUnlocked by mutableStateOf(false)
        private set
    private var generation = 0L
    private var pendingAuthentication: Long? = null
    private var active = false
    private var foreground = false
    private var disposed = false

    fun setActive(value: Boolean) {
        if (!value) revoke()
        active = value && !disposed
    }

    fun setForeground(value: Boolean) {
        if (!value) revoke()
        foreground = value && !disposed
    }

    fun beginAuthentication(): Long? {
        if (!active || !foreground || disposed || pendingAuthentication != null) return null
        return (++generation).also { pendingAuthentication = it }
    }

    fun completeAuthentication(token: Long): Boolean {
        if (!active || !foreground || disposed || pendingAuthentication != token) return false
        pendingAuthentication = null
        isUnlocked = true
        return true
    }

    /** A failed sample is not terminal; the platform prompt can still accept a later sample. */
    fun isPending(token: Long): Boolean =
        active && foreground && !disposed && pendingAuthentication == token

    fun cancelAuthentication(token: Long): Boolean {
        if (!isPending(token)) return false
        pendingAuthentication = null
        return true
    }

    fun accessToken(): Long? = generation.takeIf { active && foreground && isUnlocked && !disposed }

    fun hasAccess(token: Long): Boolean = accessToken() == token

    fun revoke() {
        onRevoke()
        generation++
        pendingAuthentication = null
        isUnlocked = false
    }

    fun dispose() {
        revoke()
        disposed = true
        active = false
        foreground = false
    }
}

/** ON_STOP also includes external document pickers: their return must reauthenticate. */
@Composable
fun rememberPrivateAlbumSession(active: Boolean, lifecycleOwner: LifecycleOwner, onRevoke: () -> Unit = {}): PrivateAlbumSession {
    val currentOnRevoke = androidx.compose.runtime.rememberUpdatedState(onRevoke)
    val session = remember(lifecycleOwner) { PrivateAlbumSession { currentOnRevoke.value() } }
    DisposableEffect(session, active) {
        session.setActive(active)
        onDispose { session.setActive(false) }
    }
    DisposableEffect(session, lifecycleOwner) {
        session.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.setForeground(true)
                Lifecycle.Event.ON_STOP -> session.setForeground(false)
                Lifecycle.Event.ON_DESTROY -> session.dispose()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.dispose()
        }
    }
    return session
}
