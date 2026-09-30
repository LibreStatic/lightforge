package com.librestatic.lightforge

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Acceptance-only post-commit pause. It never mutates the application's pending-request journal. */
object ManualMomentCommitProbe {
    suspend fun afterCommit(application: Application, request: ManualMomentCreateRequest) {
        if (application.packageName != "com.librestatic.lightforge.pdfacceptance") return
        val fixture = request.title.removePrefix("Manual process ")
        if (request.title != "Manual process $fixture" || runCatching { UUID.fromString(fixture).toString() }.getOrNull() != fixture) return
        val directory = File(application.filesDir, "creation-process-$fixture")
        val armFile = File(directory, "manual-commit-arm.json")
        if (!armFile.isFile) return
        withContext(Dispatchers.IO) {
            check(directory.canonicalFile.parentFile == application.filesDir.canonicalFile)
            val arm = readSmall(armFile)
            val sources = request.draft.sources
            check(sources.size == 2 && sources.map { it.key }.distinct().size == 2)
            check(arm.getString("fixtureUuid") == fixture && arm.getString("title") == request.title && arm.getString("phase") == "ARMED")
            check(arm.get("includeSpecialMedia") == true && request.includeSpecialMedia)
            check(arm.length() == 8)
            fun longs(name: String): List<Long> = arm.getJSONArray(name).let { values -> (0 until values.length()).map(values::getLong) }
            check(sources.all { it.key.volumeName == "external_primary" })
            check(longs("sourceIds") == sources.map { it.key.mediaStoreId })
            check(longs("generationAdded") == sources.map { it.generationAdded })
            check(longs("generationModified") == sources.map { it.generationModified })
            check(request.orderedKeys == sources.map { it.key }.reversed() &&
                longs("orderedMediaIds") == request.orderedKeys.map { it.mediaStoreId })
            check(UUID.fromString(request.token).toString() == request.token && UUID.fromString(request.draft.id).toString() == request.draft.id)
            val pending = ManualMomentPendingCreateStore(File(application.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read()
            check(pending == request) { "Pending request must be durable and exact before the commit pause" }
            val gate = JSONObject().put("fixtureUuid", fixture).put("token", request.token).put("draftId", request.draft.id)
                .put("phase", "AFTER_ROOM_COMMIT").put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("requestFingerprint", fingerprint(request)).put("deadlineElapsedRealtimeMillis", SystemClock.elapsedRealtime() + 120_000L)
            persistNew(directory, "manual-commit-gate.json", gate)
            try {
                withTimeout(120_000L) {
                    while (true) {
                        val release = File(directory, "manual-commit-release.json")
                        if (release.exists()) {
                            val value = readSmall(release)
                            check(value.length() == 3 && value.getString("fixtureUuid") == fixture &&
                                value.getString("token") == request.token && value.getString("phase") == "RELEASE")
                            persistNew(directory, "manual-commit-outcome.json", JSONObject().put("fixtureUuid", fixture)
                                .put("token", request.token).put("phase", "RELEASED"))
                            break
                        }
                        delay(50)
                    }
                }
            } catch (cancelled: CancellationException) {
                persistNew(directory, "manual-commit-outcome.json", JSONObject().put("fixtureUuid", fixture)
                    .put("token", request.token).put("phase", "CANCELLED_OR_TIMED_OUT"))
                throw cancelled
            }
        }
    }

    private fun readSmall(file: File): JSONObject {
        check(file.isFile && file.length() in 1L..8192L)
        return JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun persistNew(directory: File, name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, name))
        check(!file.baseFile.exists())
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
        check(file.openRead().use { it.readBytes() }.contentEquals(bytes))
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun fingerprint(request: ManualMomentCreateRequest): String {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
            fun key(value: com.librestatic.lightforge.core.model.MediaKey) { text(value.volumeName); output.writeLong(value.mediaStoreId) }
            text("manual-v1"); output.writeInt(request.draft.sources.size)
            request.draft.sources.forEach { key(it.key); output.writeLong(it.generationAdded); output.writeLong(it.generationModified) }
            output.writeInt(request.orderedKeys.size); request.orderedKeys.forEach(::key)
            text(request.title.trim()); output.writeBoolean(request.includeSpecialMedia)
        }
        return "manual-v1:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
