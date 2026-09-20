package com.ugallery.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.feature.motionphotos.MotionPhotoPublication
import com.ugallery.feature.motionphotos.MotionPhotoPublicationPhase
import com.ugallery.feature.motionphotos.MotionPhotoPublicationStatus
import com.ugallery.feature.motionphotos.MotionPhotoPublicationKind
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Owned publication fixture; normal-app launch, process death and handoffs remain host-owned. */
class MotionPublicationProcessAppDeviceTest {
    @Test fun seedOwnedMotionPublicationForHost(): Unit = runBlocking {
        CreationProcessRestorationAppDeviceTest().seedOwnedMotionForHostProcessRestoration()
        val (context, uuid, directory) = fixture()
        val record = read(directory, "fixture.json")
        requireFixture(record, uuid)
        val rows = record.getJSONArray("rows")
        check(rows.length() == 1)
        verifySources(context, uuid, rows)
        val row = rows.getJSONObject(0)
        val kind = kind()
        val sourceBytes = sourceBytes(context, row, record)
        val duration = withClip(directory, record, sourceBytes) { clip -> durationUs(clip) }
        val selected = if (kind == MotionPhotoPublicationKind.Frame) (duration - 1) * 4 / 5 else null
        val arm = JSONObject().put("version", 1).put("fixtureUuid", uuid).put("phase", "ARMED")
            .put("sourceIdentity", identity(row)).put("sourceSha256", row.getString("sha256"))
            .put("sourceName", row.getString("_display_name")).put("kind", kind.name).put("selectedTimeUs", selected ?: JSONObject.NULL)
        persist(directory, "motion-publication-arm.json", arm)
        record.put("armMotionPublication", true).put("motionPublicationKind", kind.name)
            .put("motionPublicationDurationUs", duration).put("motionPublicationSelectedTimeUs", selected ?: JSONObject.NULL)
            .put("motionOutputBaseline", inventories(context))
        persist(directory, "fixture.json", record, replaceOwnedFixture = true)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "MOTION_PUBLICATION $uuid ARMED\n") })
    }

    @Test fun verifyOwnedMotionPublicationBeforeSourceCleanup(): Unit = runBlocking {
        val (context, uuid, directory) = fixture()
        val record = read(directory, "fixture.json")
        requireFixture(record, uuid)
        val kind = kind(); val outputPath = path(kind); val mime = mime(kind)
        check(record.get("armMotionPublication") == true && record.getString("motionPublicationKind") == kind.name)
        val rows = record.getJSONArray("rows")
        verifySources(context, uuid, rows)
        val armBytes = File(directory, "motion-publication-arm.json").readBytes()
        val gateBytes = File(directory, "motion-publication-gate.json").readBytes()
        val arm = JSONObject(armBytes.toString(Charsets.UTF_8))
        val gate = JSONObject(gateBytes.toString(Charsets.UTF_8))
        val row = rows.getJSONObject(0)
        val sourceBytes = sourceBytes(context, row, record)
        val duration = withClip(directory, record, sourceBytes) { durationUs(it) }
        val selected = if (kind == MotionPhotoPublicationKind.Frame) (duration - 1) * 4 / 5 else null
        check(arm.length() == 8 && arm.getInt("version") == 1 && arm.getString("fixtureUuid") == uuid && arm.getString("phase") == "ARMED")
        check(arm.getString("sourceIdentity") == identity(row) && arm.getString("sourceSha256") == row.getString("sha256") &&
            arm.getString("sourceName") == row.getString("_display_name") && arm.getString("kind") == kind.name)
        check(if (selected == null) arm.isNull("selectedTimeUs") else arm.getLong("selectedTimeUs") == selected)
        check(gate.length() == 10 && gate.getString("fixtureUuid") == uuid && gate.getString("phase") == "AFTER_MEDIASTORE_COMMIT")
        val session = gate.getString("publicationId"); val token = gate.getString("token")
        check(UUID.fromString(session).toString() == session && UUID.fromString(token).toString() == token)
        check(gate.getInt("pid") > 1 && gate.getInt("uid") == record.getInt("uid") && gate.getLong("deadlineElapsedRealtimeMillis") > 0)
        check(!File(directory, "motion-publication-outcome.json").exists() && !File(directory, "motion-publication-release.json").exists())
        val exporter = MotionPhotoPublication(context)
        val recovery = exporter.reconcile(session)
        check(recovery.status == MotionPhotoPublicationStatus.Published)
        val receipt = checkNotNull(recovery.receipt)
        val destination = checkNotNull(receipt.destination)
        check(receipt.phase == MotionPhotoPublicationPhase.Published && receipt.publicationId == session && receipt.token == token)
        check(receipt.sourceIdentity == identity(row) && receipt.sourceUri == "content://media/external_primary/images/media/${row.getLong("_id")}" &&
            receipt.generationModified == row.getLong("generation_modified") && receipt.generationAdded == row.getLong("generation_added") &&
            receipt.sourceSha256 == row.getString("sha256") && receipt.kind == kind && receipt.selectedTimeUs == selected && receipt.durationUs == duration)
        check(receipt.renderSha256 == gate.getString("renderSha256") && receipt.renderSizeBytes == gate.getLong("renderSizeBytes"))
        val expected = gate.getJSONObject("destination")
        check(expected.length() == 10)
        check(destination.uri == expected.getString("uri") && recovery.resultUri == destination.uri && destination.ownerPackage == Package &&
            destination.ownerPackage == expected.getString("ownerPackage") && destination.displayName == expected.getString("displayName") &&
            destination.relativePath == outputPath && destination.relativePath == expected.getString("relativePath") &&
            destination.mimeType == mime && destination.mimeType == expected.getString("mimeType") &&
            destination.generationAdded == expected.getLong("generationAdded") && destination.generationModified == expected.getLong("generationModified") &&
            destination.sizeBytes == expected.getLong("sizeBytes") && destination.sizeBytes == receipt.renderSizeBytes &&
            !destination.pending && expected.get("pending") == false && !destination.trashed && expected.get("trashed") == false)
        val uri = Uri.parse(destination.uri)
        check(uri.toString().matches(Regex("content://media/(external|external_primary)/${if (kind == MotionPhotoPublicationKind.Frame) "images" else "video"}/media/[1-9][0-9]*")))
        val outputId = checkNotNull(uri.lastPathSegment).toLong()
        check(destination.displayName == "UGallery-Motion-$token.${if (kind == MotionPhotoPublicationKind.Frame) "jpg" else "mp4"}")
        val handoffEvidence = JSONArray()
        var restoredPid: Int? = null
        val handoffFiles = listOf("VIEW", "SEND").map { action ->
            val file = File(directory, "motion-publication-handoff-$action.json")
            check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && file.length() in 1L..8192L)
            val bytes = file.readBytes()
            val handoff = JSONObject(bytes.toString(Charsets.UTF_8))
            check(handoff.length() == 13 && handoff.getString("fixtureUuid") == uuid && handoff.getString("publicationId") == session &&
                handoff.getString("token") == token && handoff.getString("phase") == "DISPATCHED" &&
                handoff.getString("action") == "android.intent.action.$action" && handoff.getString("uri") == destination.uri &&
                handoff.getString("mimeType") == mime && handoff.get("targetReadGrant") == true && handoff.get("chooserReadGrant") == true)
            val pid = handoff.getInt("pid")
            check(pid > 1 && pid != gate.getInt("pid") && handoff.getInt("uid") == record.getInt("uid"))
            if (restoredPid == null) restoredPid = pid else check(restoredPid == pid)
            for (field in listOf("targetClipUris", "chooserClipUris")) {
                val clips = handoff.getJSONArray(field)
                check(clips.length() == 1 && clips.get(0) == destination.uri)
            }
            handoffEvidence.put(handoff)
            file to bytes
        }
        val current = outputSnapshot(context, uri)
        check(current.getLong("_id") == outputId && current.getString("owner_package_name") == Package && current.getString("relative_path") == outputPath &&
            current.getString("_display_name") == destination.displayName && current.getString("mime_type") == mime &&
            current.getLong("generation_added") == destination.generationAdded && current.getLong("generation_modified") == destination.generationModified &&
            current.getLong("_size") == destination.sizeBytes && current.getLong("is_pending") == 0L && current.getLong("is_trashed") == 0L)
        val baselineAll = record.getJSONObject("motionOutputBaseline")
        val baseline = baselineAll.getJSONArray(outputPath)
        val inventory = outputInventory(context, kind)
        val otherKind = if (kind == MotionPhotoPublicationKind.Frame) MotionPhotoPublicationKind.Clip else MotionPhotoPublicationKind.Frame
        check(outputInventory(context, otherKind).toString() == baselineAll.getJSONArray(path(otherKind)).toString())
        check(inventory.length() == baseline.length() + 1)
        val others = JSONArray((0 until inventory.length()).map { inventory.getJSONObject(it) }.filter { it.getLong("_id") != outputId })
        check(others.toString() == baseline.toString()) { "Other owned outputs changed or additional outputs appeared" }
        val bytes = readOutput(context, uri, receipt.renderSizeBytes, receipt.renderSha256)
        val mediaProof = withClip(directory, record, sourceBytes) { clip ->
            if (kind == MotionPhotoPublicationKind.Clip) {
                check(bytes.contentEquals(clip.readBytes())) { "Published MP4 differs from exact source clip slice" }
                JSONObject().put("kind", kind.name).put("durationUs", durationUs(clip)).put("clipSliceSha256", digest(bytes))
            } else {
                val retriever = android.media.MediaMetadataRetriever()
                val frame = try {
                    retriever.setDataSource(clip.path)
                    checkNotNull(retriever.getScaledFrameAtTime(checkNotNull(selected), android.media.MediaMetadataRetriever.OPTION_CLOSEST, 4096, 4096))
                } finally { retriever.release() }
                try {
                    val expectedBytes = java.io.ByteArrayOutputStream().also { check(frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
                    check(bytes.contentEquals(expectedBytes)) { "JPEG differs from exact selected frame encoding" }
                    val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try {
                        check(decoded.width == frame.width && decoded.height == frame.height)
                        JSONObject().put("kind", kind.name).put("selectedTimeUs", selected).put("durationUs", duration)
                            .put("width", decoded.width).put("height", decoded.height).put("expectedFrameJpegSha256", digest(expectedBytes))
                    } finally { decoded.recycle() }
                } finally { frame.recycle() }
            }
        }
        // Reuse the accepted read-only Room/keyframe proof while the exact source still exists.
        CreationProcessRestorationAppDeviceTest().verifyOwnedMotionHasNoSavedKeyFrame()
        val keyframeProof = read(directory, "keyframe-verification.json")
        check(keyframeProof.getString("fixtureUuid") == uuid && keyframeProof.getLong("mediaId") == row.getLong("_id") &&
            keyframeProof.get("sourceCurrent") == true && keyframeProof.get("keyFrameRowsVerified") == true &&
            keyframeProof.get("baselineKeyFrameAbsent") == true && keyframeProof.getString("status") == "PASS")
        check(outputSnapshot(context, uri).toString() == current.toString())
        verifySources(context, uuid, rows)
        val proof = JSONObject().put("fixtureUuid", uuid).put("publicationId", session).put("token", token)
            .put("gate", gate).put("handoffs", handoffEvidence).put("sourceSnapshots", rows).put("outputSnapshot", current).put("outputSha256", receipt.renderSha256)
            .put("baselineOutputInventory", baselineAll).put("currentOutputInventory", inventories(context)).put("mediaProof", mediaProof)
            .put("keyFrameProof", keyframeProof).put("sourcesCurrent", true).put("status", "VERIFIED_BEFORE_DELETE")
        persist(directory, "motion-publication-predelete.json", proof)
        check(exporter.retire(receipt)) { "Exact publication journal was not retired; retain output/sources" }
        check(outputSnapshot(context, uri).toString() == current.toString())
        readOutput(context, uri, receipt.renderSizeBytes, receipt.renderSha256)
        val clauses = OutputColumns.joinToString(" AND ") { "$it=?" }
        val args = OutputColumns.map { current.get(it).toString() }.toTypedArray()
        check(context.contentResolver.delete(uri, clauses, args) == 1)
        context.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { check(!it.moveToFirst()) }
        check(inventories(context).toString() == baselineAll.toString())
        verifySources(context, uuid, rows)
        val gateFiles = listOf(File(directory, "motion-publication-arm.json") to armBytes, File(directory, "motion-publication-gate.json") to gateBytes) + handoffFiles
        for ((file, expectedBytes) in gateFiles) check(file.readBytes().contentEquals(expectedBytes))
        for ((file, _) in gateFiles) check(file.delete() && !file.exists())
        syncDirectory(directory)
        val result = JSONObject().put("fixtureUuid", uuid).put("publicationId", session).put("token", token)
            .put("outputUri", destination.uri).put("outputSha256", receipt.renderSha256).put("sourcesCurrent", true)
            .put("outputVerified", true).put("outputCountOne", true).put("publicationRetired", true)
            .put("outputCleaned", true).put("gateCleaned", true).put("status", "PASS")
        persist(directory, "motion-publication-verification.json", result)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$result\n") })
    }

    private fun fixture(): Triple<Context, String, File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == Package)
        val requested = InstrumentationRegistry.getArguments().getString("fixtureUuid")
        org.junit.Assume.assumeTrue("Requires the exact owned publication host fixture", requested != null)
        val uuid = requireNotNull(requested)
        require(UUID.fromString(uuid).toString() == uuid)
        val directory = File(context.filesDir, "creation-process-$uuid")
        check(directory.isDirectory && directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
        return Triple(context, uuid, directory)
    }

    private fun requireFixture(record: JSONObject, uuid: String) {
        check(record.getString("fixtureUuid") == uuid && record.getString("package") == Package && record.getString("state") == "SEEDED")
    }

    private fun kind(): MotionPhotoPublicationKind {
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("motionPublicationKind"))
        return MotionPhotoPublicationKind.valueOf(name).also { check(name in listOf("Frame", "Clip")) }
    }
    private fun path(kind: MotionPhotoPublicationKind) = if (kind == MotionPhotoPublicationKind.Frame) "Pictures/UGallery/Motion/" else "Movies/UGallery/Motion/"
    private fun mime(kind: MotionPhotoPublicationKind) = if (kind == MotionPhotoPublicationKind.Frame) "image/jpeg" else "video/mp4"
    private fun identity(row: JSONObject) = "content://media/external_primary/images/media/${row.getLong("_id")}@${row.getLong("generation_modified")}/${row.getLong("generation_added")}"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sourceBytes(context: Context, row: JSONObject, record: JSONObject): ByteArray {
        val bytes = context.contentResolver.openInputStream(Uri.parse(row.getString("uri")))!!.use { it.readBytes() }
        check(bytes.size.toLong() == record.getLong("byteSize") && digest(bytes) == row.getString("sha256"))
        return bytes
    }
    private fun <T> withClip(directory: File, record: JSONObject, source: ByteArray, action: (File) -> T): T {
        val offset = record.getInt("videoOffset"); val length = record.getInt("videoLength")
        check(offset > 0 && length > 0 && offset.toLong() + length == source.size.toLong())
        val bytes = source.copyOfRange(offset, offset + length)
        val file = File.createTempFile("owned-motion-proof-", ".mp4", directory)
        try { file.writeBytes(bytes); check(file.readBytes().contentEquals(bytes)); return action(file) }
        finally { check(file.readBytes().contentEquals(bytes)); check(file.delete() && !file.exists()) }
    }
    private fun durationUs(clip: File): Long {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(clip.path)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .filter { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            check(tracks.size == 1)
            return tracks.single().getLong(android.media.MediaFormat.KEY_DURATION).also { check(it > 1) }
        } finally { extractor.release() }
    }
    private fun read(directory: File, name: String) = JSONObject(AtomicFile(File(directory, name)).openRead().use { it.readBytes().toString(Charsets.UTF_8) })

    private fun persist(directory: File, name: String, value: JSONObject, replaceOwnedFixture: Boolean = false) {
        check(!replaceOwnedFixture || name == "fixture.json")
        val file = AtomicFile(File(directory, name))
        check(replaceOwnedFixture || !file.baseFile.exists())
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
        check(file.openRead().use { it.readBytes() }.contentEquals(bytes)); syncDirectory(directory)
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun outputSnapshot(context: Context, uri: Uri): JSONObject = context.contentResolver.query(uri, OutputColumns, null, null, null)!!.use {
        check(it.moveToFirst()); val row = cursorSnapshot(it); check(!it.moveToNext()); row
    }

    private fun outputInventory(context: Context, kind: MotionPhotoPublicationKind): JSONArray = context.contentResolver.query(
        Uri.parse("content://media/external/${if (kind == MotionPhotoPublicationKind.Frame) "images" else "video"}/media?includePending=1"), OutputColumns,
        "owner_package_name=? AND relative_path=?", arrayOf(Package, path(kind)), "_id ASC",
    )!!.use { cursor -> JSONArray().apply { while (cursor.moveToNext()) put(cursorSnapshot(cursor)) } }

    private fun inventories(context: Context) = JSONObject().apply {
        put(path(MotionPhotoPublicationKind.Frame), outputInventory(context, MotionPhotoPublicationKind.Frame))
        put(path(MotionPhotoPublicationKind.Clip), outputInventory(context, MotionPhotoPublicationKind.Clip))
    }

    private fun cursorSnapshot(cursor: android.database.Cursor) = JSONObject().apply {
        for (field in OutputColumns) {
            val index = cursor.getColumnIndexOrThrow(field)
            put(field, if (field in NumericColumns) cursor.getLong(index) else cursor.getString(index))
        }
    }

    private fun readOutput(context: Context, uri: Uri, size: Long, hash: String): ByteArray {
        // Acceptance fixture reader bound only; the product supports larger Motion sources/exports.
        check(size in 32L..67_108_864L && hash.matches(Regex("[0-9a-f]{64}"))) { "Owned Motion fixture exceeds its reader bound" }
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size().toLong() + count <= size) { "Output exceeds the exact published size" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(bytes.size.toLong() == size && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hash)
        return bytes
    }

    private fun verifySources(context: Context, uuid: String, rows: JSONArray) {
        check(rows.length() == 1)
        val columns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name", "generation_added", "generation_modified", "is_pending", "_data")
        for (index in 0..0) {
            val row = rows.getJSONObject(index); val id = row.getLong("_id")
            check(row.getInt("ordinal") == index && row.getString("uri") == "content://media/external/images/media/$id")
            check(row.getString("owner_package_name") == Package && row.getString("relative_path") == "Pictures/creation-process-$uuid/" &&
                row.getString("_display_name") == "creation-process-$uuid-$index.jpg" && row.getLong("is_pending") == 0L)
            val uri = Uri.parse(row.getString("uri"))
            fun snapshot() = context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
                check(cursor.moveToFirst())
                for (field in columns) {
                    val position = cursor.getColumnIndexOrThrow(field)
                    if (field in NumericColumns) check(cursor.getLong(position) == row.getLong(field))
                    else check(cursor.getString(position) == row.getString(field))
                }
                check(!cursor.moveToNext())
            }
            snapshot()
            val hash = context.contentResolver.openInputStream(uri)!!.use { input ->
                MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
            }
            check(hash == row.getString("sha256")); snapshot()
        }
    }

    companion object {
        const val Package = "com.ugallery.app.pdfacceptance"
        private val NumericColumns = setOf("_id", "generation_added", "generation_modified", "is_pending", "is_trashed", "_size")
        private val OutputColumns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name", "generation_added", "generation_modified", "is_pending", "is_trashed", "mime_type", "_size")
    }
}
