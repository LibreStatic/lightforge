package com.librestatic.lightforge.core.remotestorage

import android.os.SystemClock
import org.junit.Assert.fail

/**
 * Bounded poll until [thread] is blocked inside the stalled handshake: parked waiting for the
 * server's reply, or reading from the socket. Cancelling earlier would only exercise the
 * pre-connect cancellation check, not socket close during the handshake.
 */
internal fun awaitStalledInHandshake(thread: Thread, timeoutMillis: Long = 5_000) {
    fun blocked(): Boolean {
        if (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING)
            return true
        return thread.stackTrace.any { frame ->
            (frame.className.startsWith("java.net.") ||
                frame.className.startsWith("libcore.io.") ||
                frame.className.startsWith("sun.nio.")) &&
                (frame.methodName.contains("read", ignoreCase = true) ||
                    frame.methodName.contains("recv", ignoreCase = true))
        }
    }
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    var consecutive = 0
    while (SystemClock.elapsedRealtime() < deadline) {
        // Two consecutive observations rule out a transient park before the socket exists.
        consecutive = if (thread.isAlive && blocked()) consecutive + 1 else 0
        if (consecutive >= 2) return
        SystemClock.sleep(20)
    }
    fail("Connect thread never blocked in the stalled handshake")
}
