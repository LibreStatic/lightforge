package com.librestatic.lightforge

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
import com.librestatic.lightforge.feature.collage.CreationCollageExporter
import com.librestatic.lightforge.feature.collage.CreationCollagePublicationPhase
import com.librestatic.lightforge.feature.collage.CreationCollagePublicationStatus
import com.librestatic.lightforge.feature.collage.CreationCollageTemplate
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Owned publication fixture; normal-app launch, process death and handoffs remain host-owned. */
class CollagePublicationProcessAppDeviceTest {
    @Test fun seedOwnedCollagePublicationForHost(): Unit = runBlocking {
        CreationProcessRestorationAppDeviceTest().seedOwnedPhotosForHostProcessRestoration()
        val (context, uuid, directory) = fixture()
        val record = read(directory, "fixture.json")
        requireFixture(record, uuid)
        val rows = record.getJSONArray("rows")
        check(rows.length() == 2)
        verifySources(context, uuid, rows)
        val names = JSONArray((0..1).map { rows.getJSONObject(it).getString("_display_name") })
        val arm = JSONObject().put("version", 1).put("fixtureUuid", uuid).put("phase", "ARMED")
            .put("sourceIdentities", identities(rows)).put("sourceSha256", hashes(rows)).put("sourceNames", names)
        persist(directory, "collage-publication-arm.json", arm)
        record.put("armCollagePublication", true).put("collageOutputBaseline", outputInventory(context))
        persist(directory, "fixture.json", record, replaceOwnedFixture = true)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "COLLAGE_PUBLICATION $uuid ARMED\n") })
    }

    @Test fun verifyOwnedCollagePublicationBeforeSourceCleanup(): Unit = runBlocking {
        val (context, uuid, directory) = fixture()
        val record = read(directory, "fixture.json")
        requireFixture(record, uuid)
        check(record.get("armCollagePublication") == true)
        val rows = record.getJSONArray("rows")
        verifySources(context, uuid, rows)
        val armBytes = File(directory, "collage-publication-arm.json").readBytes()
        val gateBytes = File(directory, "collage-publication-gate.json").readBytes()
        val arm = JSONObject(armBytes.toString(Charsets.UTF_8))
        val gate = JSONObject(gateBytes.toString(Charsets.UTF_8))
        check(arm.length() == 6 && arm.getInt("version") == 1 && arm.getString("fixtureUuid") == uuid && arm.getString("phase") == "ARMED")
        check(arm.getJSONArray("sourceIdentities").toString() == identities(rows).toString() && arm.getJSONArray("sourceSha256").toString() == hashes(rows).toString())
        check(arm.getJSONArray("sourceNames").toString() == JSONArray((0..1).map { rows.getJSONObject(it).getString("_display_name") }).toString())
        check(gate.length() == 10 && gate.getString("fixtureUuid") == uuid && gate.getString("phase") == "AFTER_MEDIASTORE_COMMIT")
        val session = gate.getString("sessionId"); val token = gate.getString("token")
        check(UUID.fromString(session).toString() == session && UUID.fromString(token).toString() == token)
        check(gate.getInt("pid") > 1 && gate.getInt("uid") == record.getInt("uid") && gate.getLong("deadlineElapsedRealtimeMillis") > 0)
        check(!File(directory, "collage-publication-outcome.json").exists() && !File(directory, "collage-publication-release.json").exists())
        val exporter = CreationCollageExporter(context)
        val recovery = exporter.reconcile(session)
        check(recovery.status == CreationCollagePublicationStatus.Published)
        val receipt = checkNotNull(recovery.receipt)
        val destination = checkNotNull(receipt.destination)
        check(receipt.phase == CreationCollagePublicationPhase.Published && receipt.sessionId == session && receipt.token == token)
        check(JSONArray(receipt.sourceIdentities).toString() == identities(rows).toString() && JSONArray(receipt.sourceSha256).toString() == hashes(rows).toString())
        check(receipt.layout.template == CreationCollageTemplate.Grid2 && receipt.layout.order == listOf(1, 0))
        check(receipt.layout.crops.size == 2 && receipt.layout.crops[0].zoom > 1f)
        check(receipt.renderSha256 == gate.getString("renderSha256") && receipt.renderSizeBytes == gate.getLong("renderSizeBytes"))
        val expected = gate.getJSONObject("destination")
        check(expected.length() == 10)
        check(destination.uri == expected.getString("uri") && recovery.resultUri == destination.uri && destination.ownerPackage == Package &&
            destination.ownerPackage == expected.getString("ownerPackage") && destination.displayName == expected.getString("displayName") &&
            destination.relativePath == OutputPath && destination.relativePath == expected.getString("relativePath") &&
            destination.mimeType == "image/png" && destination.mimeType == expected.getString("mimeType") &&
            destination.generationAdded == expected.getLong("generationAdded") && destination.generationModified == expected.getLong("generationModified") &&
            destination.sizeBytes == expected.getLong("sizeBytes") && destination.sizeBytes == receipt.renderSizeBytes &&
            !destination.pending && expected.get("pending") == false && !destination.trashed && expected.get("trashed") == false)
        val uri = Uri.parse(destination.uri)
        check(uri.toString().matches(Regex("content://media/(external|external_primary)/images/media/[1-9][0-9]*")))
        val outputId = checkNotNull(uri.lastPathSegment).toLong()
        check(destination.displayName.matches(Regex("Lightforge-collage-[0-9a-f-]{36}\\.png")))
        val handoffEvidence = JSONArray()
        var restoredPid: Int? = null
        val handoffFiles = listOf("VIEW", "SEND").map { action ->
            val file = File(directory, "collage-publication-handoff-$action.json")
            check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && file.length() in 1L..8192L)
            val bytes = file.readBytes()
            val handoff = JSONObject(bytes.toString(Charsets.UTF_8))
            check(handoff.length() == 13 && handoff.getString("fixtureUuid") == uuid && handoff.getString("sessionId") == session &&
                handoff.getString("token") == token && handoff.getString("phase") == "DISPATCHED" &&
                handoff.getString("action") == "android.intent.action.$action" && handoff.getString("uri") == destination.uri &&
                handoff.getString("mimeType") == "image/png" && handoff.get("targetReadGrant") == true && handoff.get("chooserReadGrant") == true)
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
        check(current.getLong("_id") == outputId && current.getString("owner_package_name") == Package && current.getString("relative_path") == OutputPath &&
            current.getString("_display_name") == destination.displayName && current.getString("mime_type") == "image/png" &&
            current.getLong("generation_added") == destination.generationAdded && current.getLong("generation_modified") == destination.generationModified &&
            current.getLong("_size") == destination.sizeBytes && current.getLong("is_pending") == 0L && current.getLong("is_trashed") == 0L)
        val baseline = record.getJSONArray("collageOutputBaseline")
        val inventory = outputInventory(context)
        check(inventory.length() == baseline.length() + 1)
        val others = JSONArray((0 until inventory.length()).map { inventory.getJSONObject(it) }.filter { it.getLong("_id") != outputId })
        check(others.toString() == baseline.toString()) { "Other owned outputs changed or additional outputs appeared" }
        val bytes = readOutput(context, uri, receipt.renderSizeBytes, receipt.renderSha256)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            check(bitmap.width == 2048 && bitmap.height == 2048)
            check(bitmap.getPixel(512, 1024) == Color.BLUE && bitmap.getPixel(1536, 1024) == Color.RED)
        } finally { bitmap.recycle() }
        check(outputSnapshot(context, uri).toString() == current.toString())
        verifySources(context, uuid, rows)
        val proof = JSONObject().put("fixtureUuid", uuid).put("sessionId", session).put("token", token)
            .put("gate", gate).put("handoffs", handoffEvidence).put("sourceSnapshots", rows).put("outputSnapshot", current).put("outputSha256", receipt.renderSha256)
            .put("baselineOutputInventory", baseline).put("currentOutputInventory", inventory).put("order", JSONArray(receipt.layout.order))
            .put("width", 2048).put("height", 2048).put("sourcesCurrent", true).put("status", "VERIFIED_BEFORE_DELETE")
        persist(directory, "collage-publication-predelete.json", proof)
        check(exporter.retirePublication(receipt)) { "Exact publication journal was not retired; retain output/sources" }
        check(outputSnapshot(context, uri).toString() == current.toString())
        readOutput(context, uri, receipt.renderSizeBytes, receipt.renderSha256)
        val clauses = OutputColumns.joinToString(" AND ") { "$it=?" }
        val args = OutputColumns.map { current.get(it).toString() }.toTypedArray()
        check(context.contentResolver.delete(uri, clauses, args) == 1)
        context.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { check(!it.moveToFirst()) }
        check(outputInventory(context).toString() == baseline.toString())
        verifySources(context, uuid, rows)
        val gateFiles = listOf(File(directory, "collage-publication-arm.json") to armBytes, File(directory, "collage-publication-gate.json") to gateBytes) + handoffFiles
        for ((file, expectedBytes) in gateFiles) check(file.readBytes().contentEquals(expectedBytes))
        for ((file, _) in gateFiles) check(file.delete() && !file.exists())
        syncDirectory(directory)
        val result = JSONObject().put("fixtureUuid", uuid).put("sessionId", session).put("token", token)
            .put("outputUri", destination.uri).put("outputSha256", receipt.renderSha256).put("sourcesCurrent", true)
            .put("outputVerified", true).put("outputCountOne", true).put("publicationRetired", true)
            .put("outputCleaned", true).put("gateCleaned", true).put("status", "PASS")
        persist(directory, "collage-publication-verification.json", result)
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

    private fun identities(rows: JSONArray) = JSONArray((0..1).map { index -> rows.getJSONObject(index).let {
        "content://media/external_primary/images/media/${it.getLong("_id")}@${it.getLong("generation_modified")}/${it.getLong("generation_added")}"
    } })
    private fun hashes(rows: JSONArray) = JSONArray((0..1).map { rows.getJSONObject(it).getString("sha256") })
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

    private fun outputInventory(context: Context): JSONArray = context.contentResolver.query(
        Uri.parse("content://media/external/images/media?includePending=1"), OutputColumns,
        "owner_package_name=? AND relative_path=?", arrayOf(Package, OutputPath), "_id ASC",
    )!!.use { cursor -> JSONArray().apply { while (cursor.moveToNext()) put(cursorSnapshot(cursor)) } }

    private fun cursorSnapshot(cursor: android.database.Cursor) = JSONObject().apply {
        for (field in OutputColumns) {
            val index = cursor.getColumnIndexOrThrow(field)
            put(field, if (field in NumericColumns) cursor.getLong(index) else cursor.getString(index))
        }
    }

    private fun readOutput(context: Context, uri: Uri, size: Long, hash: String): ByteArray {
        check(size in 32L..67_108_864L && hash.matches(Regex("[0-9a-f]{64}")))
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
        check(bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map(Int::toByte))
        return bytes
    }

    private fun verifySources(context: Context, uuid: String, rows: JSONArray) {
        check(rows.length() == 2 && rows.getJSONObject(0).getLong("_id") != rows.getJSONObject(1).getLong("_id"))
        val columns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name", "generation_added", "generation_modified", "is_pending", "_data")
        for (index in 0..1) {
            val row = rows.getJSONObject(index); val id = row.getLong("_id")
            check(row.getInt("ordinal") == index && row.getString("uri") == "content://media/external/images/media/$id")
            check(row.getString("owner_package_name") == Package && row.getString("relative_path") == "Pictures/creation-process-$uuid/" &&
                row.getString("_display_name") == "creation-process-$uuid-$index.png" && row.getLong("is_pending") == 0L)
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
        const val Package = "com.librestatic.lightforge.pdfacceptance"
        const val OutputPath = "Pictures/Lightforge/Collage/"
        private val NumericColumns = setOf("_id", "generation_added", "generation_modified", "is_pending", "is_trashed", "_size")
        private val OutputColumns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name", "generation_added", "generation_modified", "is_pending", "is_trashed", "mime_type", "_size")
    }
}
