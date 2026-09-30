package com.librestatic.lightforge.core.designsystem

import android.Manifest
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run serially after other UI/performance work: temporarily changes device-global animation scales.
 * No shell command, root, app-data clearing, or replacement motion provider is used.
 * The fsynced UUID receipt retains the original values if the instrumentation process is externally killed;
 * ordinary failures always restore and verify every original value in finally before dropping identity.
 */
@OptIn(ExperimentalAnimationApi::class)
@RunWith(AndroidJUnit4::class)
class GallerySystemMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun androidTransitionScaleUpdatesRealProviderAndVisibilityInSameActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val resolver = context.contentResolver
        val uuid = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid"))
        require(UUID.fromString(uuid).toString() == uuid)
        val receipt = File(context.filesDir, "system-motion-$uuid.json")
        check(receipt.createNewFile()) { "Review the existing UUID receipt before reuse" }
        val names = listOf(Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
        val original = names.associateWith { Settings.Global.getString(resolver, it) }
        val activity = compose.activity
        val task = activity.taskId
        val pid = Process.myPid()
        val previousAutoAdvance = compose.mainClock.autoAdvance
        val observedReduced = AtomicReference<Boolean?>(null)
        val observedTransition = AtomicReference<Transition<EnterExitState>?>(null)
        var visible by mutableStateOf(false)
        val evidence = JSONObject().put("uuid", uuid).put("owner", context.packageName)
            .put("original", JSONObject().apply { original.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } })
            .put("pid", pid).put("task", task).put("activityIdentity", System.identityHashCode(activity))

        fun record(phase: String) {
            evidence.put("phase", phase)
            evidence.put("current", JSONObject().apply {
                names.forEach { put(it, Settings.Global.getString(resolver, it) ?: JSONObject.NULL) }
            })
            FileOutputStream(receipt).use { output ->
                output.write(evidence.toString(2).toByteArray()); output.flush(); output.fd.sync()
            }
            check(JSONObject(receipt.readText()).getString("phase") == phase)
        }
        fun sameActivity() = compose.runOnIdle {
            assertSame(activity, compose.activity)
            assertEquals(task, compose.activity.taskId)
            assertEquals(pid, Process.myPid())
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
        }
        fun awaitReduced(expected: Boolean) {
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                observedReduced.get() == expected
            }
            sameActivity()
        }
        fun advanceTwoFrames() {
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
        }
        // Immutable original receipt survives an interruption while rewriting a later phase receipt.
        val originalReceipt = File(context.filesDir, "system-motion-$uuid-original.json")
        check(originalReceipt.createNewFile()) { "Original UUID receipt already exists" }
        FileOutputStream(originalReceipt).use { output ->
            output.write(evidence.toString(2).toByteArray()); output.flush(); output.fd.sync()
        }
        assertEquals(evidence.getJSONObject("original").toString(),
            JSONObject(originalReceipt.readText()).getJSONObject("original").toString())
        record("original-before-write")
        var identityAdopted = false
        var primaryFailure: Throwable? = null
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            identityAdopted = true
            names.forEach {
                check(Settings.Global.putString(resolver, it, "1.0"))
                assertEquals("1.0", Settings.Global.getString(resolver, it))
            }
            compose.mainClock.autoAdvance = false
            compose.setContent {
                LightforgeTheme(darkTheme = false) {
                    val reduced = rememberGalleryReducedMotion()
                    SideEffect { observedReduced.set(reduced) }
                    GalleryAnimatedVisibility(visible = visible, edge = GalleryMotionEdge.End) {
                        val actualTransition = transition
                        SideEffect { observedTransition.set(actualTransition) }
                        Box(Modifier.size(120.dp).testTag("system-motion-overlay"))
                    }
                }
            }
            awaitReduced(false)
            compose.runOnIdle { visible = true }
            advanceTwoFrames()
            compose.runOnIdle {
                val actual = checkNotNull(observedTransition.get())
                assertEquals(EnterExitState.Visible, actual.targetState)
                assertNotEquals(actual.currentState, actual.targetState)
                assertTrue(actual.isRunning)
                evidence.put("enabledTransitionRunning", true)
            }
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("system-motion-overlay").assertIsDisplayed()
            record("scale-one-animation-observed")
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("system-motion-overlay").assertDoesNotExist()

            // Animator duration remains 1: the application's real ContentObserver must disable motion,
            // rather than Compose simply making every animation instantaneous through animator scale 0.
            check(Settings.Global.putString(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, "0.0"))
            assertEquals("1.0", Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE))
            awaitReduced(true)
            compose.runOnIdle { observedTransition.set(null); visible = true }
            advanceTwoFrames()
            compose.onNodeWithTag("system-motion-overlay").assertIsDisplayed()
            compose.runOnIdle {
                val actual = checkNotNull(observedTransition.get())
                assertEquals(EnterExitState.Visible, actual.currentState)
                assertEquals(EnterExitState.Visible, actual.targetState)
                assertFalse(actual.isRunning)
                evidence.put("disabledTransitionSettled", true)
            }
            sameActivity()
            record("scale-zero-no-spatial-transition")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var restoreFailure: Throwable? = null
            fun retainFailure(failure: Throwable) {
                if (restoreFailure == null) restoreFailure = failure else restoreFailure!!.addSuppressed(failure)
            }
            if (identityAdopted) {
                // Attempt every original key even if restoring another one throws.
                original.forEach { (key, value) ->
                    try {
                        check(Settings.Global.putString(resolver, key, value))
                        assertEquals("Exact raw animation setting restored: $key", value, Settings.Global.getString(resolver, key))
                    } catch (failure: Throwable) { retainFailure(failure) }
                }
                try {
                    if (observedReduced.get() != null) {
                        val expected = original.values.minOf { it?.toFloatOrNull() ?: 1f }.coerceAtLeast(0f) <= 0f
                        awaitReduced(expected)
                        evidence.put("restoredProvider", expected)
                    }
                    record(if (restoreFailure == null) "restored" else "restoration-failed")
                } catch (failure: Throwable) { retainFailure(failure) }
            }
            try { compose.mainClock.autoAdvance = previousAutoAdvance }
            catch (failure: Throwable) { retainFailure(failure) }
            try { if (identityAdopted) instrumentation.uiAutomation.dropShellPermissionIdentity() }
            catch (failure: Throwable) { retainFailure(failure) }
            restoreFailure?.let { failure ->
                if (primaryFailure != null) primaryFailure!!.addSuppressed(failure) else throw failure
            }
        }
    }
}
