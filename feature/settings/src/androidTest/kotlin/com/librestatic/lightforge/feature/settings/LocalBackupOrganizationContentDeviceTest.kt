package com.librestatic.lightforge.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import java.io.InputStream
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Root adapter is injected; screen, picker contracts, SAF archive bytes and cancellation are real.
 */
class LocalBackupOrganizationContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun v2ReviewDefaultsGlobalRulesOffAndRestoresThroughGalleryPort() = exercise(false, false)

    @Test fun darkLargeTextGlobalOptInAndCancelledGalleryRestoreRollBack() = exercise(true, true)

    @Test fun partialSelectionRequiresSeparateConfirmation() = exercise(false,false,true)

    private fun exercise(dark: Boolean, cancel: Boolean, partial: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val authority = "com.librestatic.lightforge.feature.settings.test.localbackup"
        val base = Uri.parse("content://$authority")
        val root = DocumentsContract.buildDocumentUri(authority, "root")
        resolver.call(base, "fixture-reset", null, null)
        val original =
            DocumentsContract.createDocument(resolver, root, "image/jpeg", "original.jpg")!!
        resolver.openOutputStream(original)!!.use { it.write(byteArrayOf(1, 2, 3)) }
        val backup =
            DocumentsContract.createDocument(
                resolver,
                root,
                "application/zip",
                "organization.zip",
            )!!
        val copies = mutableListOf<ByteArray>()
        var receivedOptions: LocalRestoreOrganizationOptions? = null
        var aborted = false
        val staged = CompletableDeferred<Unit>()
        val port =
            object : LocalBackupOrganizationPort {
                override suspend fun export(
                    sources: List<LocalBackupSourceRef>
                ): LocalBackupSidecar {
                    assertEquals(original.toString(), sources.single().sourceUri)
                    assertEquals(36, sources.single().entry.sourceId.length)
                    return LocalBackupSidecar(byteArrayOf(9, 8, 7))
                }

                override suspend fun review(
                    sidecar: ByteArray,
                    manifest: BackupManifest,
                ): LocalBackupOrganizationReview {
                    assertEquals(2, manifest.version)
                    assertArrayEquals(byteArrayOf(9, 8, 7), sidecar)
                    return LocalBackupOrganizationReview(
                        listOf(
                            LocalBackupOrganizationCount(LocalBackupOrganizationKind.Albums, 1),
                            LocalBackupOrganizationCount(LocalBackupOrganizationKind.DateRules, 1),
                        ),
                        1,
                        0,
                        0,
                        1,
                        true,
                        hasPreferences = true,
                    ).let { if(partial) it.copy(omitted=1,totalSources=2) else it }
                }

                override suspend fun beginRestore(
                    sidecar: ByteArray,
                    manifest: BackupManifest,
                    options: LocalRestoreOrganizationOptions,
                ): LocalRestoreGallerySession {
                    receivedOptions = options
                    return object : LocalRestoreGallerySession {
                        override suspend fun stage(
                            entry: BackupManifest.Entry,
                            input: InputStream,
                        ) {
                            copies += input.readBytes()
                            staged.complete(Unit)
                            if (cancel) awaitCancellation()
                        }

                        override suspend fun commit() = LocalRestoreGalleryResult(copies.size, 2)

                        override suspend fun abort() {
                            aborted = true
                            copies.clear()
                        }
                    }
                }
            }
        val results = ArrayDeque<Intent>()
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    assertTrue(
                        contract
                            .createIntent(context, input)
                            .getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    )
                    dispatchResult(requestCode, Activity.RESULT_OK, results.removeFirst())
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        try {
            compose.setContent {
                LightforgeTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalActivityResultRegistryOwner provides owner,
                        LocalDensity provides Density(density.density, if (dark) 1.6f else 1f),
                    ) {
                        LocalBackupContent(port) {}
                    }
                }
            }
            awaitEnabled("local-backup-select")
            results += Intent().setData(original)
            compose.onNodeWithTag("local-backup-select").performScrollTo().performClick()
            awaitEnabled("local-backup-create")
            compose
                .onNodeWithTag("local-backup-include-organization")
                .performScrollTo()
                .performClick()
            results += Intent().setData(backup)
            compose.onNodeWithTag("local-backup-create").performScrollTo().performClick()
            awaitText(context.getString(R.string.local_backup_exported))
            compose
                .onNodeWithText(context.getString(R.string.local_backup_review_version, 2))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.local_backup_settings_review_only))
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag("local-backup-gallery").performScrollTo().performClick()
            compose.onNodeWithTag("local-backup-global-rules").performScrollTo().assertIsOff()
            if (cancel)
                compose.onNodeWithTag("local-backup-global-rules").performScrollTo().performClick()
            if(partial) {
                compose.onNodeWithTag("local-backup-gallery-confirm").assertIsNotEnabled()
                compose.onNodeWithTag("local-backup-partial").performScrollTo().assertIsOff().performClick()
            }
            compose.onNodeWithTag("local-backup-gallery-confirm").performClick()
            if (cancel) {
                compose.waitUntil(10_000) { staged.isCompleted }
                compose.onNodeWithTag("local-backup-cancel").performScrollTo().performClick()
                compose.onNodeWithTag("local-backup-cancel-confirm").performClick()
                awaitText(context.getString(R.string.local_backup_cancelled))
                assertTrue(aborted)
                assertTrue(copies.isEmpty())
            } else {
                awaitText(context.getString(R.string.local_backup_gallery_done))
                assertFalse(aborted)
                assertArrayEquals(byteArrayOf(1, 2, 3), copies.single())
            }
            assertEquals(cancel, receivedOptions!!.importGlobalRules)
            assertEquals(partial, receivedOptions!!.allowPartial)
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                resolver.openInputStream(original)!!.use { it.readBytes() },
            )
            val screenshot =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot != null) {
                java.io.File(context.filesDir, "backup-organization-$dark.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            resolver.call(base, "fixture-reset", null, null)
        }
    }

    private fun awaitEnabled(tag: String) {
        compose.waitUntil(10_000) {
            try {
                compose.onNodeWithTag(tag).assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
