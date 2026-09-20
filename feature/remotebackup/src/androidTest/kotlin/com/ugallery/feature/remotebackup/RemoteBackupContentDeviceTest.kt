package com.ugallery.feature.remotebackup

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.remotestorage.*
import com.ugallery.feature.settings.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteBackupContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class OwnedContext(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "remote-ui-${UUID.randomUUID()}").apply { mkdirs() }

        override fun getFilesDir() = directory

        override fun getApplicationContext(): Context = this
    }

    private fun services() =
        RemoteBackupServices(
            RemoteConnectionFactory { _, _, _ -> error("No UI fixture network") },
            object : RemoteCredentialVault {
                override fun save(profileId: String, credentials: RemoteCredentials) {}

                override fun load(profileId: String): RemoteCredentials? = null

                override fun delete(profileId: String) {}
            },
            null,
            RemoteRestoreBridge { _, _, _, _, _, _ -> "fixture" },
            { true },
        )

    @Test
    fun lightDarkAndDynamicMaterialPairsMeetContrastAndProfileControlsAreReachable() {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        val context = OwnedContext(actual)
        val controller = RemoteBackupController(context, services()) {}
        var mode by mutableStateOf(0)
        var colors: ColorScheme? = null
        try {
            compose.setContent {
                UGalleryTheme(darkTheme = mode == 1, dynamicColor = mode == 2) {
                    val currentColors = MaterialTheme.colorScheme
                    SideEffect { colors = currentColors }
                    RemoteBackupContent(controller, onBack = {})
                }
            }
            val evidence = JSONArray()
            repeat(3) { variant ->
                compose.runOnIdle { mode = variant }
                compose.waitForIdle()
                val palette = requireNotNull(colors)
                val pairs =
                    listOf(
                        "surface" to (palette.surface to palette.onSurface),
                        "surfaceContainer" to (palette.surfaceContainer to palette.onSurface),
                        "primary" to (palette.primary to palette.onPrimary),
                        "primaryContainer" to
                            (palette.primaryContainer to palette.onPrimaryContainer),
                    )
                val ratios = JSONObject()
                pairs.forEach { (name, pair) ->
                    val first = pair.first.luminance().toDouble()
                    val second = pair.second.luminance().toDouble()
                    val ratio = (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
                    assertTrue("$name variant=$variant contrast=$ratio", ratio >= 4.5)
                    ratios.put(name, ratio)
                }
                evidence.put(
                    JSONObject()
                        .put("variant", variant)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("dynamicActual", variant == 2 && Build.VERSION.SDK_INT >= 31)
                        .put("ratios", ratios)
                )
                val screenshot =
                    File(
                        actual.cacheDir,
                        "remote-flow-theme-api${Build.VERSION.SDK_INT}-$variant.png",
                    )
                screenshot.outputStream().use {
                    compose
                        .onNodeWithTag("remote-backup-screen")
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            File(actual.cacheDir, "remote-flow-theme-api${Build.VERSION.SDK_INT}.json")
                .writeText(evidence.toString())
            compose
                .onNodeWithText(actual.getString(R.string.remote_add))
                .performScrollTo()
                .performClick()
            compose.onNodeWithTag("remote-profile-host").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("remote-profile-folder").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("remote-secret").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(actual.getString(R.string.remote_cancel)).performClick()
        } finally {
            controller.close()
            context.directory.deleteRecursively()
        }
    }

    @Test
    fun reviewConfirmsOnlyTheShownArchiveAndHistoryPauseCancelChangesDurableState() {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        val context = OwnedContext(actual)
        val store = RemoteBackupStore(context)
        val id = UUID.randomUUID().toString()
        val profile =
            RemoteProfile(
                name = "fixture",
                protocol = RemoteProtocol.SFTP,
                host = "fixture.invalid",
                username = "fixture",
                root = "/owned",
            )
        val archive = store.archive(id)
        val manifest =
            LocalBackupArchive.create(
                listOf(
                    LocalBackupArchive.Source("review.pdf", "application/pdf") {
                        "%PDF review fixture".byteInputStream()
                    }
                ),
                archive,
            )
        val digest = RemoteBackupIO.digest(archive) {}
        store.create(
            RemoteBackupTask(
                id,
                profile,
                RemoteBackupDirection.Upload,
                1,
                status = RemoteBackupStatus.AwaitingUploadReview,
                name = "review.ugallery.zip",
                totalBytes = digest.size,
                archiveSha = digest.sha256,
                files = manifest.entries.size,
            )
        )
        val controller = RemoteBackupController(context, services()) {}
        try {
            compose.setContent {
                UGalleryTheme(darkTheme = true, dynamicColor = false) {
                    RemoteBackupContent(controller, onBack = {})
                }
            }
            compose.waitUntil(10000) { controller.tasks.value.isNotEmpty() }
            compose
                .onNodeWithTag("remote-backup-screen")
                .performScrollToNode(hasTestTag("remote-review-$id"))
            compose.onNodeWithTag("remote-review-$id").performClick()
            val summary =
                actual.getString(
                    R.string.remote_archive_summary,
                    manifest.version,
                    manifest.entries.size,
                    manifest.totalBytes,
                )
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(summary).assertExists()
            compose.onNodeWithText(actual.getString(R.string.remote_upload)).performClick()
            compose.waitUntil(10000) { store.get(id)!!.status == RemoteBackupStatus.Queued }
            compose.onNodeWithTag("remote-pause-$id").performScrollTo().performClick()
            compose.waitUntil(10000) { store.get(id)!!.status == RemoteBackupStatus.Paused }
            compose.onNodeWithTag("remote-cancel-$id").performScrollTo().performClick()
            compose.onNodeWithText(actual.getString(R.string.remote_confirm)).performClick()
            compose.waitUntil(10000) { store.get(id)!!.cancelRequested }
        } finally {
            controller.close()
            context.directory.deleteRecursively()
        }
    }
}
