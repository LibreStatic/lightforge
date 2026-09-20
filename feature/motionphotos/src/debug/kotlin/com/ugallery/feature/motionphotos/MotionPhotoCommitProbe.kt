package com.ugallery.feature.motionphotos

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
object MotionPhotoCommitProbe {
    suspend fun afterCommit(context: Context, receipt: MotionPhotoPublicationReceipt) {
        if (context.packageName != "com.ugallery.app.pdfacceptance") return
        withContext(Dispatchers.IO) {
            fun sourceMetadata(): List<String>? {
                val uri = Uri.parse(receipt.sourceUri)
                if (uri.scheme != "content" || uri.authority != "media") return null
                return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                    check(cursor.moveToFirst())
                    List(3) { cursor.getString(it).orEmpty() }.also { check(!cursor.moveToNext()) }
                }
            }
            val names = runCatching { sourceMetadata() }.getOrNull() ?: return@withContext
            val match = Regex("creation-process-([0-9a-f-]{36})-0\\.jpg").matchEntire(names[0]) ?: return@withContext
            val fixture = match.groupValues[1]
            check(UUID.fromString(fixture).toString() == fixture)
            val directory = File(context.filesDir, "creation-process-$fixture")
            val armFile = File(directory, "motion-publication-arm.json")
            if (!armFile.isFile) return@withContext
            check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
            check(names == listOf("creation-process-$fixture-0.jpg", context.packageName, "Pictures/creation-process-$fixture/"))
            val arm = readSmall(armFile)
            check(arm.length() == 8 && arm.getInt("version") == 1 && arm.getString("fixtureUuid") == fixture && arm.getString("phase") == "ARMED")
            check(arm.getString("sourceIdentity") == receipt.sourceIdentity && arm.getString("sourceSha256") == receipt.sourceSha256 && arm.getString("sourceName") == names[0])
            check(arm.getString("kind") == receipt.kind.name)
            if (receipt.kind == MotionPhotoPublicationKind.Frame) {
                check(!arm.isNull("selectedTimeUs") && arm.getLong("selectedTimeUs") == receipt.selectedTimeUs)
                check(receipt.selectedTimeUs == (receipt.durationUs - 1) * 4 / 5)
            } else check(arm.isNull("selectedTimeUs") && receipt.selectedTimeUs == null)
            check(UUID.fromString(receipt.publicationId).toString() == receipt.publicationId && UUID.fromString(receipt.token).toString() == receipt.token)
            check(receipt.phase == MotionPhotoPublicationPhase.Published)
            val destination = checkNotNull(receipt.destination)
            val recovery = MotionPhotoPublication(context).reconcile(receipt.publicationId)
            check(recovery.status.name == "Published" && recovery.receipt == receipt && recovery.resultUri == destination.uri)
            val output = JSONObject().put("uri", destination.uri).put("ownerPackage", destination.ownerPackage)
                .put("displayName", destination.displayName).put("relativePath", destination.relativePath).put("mimeType", destination.mimeType)
                .put("generationAdded", destination.generationAdded).put("generationModified", destination.generationModified)
                .put("sizeBytes", destination.sizeBytes).put("pending", destination.pending).put("trashed", destination.trashed)
            val gate = JSONObject().put("fixtureUuid", fixture).put("publicationId", receipt.publicationId).put("token", receipt.token)
                .put("phase", "AFTER_MEDIASTORE_COMMIT").put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("deadlineElapsedRealtimeMillis", SystemClock.elapsedRealtime() + 120_000L).put("destination", output)
                .put("renderSha256", receipt.renderSha256).put("renderSizeBytes", receipt.renderSizeBytes)
            persistNew(directory, "motion-publication-gate.json", gate)
            try {
                withTimeout(120_000L) {
                    while (true) {
                        val release = File(directory, "motion-publication-release.json")
                        if (release.exists()) {
                            val value = readSmall(release)
                            check(value.length() == 3 && value.getString("fixtureUuid") == fixture && value.getString("token") == receipt.token && value.getString("phase") == "RELEASE")
                            persistNew(directory, "motion-publication-outcome.json", JSONObject().put("fixtureUuid", fixture).put("token", receipt.token).put("phase", "RELEASED"))
                            break
                        }
                        delay(50)
                    }
                }
            } catch (cancelled: CancellationException) {
                persistNew(directory, "motion-publication-outcome.json", JSONObject().put("fixtureUuid", fixture).put("token", receipt.token).put("phase", "CANCELLED_OR_TIMED_OUT"))
                throw cancelled
            }
        }
    }

    /** Captures the actual request only after Android accepted startActivity; not recipient rendering. */
    @Suppress("DEPRECATION")
    fun afterHandoff(context: Context, publicationId: String, target: Intent, chooser: Intent) {
        if (context.packageName != "com.ugallery.app.pdfacceptance") return
        val receipt = MotionPhotoPublicationJournal(File(context.noBackupFilesDir.canonicalFile, "motion-publications")).read(publicationId) ?: return
        val source = Uri.parse(receipt.sourceUri)
        val sourceName = runCatching {
            context.contentResolver.query(source, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: return
        val match = Regex("creation-process-([0-9a-f-]{36})-0\\.jpg").matchEntire(sourceName) ?: return
        val fixture = match.groupValues[1]
        check(UUID.fromString(fixture).toString() == fixture)
        val directory = File(context.filesDir, "creation-process-$fixture")
        if (!File(directory, "motion-publication-arm.json").isFile) return
        check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val gate = readSmall(File(directory, "motion-publication-gate.json"))
        check(gate.getString("fixtureUuid") == fixture && gate.getString("publicationId") == publicationId && gate.getString("token") == receipt.token)
        val destination = checkNotNull(receipt.destination)
        check(gate.getJSONObject("destination").getString("uri") == destination.uri && gate.getString("renderSha256") == receipt.renderSha256)
        val uri = Uri.parse(destination.uri)
        val mime = if (receipt.kind == MotionPhotoPublicationKind.Clip) "video/mp4" else "image/jpeg"
        fun clips(intent: Intent): List<String> = intent.clipData?.let { clip ->
            List(clip.itemCount) { index -> checkNotNull(clip.getItemAt(index).uri).toString() }
        }.orEmpty()
        fun checkTarget(intent: Intent) {
            check(intent.type == mime && intent.action == target.action)
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
        val value = JSONObject().put("fixtureUuid", fixture).put("publicationId", publicationId).put("token", receipt.token)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("phase", "DISPATCHED")
            .put("action", action).put("uri", uri.toString()).put("mimeType", mime)
            .put("targetReadGrant", true).put("targetClipUris", JSONArray(clips(target)))
            .put("chooserReadGrant", true).put("chooserClipUris", JSONArray(clips(chooser)))
        persistNew(directory, "motion-publication-handoff-${action.substringAfterLast('.')}.json", value)
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
