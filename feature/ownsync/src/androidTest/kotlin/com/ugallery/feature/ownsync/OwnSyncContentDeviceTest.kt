package com.ugallery.feature.ownsync

import android.graphics.Bitmap
import android.net.Uri
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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OwnSyncContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun createControlsAndMaterialPairsWorkInLightDarkAndDynamic() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = OwnSyncFixtureContext(base)
        val originalServices =
            ownSyncFixtureServices(OwnSyncFixtureSource(), OwnSyncFixtureRemote())
        val secondProfile =
            originalServices
                .profiles()
                .single()
                .copy(id = "22222222-2222-2222-2222-222222222222", name = "Second fixture")
        val services =
            OwnSyncServices(
                { originalServices.profiles() + secondProfile },
                originalServices.connections,
                originalServices.credentials,
                originalServices.networkAllowed,
                originalServices.sourceFactory,
            )
        val controller = OwnSyncController(context, services, {})
        var mode by mutableStateOf(0)
        var colors: ColorScheme? = null
        try {
            compose.setContent {
                UGalleryTheme(darkTheme = mode == 1, dynamicColor = mode == 2) {
                    val palette = MaterialTheme.colorScheme
                    SideEffect { colors = palette }
                    OwnSyncContent(controller, {})
                }
            }
            val evidence = JSONArray()
            repeat(3) { variant ->
                compose.runOnIdle { mode = variant }
                compose.waitForIdle()
                val palette = colors!!
                val ratios = JSONObject()
                listOf(
                        "surface" to (palette.surface to palette.onSurface),
                        "container" to (palette.surfaceContainer to palette.onSurface),
                        "primary" to (palette.primary to palette.onPrimary),
                        "error" to (palette.errorContainer to palette.onErrorContainer),
                    )
                    .forEach { (name, pair) ->
                        val a = pair.first.luminance()
                        val b = pair.second.luminance()
                        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                        assertTrue("$name variant=$variant ratio=$ratio", ratio >= 4.5)
                        ratios.put(name, ratio)
                    }
                evidence.put(
                    JSONObject()
                        .put("mode", variant)
                        .put("dynamicActual", variant == 2 && Build.VERSION.SDK_INT >= 31)
                        .put("ratios", ratios)
                )
                File(base.cacheDir, "sync-theme-api${Build.VERSION.SDK_INT}-$variant.png")
                    .outputStream()
                    .use {
                        compose
                            .onNodeWithTag("own-sync-screen")
                            .captureToImage()
                            .asAndroidBitmap()
                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
            File(base.cacheDir, "sync-theme-api${Build.VERSION.SDK_INT}.json")
                .writeText(evidence.toString())
            compose.onNodeWithTag("own-sync-create").performClick()
            compose.onNodeWithTag("own-sync-name").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("own-sync-source").performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithTag("own-sync-profile-${secondProfile.id}")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose
                .onNodeWithTag("own-sync-profile-${originalServices.profiles().single().id}")
                .performScrollTo()
                .assertIsNotSelected()
            compose.onNodeWithTag("own-sync-mirror").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("own-sync-save").assertIsNotEnabled()
        } finally {
            controller.close()
            context.clean()
        }
    }

    @Test
    fun reviewAppliesExactPlanAndFollowupRunReportsVerifiedFiles() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = OwnSyncFixtureContext(base)
        val remote = OwnSyncFixtureRemote()
        val services = ownSyncFixtureServices(OwnSyncFixtureSource(), remote)
        val controller = OwnSyncController(context, services, {})
        val store = OwnSyncStore(context)
        try {
            val id = runBlocking {
                controller
                    .create(
                        "UI fixture",
                        Uri.parse(OwnSyncFixtureSource.Tree),
                        services.profiles().single().id,
                        OwnSyncPolicy.Additive,
                    )
                    .also { OwnSyncRunner(context, services).run(it) }
            }
            compose.setContent {
                UGalleryTheme(darkTheme = true, dynamicColor = false) {
                    OwnSyncContent(controller, {})
                }
            }
            compose.waitUntil(10000) { controller.runs.value.any { it.id == id } }
            compose
                .onNodeWithTag("own-sync-list")
                .performScrollToNode(hasTestTag("own-sync-review-$id"))
            compose.onNodeWithTag("own-sync-review-$id").performClick()
            compose.onNodeWithTag("own-sync-review-dialog").assertExists()
            compose.onNodeWithTag("own-sync-confirm").performClick()
            compose.waitUntil(10000) { store.run(id)!!.approved }
            runBlocking { assertTrue(OwnSyncRunner(context, services).run(id)) }
            val jobId = store.run(id)!!.jobId
            compose.waitUntil(10000) { controller.runs.value.single { it.id == id }.terminal }
            compose
                .onNodeWithTag("own-sync-list")
                .performScrollToNode(hasTestTag("own-sync-rerun-$jobId"))
            compose.onNodeWithTag("own-sync-rerun-$jobId").performClick()
            compose.waitUntil(10000) { store.runs().size == 2 }
            val next = store.runs().single { it.id != id }.id
            runBlocking { OwnSyncRunner(context, services).run(next) }
            assertTrue(store.run(next)!!.plan.all { it.action == OwnSyncAction.Verified })
            assertEquals(2, remote.writes)
        } finally {
            controller.close()
            context.clean()
        }
    }
}
