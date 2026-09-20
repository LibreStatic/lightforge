package com.ugallery.feature.localsharing

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalSharingContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun explicitReceiveAndMaterialPairsAreReachableInThreeThemes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = PeerFixtureContext(base, UUID.randomUUID().toString())
        val services =
            LocalSharingServices(PeerFixtureSource(), PeerFixtureImport(context)) { true }
        var receivedHost: String? = null
        val controller = LocalSharingController(context, services, {}, { receivedHost = it }, {})
        var mode by mutableStateOf(0)
        var colors: ColorScheme? = null
        try {
            compose.setContent {
                UGalleryTheme(darkTheme = mode == 1, dynamicColor = mode == 2) {
                    val palette = MaterialTheme.colorScheme
                    SideEffect { colors = palette }
                    LocalSharingContent(controller, {})
                }
            }
            val evidence = JSONArray()
            repeat(3) { variant ->
                compose.runOnIdle { mode = variant }
                compose.waitForIdle()
                val p = colors!!
                val ratios = JSONObject()
                listOf(
                        "surface" to (p.surface to p.onSurface),
                        "card" to (p.surfaceContainerHighest to p.onSurface),
                        "primary" to (p.primary to p.onPrimary),
                        "error" to (p.errorContainer to p.onErrorContainer),
                    )
                    .forEach { (name, pair) ->
                        val a = pair.first.luminance()
                        val b = pair.second.luminance()
                        val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                        assertTrue("$name ratio=$ratio", ratio >= 4.5)
                        ratios.put(name, ratio)
                    }
                evidence.put(
                    JSONObject()
                        .put("variant", variant)
                        .put("dynamicActual", variant == 2 && Build.VERSION.SDK_INT >= 31)
                        .put("ratios", ratios)
                )
                File(base.cacheDir, "sync-peer-theme-api${Build.VERSION.SDK_INT}-$variant.png")
                    .outputStream()
                    .use {
                        compose
                            .onRoot()
                            .captureToImage()
                            .asAndroidBitmap()
                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
            File(base.cacheDir, "sync-peer-theme-api${Build.VERSION.SDK_INT}.json")
                .writeText(evidence.toString())
            compose.onNodeWithTag("peer-host").performTextInput("127.0.0.1")
            compose.onNodeWithTag("peer-start-receive").performClick()
            compose.runOnIdle { assertEquals("127.0.0.1", receivedHost) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("peer-code"))
            compose.onNodeWithTag("peer-code").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("peer-strip-location"))
            compose.onNodeWithTag("peer-strip-location").assertIsOn()
        } finally {
            controller.close()
            context.cleanup()
        }
    }

    @Test
    fun reviewRequiresExplicitImportAndDefaultsToKeepBoth() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = PeerFixtureContext(base, UUID.randomUUID().toString())
        val source = PeerFixtureSource(byteArrayOf(1, 2, 3))
        val imported =
            PeerFixtureImport(context).apply {
                disposition = LocalSharingImportDisposition.Conflict
            }
        val services = LocalSharingServices(source, imported) { true }
        val controller = LocalSharingController(context, services, {}, {}, {})
        val store = LocalSharingStore(context)
        val id = UUID.randomUUID().toString()
        try {
            val staged = File(context.filesDir, "fixture-source").apply { mkdirs() }
            val entry = runBlocking { source.prepare(emptyList(), true, staged, {}) }.single().entry
            store.create(
                LocalSharingTransfer(
                    id,
                    "a".repeat(64),
                    LocalSharingDirection.Receive,
                    status = LocalSharingStatus.ReadyToImport,
                    manifest = LocalSharingManifest(listOf(entry), true),
                )
            )
            store.received(id, 0).writeBytes(source.bytes)
            compose.setContent {
                UGalleryTheme(darkTheme = true, dynamicColor = false) {
                    LocalSharingContent(controller, {})
                }
            }
            compose.waitUntil(10000) { controller.transfers.value.any { it.id == id } }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("peer-review-$id"))
            compose.onNodeWithTag("peer-review-$id").performClick()
            compose.onNodeWithTag("peer-review-dialog").assertExists()
            assertEquals(0, imported.copies)
            compose.onNodeWithTag("peer-confirm-review").performClick()
            compose.waitUntil(10000) { store.transfer(id)!!.status == LocalSharingStatus.Importing }
            assertEquals(
                mapOf(entry.sourceId to LocalSharingConflictChoice.KeepBoth),
                store.transfer(id)!!.choices,
            )
            runBlocking { LocalSharingRunner(context, services).run(id) }
            assertEquals(1, imported.copies)
            assertTrue(store.received(id, 0).exists())
        } finally {
            controller.close()
            context.cleanup()
        }
    }
}
