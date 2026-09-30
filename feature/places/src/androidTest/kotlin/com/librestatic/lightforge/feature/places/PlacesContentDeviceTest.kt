package com.librestatic.lightforge.feature.places

import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class PlacesContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realRegionalMapLightAndPhotoYearFilter() = workflow("light", false, false)

    @Test fun realRegionalMapDarkAndCameraRestoration() = workflow("dark", true, false)

    @Test fun realRegionalMapDynamicAndPhotoNavigation() = workflow("dynamic", false, true)

    @Test
    fun incompatibleGlesKeepsPackageManagerWithoutStartingNativeRenderer() =
        workflow("unsupported", false, false, false)

    private fun workflow(
        label: String,
        dark: Boolean,
        dynamic: Boolean,
        rendererCompatible: Boolean = true,
    ) {
        val root = File(context.cacheDir, "maps-ui-owned-" + UUID.randomUUID()).apply { mkdirs() }
        val raw = File(root, "original.pmtiles")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("maps/monaco.pmtiles")
            .use { i -> raw.outputStream().use { i.copyTo(it) } }
        val access =
            object : OfflineMapSourceAccess {
                override fun hasRead(uri: Uri) = true

                override fun retainRead(uri: Uri) {}

                override fun releaseRead(uri: Uri) {}

                override fun open(uri: Uri) = OfflineMapInput(raw.inputStream(), raw.length())
            }
        val controller =
            OfflinePlacesController(
                context,
                {},
                access,
                {
                    OfflineMapDeviceFacts(
                        10L * 1024 * 1024 * 1024,
                        8L * 1024 * 1024 * 1024,
                        false,
                        rendererCompatible,
                        true,
                        true,
                    )
                },
                File(root, "installed"),
            )
        var failureSnapshot: android.graphics.Bitmap? = null
        try {
            runBlocking {
                val id =
                    controller.importPackage(
                        Uri.parse("content://maps.fixture/ui"),
                        "Monaco real map",
                    )
                assertEquals(OfflineMapTaskStatus.ReadyForReview, controller.run(id))
                controller.confirmImport(id)
                assertEquals(OfflineMapTaskStatus.Installed, controller.run(id))
            }
            val photos =
                listOf(
                    PlacePhoto(
                        MediaKey("fixture", 1),
                        43.738,
                        7.425,
                        Instant.parse("2026-06-01T12:00:00Z").toEpochMilli(),
                        1,
                    ),
                    PlacePhoto(
                        MediaKey("fixture", 2),
                        43.738,
                        7.425,
                        Instant.parse("2025-06-01T12:00:00Z").toEpochMilli(),
                        1,
                    ),
                )
            val source =
                object : PlacesSource {
                    override val revision = MutableStateFlow(0L)

                    override suspend fun years() = listOf(2026, 2025)

                    override suspend fun query(
                        bounds: PlaceBounds,
                        year: Int?,
                        limit: Int,
                    ): PlacesPhotoPage {
                        val matching =
                            photos.filter {
                                bounds.contains(it.latitude, it.longitude) &&
                                    (year == null ||
                                        Instant.ofEpochMilli(it.timelineSortMillis)
                                            .atZone(ZoneOffset.UTC)
                                            .year == year)
                            }
                        return PlacesPhotoPage(matching, matching.size.toLong())
                    }
                }
            var host: View? = null
            var clicked: MediaKey? = null
            var pinContrast = 0.0
            var pinPair = ""
            var pinForeground = 0
            var pinBackground = 0
            val locationAllowed = mutableStateOf(true)
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                host = LocalView.current
                LightforgeTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
                    val background = MaterialTheme.colorScheme.primaryContainer
                    pinContrast =
                        (maxOf(foreground.luminance(), background.luminance()) + 0.05) /
                            (minOf(foreground.luminance(), background.luminance()) + 0.05)
                    pinForeground = foreground.toArgb()
                    pinBackground = background.toArgb()
                    pinPair =
                        "primaryContainer=${background.toArgb()}; onPrimaryContainer=${foreground.toArgb()}"
                    PlacesContent(
                        controller,
                        source,
                        {},
                        { clicked = it },
                        locationAccessGranted = locationAllowed.value,
                    )
                }
            }
            if (!rendererCompatible) {
                compose
                    .onNodeWithTag("places-list")
                    .performScrollToNode(hasTestTag("places-renderer-unavailable"))
                compose.onNodeWithTag("places-renderer-unavailable").assertExists()
                compose.onNodeWithTag("places-map").assertDoesNotExist()
                compose
                    .onNodeWithTag("places-list")
                    .performScrollToNode(hasTestTag("places-import"))
                compose.onNodeWithTag("places-import").assertIsEnabled()
                assertEquals(1, controller.packs.value.size)
                return
            }
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag("places-map-ready").fetchSemanticsNodes().isNotEmpty()
            }
            fun findMap(view: View?): MapView? {
                if (view is MapView) return view
                if (view is ViewGroup)
                    for (i in 0 until view.childCount) {
                        findMap(view.getChildAt(i))?.let {
                            return it
                        }
                    }
                return null
            }
            var rendered = 0
            compose.waitUntil(20000) {
                compose.runOnIdle {
                    findMap(host?.rootView)?.getMapAsync { map ->
                        rendered =
                            map.queryRenderedFeatures(
                                    RectF(0f, 0f, 3000f, 3000f),
                                    "roads",
                                    "water",
                                    "buildings",
                                )
                                .size
                    }
                }
                rendered > 0
            }
            compose.runOnIdle {
                findMap(host?.rootView)?.getMapAsync { map ->
                    val out = File(context.filesDir, "maps-captures").apply { mkdirs() }
                    map.getStyle { File(out, "$label-style.json").writeText(it.json) }
                    File(out, "$label-camera.txt").writeText(map.cameraPosition.toString())
                }
            }
            assertTrue("Real OSM features must render, not just an empty style", rendered > 0)
            assertTrue(
                "Matching Material pin pair must reach 4.5:1: $pinContrast",
                pinContrast >= 4.5,
            )
            compose.onNodeWithTag("places-year-2026").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onNodeWithTag("places-year-2026").fetchSemanticsNode().config.getOrElse(
                    androidx.compose.ui.semantics.SemanticsProperties.Selected
                ) {
                    false
                }
            }
            val group = groupPlacePhotos(listOf(photos.first()), 10.0).single()
            compose
                .onNodeWithTag("places-list")
                .performScrollToNode(hasTestTag("places-group-${group.id}"))
            compose.onNodeWithTag("places-group-${group.id}").performClick()
            compose
                .onNodeWithTag("places-list")
                .performScrollToNode(hasTestTag("places-photo-fixture-1"))
            compose.onNodeWithTag("places-photo-fixture-1").performClick()
            assertEquals(photos.first().key, clicked)
            compose.onNodeWithTag("places-photo-fixture-2").assertDoesNotExist()
            compose.onNodeWithTag("places-list").performScrollToNode(hasTestTag("places-map"))
            var cameraIdle = false
            compose.runOnIdle {
                findMap(host?.rootView)!!.getMapAsync {
                    it.addOnCameraIdleListener { cameraIdle = true }
                    it.cameraPosition =
                        CameraPosition.Builder().target(LatLng(43.738, 7.425)).zoom(13.0).build()
                }
            }
            compose.waitUntil(10000) { cameraIdle }
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag("places-map-ready").fetchSemanticsNodes().isNotEmpty()
            }
            var restoredZoom = 0.0
            compose.waitUntil(10000) {
                compose.runOnIdle {
                    findMap(host?.rootView)?.getMapAsync { restoredZoom = it.cameraPosition.zoom }
                }
                kotlin.math.abs(restoredZoom - 13.0) < 0.01
            }
            assertEquals(13.0, restoredZoom, 0.01)
            compose.onNodeWithTag("places-year-2026").assertIsSelected()
            compose.onNodeWithTag("places-list").performScrollToNode(hasTestTag("places-map"))
            var restoredFeatures = 0
            var restoredPins = 0
            var restoredCounts = 0
            compose.waitUntil(20000) {
                compose.runOnIdle {
                    findMap(host?.rootView)?.getMapAsync { map ->
                        restoredFeatures =
                            map.queryRenderedFeatures(
                                    RectF(0f, 0f, 3000f, 3000f),
                                    "water",
                                    "roads",
                                    "buildings",
                                )
                                .size
                        restoredCounts =
                            map.queryRenderedFeatures(RectF(0f, 0f, 3000f, 3000f), "photo-count")
                                .size
                        restoredPins =
                            map.queryRenderedFeatures(RectF(0f, 0f, 3000f, 3000f), "photo-pins")
                                .size
                    }
                }
                restoredFeatures > 0 && restoredPins > 0 && restoredCounts > 0
            }
            // The Android surface presents asynchronously after native layout. Capture only a
            // completed MapLibre frame, and retain the renderer snapshot alongside the screen.
            var nativeSnapshot: android.graphics.Bitmap? = null
            var snapshotPending = false
            var matchingTextPixels = 0
            var matchingTextContrast = 0.0
            var matchingTextColors = ""
            compose.waitUntil(20000) {
                if (matchingTextPixels >= 2) true
                else {
                    if (!snapshotPending) {
                        snapshotPending = true
                        compose.runOnIdle {
                            findMap(host?.rootView)!!.getMapAsync { map ->
                                map.snapshot { bitmap ->
                                    val pixels = IntArray(bitmap.width * bitmap.height)
                                    bitmap.getPixels(
                                        pixels,
                                        0,
                                        bitmap.width,
                                        0,
                                        0,
                                        bitmap.width,
                                        bitmap.height,
                                    )
                                    // Small SDF strokes are antialiased: they may never contain
                                    // two fully opaque foreground pixels. Keep a narrow RGB bound
                                    // plus measured 4.5:1 contrast; blue/green/absent glyphs fail.
                                    val backgroundLuminance =
                                        androidx.compose.ui.graphics
                                            .Color(pinBackground)
                                            .luminance()
                                    fun contrast(pixel: Int): Double {
                                        val foregroundLuminance =
                                            androidx.compose.ui.graphics.Color(pixel).luminance()
                                        return (maxOf(backgroundLuminance, foregroundLuminance) +
                                            0.05) /
                                            (minOf(backgroundLuminance, foregroundLuminance) + 0.05)
                                    }
                                    val matches =
                                        pixels.filter { pixel ->
                                            listOf(0, 8, 16).all { shift ->
                                                kotlin.math.abs(
                                                    ((pixel ushr shift) and 255) -
                                                        ((pinForeground ushr shift) and 255)
                                                ) <= 8
                                            } && contrast(pixel) >= 4.5
                                        }
                                    matchingTextPixels = matches.size
                                    matchingTextContrast =
                                        matches.minOfOrNull { contrast(it) } ?: 0.0
                                    matchingTextColors =
                                        matches
                                            .groupingBy { it }
                                            .eachCount()
                                            .entries
                                            .joinToString { (color, count) ->
                                                "#%06x:%d".format(color and 0xffffff, count)
                                            }
                                    nativeSnapshot?.recycle()
                                    nativeSnapshot = bitmap
                                    failureSnapshot = bitmap
                                    snapshotPending = false
                                }
                            }
                        }
                    }
                    false
                }
            }
            val screenshot =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val out = File(context.filesDir, "maps-captures").apply { mkdirs() }
            File(out, "$label-map.png").outputStream().use {
                nativeSnapshot!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            File(out, "$label.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            File(out, "$label.txt")
                .writeText(
                    "Real rendered features=$rendered; UTC year2026 selected; photo1 opened; photo2 absent; camera13 restored; restoredFeatures=$restoredFeatures; restoredPins=$restoredPins; restoredCounts=$restoredCounts; glyphForegroundPixels=$matchingTextPixels; glyphObservedMinContrast=$matchingTextContrast; glyphObservedColors=$matchingTextColors; pinContrast=$pinContrast; $pinPair; width=${screenshot.width}; height=${screenshot.height}\n"
                )
            compose.runOnIdle { locationAllowed.value = false }
            compose
                .onNodeWithTag("places-list")
                .performScrollToNode(hasTestTag("places-location-access"))
            compose.onNodeWithTag("places-location-access").assertExists()
            compose.onNodeWithTag("places-year-2026").assertDoesNotExist()
            compose.onNodeWithTag("places-list").performScrollToNode(hasTestTag("places-map"))
            var pinsAfterRevoke = -1
            compose.waitUntil(10000) {
                compose.runOnIdle {
                    findMap(host?.rootView)?.getMapAsync { map ->
                        pinsAfterRevoke =
                            map.queryRenderedFeatures(RectF(0f, 0f, 3000f, 3000f), "photo-pins")
                                .size
                    }
                }
                pinsAfterRevoke == 0
            }
            // Exercise the real JNI -> Java HTTP boundary, not only JSON URL validation.
            if (label == "light")
                ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                    server.soTimeout = 400
                    val before = OfflineMapNetworkGuard.blockedRequests.get()
                    compose.runOnIdle {
                        findMap(host?.rootView)!!.getMapAsync {
                            it.setStyle(
                                org.maplibre.android.maps.Style.Builder()
                                    .fromUri("http://127.0.0.1:${server.localPort}/forbidden.json")
                            )
                        }
                    }
                    compose.waitUntil(10000) {
                        OfflineMapNetworkGuard.blockedRequests.get() > before
                    }
                    try {
                        server.accept().use { fail("Native renderer reached a real HTTP socket") }
                    } catch (_: SocketTimeoutException) {}
                    File(out, "native-http-denied.txt")
                        .writeText(
                            "JNI HTTP request rejected; blocked delta=${OfflineMapNetworkGuard.blockedRequests.get() - before}; loopback accept timed out; no socket opened\n"
                        )
                }
        } catch (t: Throwable) {
            val out = File(context.filesDir, "maps-captures").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap
                ->
                File(out, "$label-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            failureSnapshot?.let { bitmap ->
                File(out, "$label-failure-map.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            File(out, "$label-failure.txt").writeText(t.stackTraceToString())
            throw t
        } finally {
            failureSnapshot?.recycle()
            controller.close()
            root.deleteRecursively()
        }
    }
}
