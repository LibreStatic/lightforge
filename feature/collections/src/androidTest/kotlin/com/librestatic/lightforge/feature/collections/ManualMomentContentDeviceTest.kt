package com.librestatic.lightforge.feature.collections

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Small draft/preview/callback contract. The real repository transaction is tested by integration. */
class ManualMomentContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewOrderConsentCancelAndNewDraftDoNotPersistAutomatically() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString()
        val owned = mutableListOf<Uri>()
        val visible = mutableStateOf(false)
        val draftId = mutableStateOf("first-$token")
        val busy = mutableStateOf(false)
        val error = mutableStateOf(false)
        val sources = mutableStateOf(emptyList<TimelineMedia>())
        data class Request(val title: String, val keys: List<MediaKey>, val include: Boolean)
        val requests = mutableListOf<Request>()
        var cancels = 0
        try {
            val photos = (0..2).map { index ->
                val name = "manual-moment-$token-$index.jpg"
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ManualMoment-$token/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }))
                owned += uri
                Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())[index])
                    try { checkNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
                    finally { bitmap.recycle() }
                }
                assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
                val generation = checkNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)).use {
                    assertTrue(it.moveToFirst()); it.getLong(0)
                }
                TimelineMedia(MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri)), MediaKind.Image,
                    generation, index * 1_000L, 16, 12, 0, displayName = name)
            }
            sources.value = photos
            visible.value = true
            compose.setContent {
                if (visible.value) LightforgeTheme {
                    ManualMomentContent(draftId.value, sources.value, busy.value, error.value,
                        onCreate = { title, keys, include ->
                            requests += Request(title, keys, include)
                            busy.value = true
                        },
                        onCancel = { cancels++ })
                }
            }
            val first = id(photos[0].key)
            val second = id(photos[1].key)
            val third = id(photos[2].key)
            scroll("manual-moment-include-special")
            compose.onNodeWithTag("manual-moment-include-special").assertIsOff()
            compose.runOnIdle { assertTrue(requests.isEmpty()); assertEquals(0, cancels) }
            scroll("manual-moment-title")
            compose.onNodeWithTag("manual-moment-title").performTextReplacement("My trip")
            scroll("manual-moment-source-$first")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("manual-moment-preview-$first").fetchSemanticsNodes().isNotEmpty()
            }
            scroll("manual-moment-up-$second")
            compose.onNodeWithTag("manual-moment-up-$second").performClick()
            scroll("manual-moment-remove-$third")
            compose.onNodeWithTag("manual-moment-remove-$third").performClick()
            compose.onNodeWithTag("manual-moment-cancel").performClick()
            compose.runOnIdle { assertEquals(1, cancels); assertTrue(requests.isEmpty()) }
            compose.onNodeWithTag("manual-moment-save").performClick()
            compose.runOnIdle {
                assertEquals(listOf(Request("My trip", listOf(photos[1].key, photos[0].key), false)), requests)
            }
            compose.onNodeWithTag("manual-moment-save").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("manual-moment-cancel").assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals(1, requests.size); assertEquals(1, cancels) }

            // Simulate a rejected save: caller clears busy and provides a generic review error.
            compose.runOnIdle { busy.value = false; error.value = true }
            scroll("manual-moment-error")
            compose.onNodeWithTag("manual-moment-error").assertIsDisplayed()
            scroll("manual-moment-include-special")
            compose.onNodeWithTag("manual-moment-include-special").performClick().assertIsOn()
            compose.onNodeWithTag("manual-moment-save").performClick()
            compose.runOnIdle { assertEquals(true, requests.last().include); assertEquals(2, requests.size) }

            // A different draft must not inherit the first draft's title/order/removal/consent.
            compose.runOnIdle { draftId.value = "second-$token"; busy.value = false; error.value = false }
            scroll("manual-moment-title")
            compose.onNodeWithTag("manual-moment-title").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString("")))
            scroll("manual-moment-include-special")
            compose.onNodeWithTag("manual-moment-include-special").assertIsOff()
            scroll("manual-moment-remove-$first")
            compose.onNodeWithTag("manual-moment-remove-$first").performClick()
            scroll("manual-moment-remove-$second")
            compose.onNodeWithTag("manual-moment-remove-$second").performClick()
            scroll("manual-moment-remove-$third")
            compose.onNodeWithTag("manual-moment-remove-$third").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("manual-moment-save").performClick()
            compose.runOnIdle {
                assertEquals(Request("", listOf(photos[2].key), false), requests.last())
                assertEquals(3, requests.size)
                assertEquals(1, cancels)
            }
        } finally {
            compose.runOnIdle { visible.value = false }
            owned.forEach { resolver.delete(it, null, null) }
        }
    }

    /** Actual IME + shell KEYCODE_BACK, not a semantics click pretending to be system Back. */
    @Test fun systemBackHidesRealImeBeforeCancellingReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString()
        val directory = java.io.File(context.filesDir, "manual-ime-$token").apply { check(mkdirs()) }
        val visible = mutableStateOf(true)
        val owned = mutableListOf<Uri>()
        var cancels = 0
        var creates = 0
        var passed = false
        lateinit var view: android.view.View
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.SIZE)
        fun metadata(uri: Uri): List<String>? = resolver.query(uri, columns, null, null, null)!!.use { cursor ->
            if (!cursor.moveToFirst()) null else columns.map { cursor.getString(cursor.getColumnIndexOrThrow(it)) }.also {
                check(!cursor.moveToNext())
            }
        }
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count; check(total <= 65536)
                digest.update(buffer, 0, count)
            }
            check(total > 0)
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun shell(command: String): String {
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
            java.io.File(directory, "commands.txt").appendText("$command\n$output\n")
            return output
        }
        fun imeVisible(): Boolean {
            var result = false
            instrumentation.runOnMainSync {
                result = view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
            return result
        }
        try {
            val photos = (0..1).map { index ->
                val name = "manual-ime-$token-$index.jpg"
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ManualIME-$token/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }))
                owned += uri
                java.io.File(directory, "owned-uris.txt").appendText("$uri\n")
                val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index == 0) android.graphics.Color.RED else android.graphics.Color.BLUE)
                }
                try { resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
                finally { bitmap.recycle() }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                val current = checkNotNull(metadata(uri))
                check(current[1] == context.packageName && current[2] == name && current[3] == "Pictures/ManualIME-$token/")
                TimelineMedia(MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri)), MediaKind.Image,
                    current[5].toLong(), index * 1000L, 16, 12, 0, displayName = name)
            }
            val before = owned.map { checkNotNull(metadata(it)) }
            val hashes = owned.map(::hash)
            java.io.File(directory, "baseline.json").writeText(org.json.JSONObject()
                .put("draftId", token).put("columns", org.json.JSONArray(columns.toList()))
                .put("sources", org.json.JSONArray(owned.indices.map { index ->
                    org.json.JSONObject().put("uri", owned[index].toString()).put("metadata", org.json.JSONArray(before[index]))
                        .put("sha256", hashes[index])
                })).toString(2))
            compose.setContent {
                view = androidx.compose.ui.platform.LocalView.current
                if (visible.value) LightforgeTheme {
                    ManualMomentContent(token, photos, false, false,
                        onCreate = { _, _, _ -> creates++ }, onCancel = { cancels++; visible.value = false })
                }
            }
            val first = id(photos[0].key)
            val second = id(photos[1].key)
            scroll("manual-moment-up-$second")
            compose.onNodeWithTag("manual-moment-up-$second").performClick()
            scroll("manual-moment-include-special")
            compose.onNodeWithTag("manual-moment-include-special").performClick().assertIsOn()
            scroll("manual-moment-title")
            // Focus through a real pointer; semantics text replacement may bypass the actual IME.
            val bounds = compose.onNodeWithTag("manual-moment-title").fetchSemanticsNode().boundsInRoot
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
            assertTrue(shell("input tap ${location[0] + bounds.center.x.toInt()} ${location[1] + bounds.center.y.toInt()}").isBlank())
            compose.waitUntil(10000) { imeVisible() }
            val title = "IME-$token"
            var typed = ""
            title.chunked(8).forEach { chunk ->
                assertTrue(shell("input text $chunk").isBlank())
                typed += chunk
                compose.onNodeWithTag("manual-moment-title").assert(
                    SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                        androidx.compose.ui.text.AnnotatedString(typed)))
            }
            val ime = shell("dumpsys input_method")
            assertTrue("IME must actually be shown", Regex("\\bmInputShown=true\\b").containsMatchIn(ime))
            assertTrue("IME input view must actually be started", Regex("\\bmInputViewStarted=true\\b").containsMatchIn(ime))
            assertTrue("IME view must actually be visible", Regex("\\bmIsInputViewShown=true\\b").containsMatchIn(ime))
            assertTrue(imeVisible())
            assertTrue(shell("input keyevent KEYCODE_BACK").isBlank())
            compose.waitUntil(10000) { !imeVisible() }
            compose.onNodeWithTag("manual-moment-screen").assertExists()
            compose.runOnIdle { assertEquals(0, cancels); assertEquals(0, creates) }
            scroll("manual-moment-title")
            compose.onNodeWithTag("manual-moment-title").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(title)))
            scroll("manual-moment-include-special")
            compose.onNodeWithTag("manual-moment-include-special").assertIsOn()
            listOf(second, first).forEachIndexed { index, source ->
                scroll("manual-moment-source-$source")
                compose.onNodeWithTag("manual-moment-source-$source").assert(hasAnyDescendant(
                    hasText(context.getString(R.string.manual_moment_photo, index + 1, 2))))
            }
            assertFalse(imeVisible())
            java.io.File(directory, "after-first-back-ime.txt").writeText(shell("dumpsys input_method"))
            assertTrue(shell("input keyevent KEYCODE_BACK").isBlank())
            compose.waitUntil(5000) { compose.onAllNodesWithTag("manual-moment-screen").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { assertEquals(1, cancels); assertEquals(0, creates) }
            assertEquals(before, owned.map(::metadata)); assertEquals(hashes, owned.map(::hash))
            // Success only: revalidate every owned row/hash, then exact CAS deletion and absence.
            owned.forEachIndexed { index, uri ->
                check(metadata(uri) == before[index] && hash(uri) == hashes[index])
                check(resolver.delete(uri, columns.joinToString(" AND ") { "$it=?" }, before[index].toTypedArray()) == 1)
                check(metadata(uri) == null)
                java.io.File(directory, "cleanup.txt").appendText("CAS deleted; absence verified $uri\n")
            }
            passed = true
            java.io.File(directory, "result.json").writeText(org.json.JSONObject().put("status", "PASS")
                .put("draftId", token).put("realImeShown", true).put("firstBackCancelled", false)
                .put("title", title).put("order", org.json.JSONArray(listOf(second, first)))
                .put("includeSpecial", true).put("secondBackCancels", 1).put("creates", 0)
                .put("sourceHashes", org.json.JSONArray(hashes)).put("cleanupComplete", true).toString(2))
        } catch (failure: Throwable) {
            java.io.File(directory, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            runCatching { compose.runOnIdle { visible.value = false } }
            if (!passed) {
                java.io.File(directory, "retained.txt").writeText("draftId=$token; retained owned URIs=$owned")
                android.util.Log.e("MANUAL_IME_FIXTURE", "Retained $token sources=$owned")
            }
        }
    }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("manual-moment-list").performScrollToNode(hasTestTag(tag))
    }
    private fun id(key: MediaKey) = "${key.volumeName}:${key.mediaStoreId}"
}
