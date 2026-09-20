package com.ugallery.feature.collage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray

/** Exact-fixture acceptance pause only; neither the journal nor public media is mutated here. */
object CreationCollageCommitProbe {
    suspend fun afterCommit(context: Context, receipt: CreationCollagePublicationReceipt) {
        if (context.packageName != "com.ugallery.app.pdfacceptance" || receipt.sourceIdentities.size != 2) return
        withContext(Dispatchers.IO) {
            fun sourceMetadata(identity: String): List<String>? {
                val uri = Uri.parse(identity.substringBeforeLast('@'))
                if (uri.scheme != "content" || uri.authority != "media") return null
                return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                    check(cursor.moveToFirst())
                    List(3) { cursor.getString(it).orEmpty() }.also { check(!cursor.moveToNext()) }
                }
            }
            val firstName = runCatching { sourceMetadata(receipt.sourceIdentities[0])?.firstOrNull() }.getOrNull() ?: return@withContext
            val match = Regex("creation-process-([0-9a-f-]{36})-0\\.png").matchEntire(firstName) ?: return@withContext
            val fixture = match.groupValues[1]
            check(UUID.fromString(fixture).toString() == fixture)
            val directory = File(context.filesDir, "creation-process-$fixture")
            val armFile = File(directory, "collage-publication-arm.json")
            if (!armFile.isFile) return@withContext
            check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
            val names = receipt.sourceIdentities.map { checkNotNull(sourceMetadata(it)) }
            names.forEachIndexed { index, values ->
                check(values == listOf("creation-process-$fixture-$index.png", context.packageName, "Pictures/creation-process-$fixture/"))
            }
            val arm = readSmall(armFile)
            fun strings(field: String) = arm.getJSONArray(field).let { array -> List(array.length()) { array.getString(it) } }
            check(arm.length() == 6 && arm.getInt("version") == 1 && arm.getString("fixtureUuid") == fixture && arm.getString("phase") == "ARMED")
            check(strings("sourceIdentities") == receipt.sourceIdentities && strings("sourceSha256") == receipt.sourceSha256 && strings("sourceNames") == names.map { it[0] })
            check(UUID.fromString(receipt.sessionId).toString() == receipt.sessionId && UUID.fromString(receipt.token).toString() == receipt.token)
            check(receipt.phase.name == "Published")
            val destination = checkNotNull(receipt.destination)
            val recovery = CreationCollageExporter(context).reconcile(receipt.sessionId)
            check(recovery.status.name == "Published" && recovery.receipt == receipt && recovery.resultUri == destination.uri)
            val output = JSONObject().put("uri", destination.uri).put("ownerPackage", destination.ownerPackage)
                .put("displayName", destination.displayName).put("relativePath", destination.relativePath).put("mimeType", destination.mimeType)
                .put("generationAdded", destination.generationAdded).put("generationModified", destination.generationModified)
                .put("sizeBytes", destination.sizeBytes).put("pending", destination.pending).put("trashed", destination.trashed)
            val gate = JSONObject().put("fixtureUuid", fixture).put("sessionId", receipt.sessionId).put("token", receipt.token)
                .put("phase", "AFTER_MEDIASTORE_COMMIT").put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("deadlineElapsedRealtimeMillis", SystemClock.elapsedRealtime() + 120_000L).put("destination", output)
                .put("renderSha256", receipt.renderSha256).put("renderSizeBytes", receipt.renderSizeBytes)
            persistNew(directory, "collage-publication-gate.json", gate)
            try {
                withTimeout(120_000L) {
                    while (true) {
                        val release = File(directory, "collage-publication-release.json")
                        if (release.exists()) {
                            val value = readSmall(release)
                            check(value.length() == 3 && value.getString("fixtureUuid") == fixture && value.getString("token") == receipt.token && value.getString("phase") == "RELEASE")
                            persistNew(directory, "collage-publication-outcome.json", JSONObject().put("fixtureUuid", fixture).put("token", receipt.token).put("phase", "RELEASED"))
                            break
                        }
                        delay(50)
                    }
                }
            } catch (cancelled: CancellationException) {
                persistNew(directory, "collage-publication-outcome.json", JSONObject().put("fixtureUuid", fixture).put("token", receipt.token).put("phase", "CANCELLED_OR_TIMED_OUT"))
                throw cancelled
            }
        }
    }

    /** Captures the actual request only after Android accepted startActivity; not recipient rendering. */
    @Suppress("DEPRECATION")
    fun afterHandoff(context: Context, sessionId: String, target: Intent, chooser: Intent) {
        if (context.packageName != "com.ugallery.app.pdfacceptance") return
        val receipt = CreationCollagePublicationJournal(File(context.noBackupFilesDir.canonicalFile, "collage-publications")).read(sessionId) ?: return
        if (receipt.sourceIdentities.size != 2) return
        val source = Uri.parse(receipt.sourceIdentities[0].substringBeforeLast('@'))
        val sourceName = runCatching {
            context.contentResolver.query(source, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: return
        val match = Regex("creation-process-([0-9a-f-]{36})-0\\.png").matchEntire(sourceName) ?: return
        val fixture = match.groupValues[1]
        check(UUID.fromString(fixture).toString() == fixture)
        val directory = File(context.filesDir, "creation-process-$fixture")
        if (!File(directory, "collage-publication-arm.json").isFile) return
        check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val gate = readSmall(File(directory, "collage-publication-gate.json"))
        check(gate.getString("fixtureUuid") == fixture && gate.getString("sessionId") == sessionId && gate.getString("token") == receipt.token)
        val destination = checkNotNull(receipt.destination)
        check(gate.getJSONObject("destination").getString("uri") == destination.uri && gate.getString("renderSha256") == receipt.renderSha256)
        val uri = Uri.parse(destination.uri)
        fun clips(intent: Intent): List<String> = intent.clipData?.let { clip ->
            List(clip.itemCount) { index -> checkNotNull(clip.getItemAt(index).uri).toString() }
        }.orEmpty()
        fun checkTarget(intent: Intent) {
            check(intent.type == "image/png" && intent.action == target.action)
            when (intent.action) {
                Intent.ACTION_VIEW -> check(intent.data == uri)
                Intent.ACTION_SEND -> check(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) == uri)
                else -> error("Unexpected result handoff")
            }
            check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && clips(intent) == listOf(uri.toString()))
        }
        checkTarget(target)
        check(chooser.action == Intent.ACTION_CHOOSER && chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        checkTarget(checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)))
        check(clips(chooser) == listOf(uri.toString()))
        val action = checkNotNull(target.action)
        val value = JSONObject().put("fixtureUuid", fixture).put("sessionId", sessionId).put("token", receipt.token)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("phase", "DISPATCHED")
            .put("action", action).put("uri", uri.toString()).put("mimeType", "image/png")
            .put("targetReadGrant", true).put("targetClipUris", JSONArray(clips(target)))
            .put("chooserReadGrant", true).put("chooserClipUris", JSONArray(clips(chooser)))
        persistNew(directory, "collage-publication-handoff-${action.substringAfterLast('.')}.json", value)
    }

    private fun readSmall(file: File): JSONObject {
        check(file.isFile && file.length() in 1L..8192L)
        return JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun persistNew(directory: File, name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, name))
        check(!file.baseFile.exists())
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync(); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
