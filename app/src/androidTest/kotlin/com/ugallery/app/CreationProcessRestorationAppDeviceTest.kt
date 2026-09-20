package com.ugallery.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Seeds exit before normal-app launch; final verifiers run only after host UI checks finish. */
class CreationProcessRestorationAppDeviceTest {
    @Test fun seedOwnedPhotosForHostProcessRestoration(): Unit = runBlocking { seed(motion = false) }
    @Test fun seedOwnedMotionForHostProcessRestoration(): Unit = runBlocking { seed(motion = true) }

    @Test fun seedOwnedVideoForHostProcessRestoration(): Unit = runBlocking { seed(motion = false, video = true) }

    private suspend fun seed(motion: Boolean, video: Boolean = false) {
        require(!motion || !video)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == Package)
        val requestedUuid = InstrumentationRegistry.getArguments().getString("fixtureUuid")
        org.junit.Assume.assumeTrue("Run this owned seed with the process-restoration host", requestedUuid != null)
        val uuid = requireNotNull(requestedUuid)
        require(UUID.fromString(uuid).toString() == uuid)
        val name = "creation-process-$uuid"
        val directory = File(context.filesDir, name)
        check(!directory.exists() && directory.mkdir())
        val manifest = AtomicFile(File(directory, "fixture.json"))
        val rows = JSONArray()
        val record = JSONObject().put("version", 1).put("fixtureUuid", uuid).put("package", Package)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("rows", rows)
            .put("state", "PREPARING").put("exportExecuted", false)
        fun persist() {
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            val output = manifest.startWrite()
            try { output.write(bytes); output.fd.sync(); manifest.finishWrite(output) }
            catch (failure: Throwable) { manifest.failWrite(output); throw failure }
            check(manifest.openRead().use { it.readBytes() }.contentEquals(bytes))
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        persist()
        val resolver = context.contentResolver
        val videoBytes = if (video) instrumentation.context.assets.open("motion_fixture.mp4").use { it.readBytes() } else null
        val motionFixture = if (motion) buildMotionFixture(context) else null
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATA)
        fun snapshot(uri: Uri, row: JSONObject) {
            resolver.query(uri, projection, null, null, null)!!.use { cursor ->
                check(cursor.moveToFirst())
                for (column in projection) {
                    val index = cursor.getColumnIndexOrThrow(column)
                    row.put(column, if (column in setOf(MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
                            MediaStore.MediaColumns.IS_PENDING)) cursor.getLong(index) else cursor.getString(index))
                }
                check(!cursor.moveToNext())
            }
            check(row.getString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) == Package)
            persist()
        }
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        }
        try {
            instrumentation.uiAutomation.grantRuntimePermission(Package,
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    if (video) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_MEDIA_IMAGES
                }
                else android.Manifest.permission.READ_EXTERNAL_STORAGE)
            (if (motion || video) listOf(Color.MAGENTA) else listOf(Color.RED, Color.BLUE)).forEachIndexed { index, color ->
                val uri = requireNotNull(resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name-$index.${if (video) "mp4" else if (motion) "jpg" else "png"}")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else if (motion) "image/jpeg" else "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (video) "Movies" else "Pictures"}/$name/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                val row = JSONObject().put("uri", uri.toString()).put("ordinal", index)
                rows.put(row)
                snapshot(uri, row) // Journal this exact owned pending row before writing its contents.
                if (videoBytes != null) {
                    resolver.openOutputStream(uri)!!.use { it.write(videoBytes) }
                } else if (motionFixture != null) {
                    resolver.openOutputStream(uri)!!.use { it.write(motionFixture.bytes) }
                } else {
                    val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(color)
                        resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                row.put("sha256", hash(uri)); snapshot(uri, row)
            }
            if (motionFixture != null) {
                val db = com.ugallery.core.database.GalleryDatabaseFactory.open(context)
                try { check(db.motionKeyFrameDao().get("external_primary", rows.getJSONObject(0).getLong("_id")) == null) }
                finally { db.close() }
                record.put("byteSize", motionFixture.bytes.size)
                    .put("videoOffset", motionFixture.videoOffset).put("videoLength", motionFixture.videoLength)
                    .put("motionLabel", context.getString(com.ugallery.feature.motionphotos.R.string.motion_title))
                    .put("playLabel", context.getString(com.ugallery.feature.motionphotos.R.string.motion_play))
                    .put("expectedFrame4TimeLabel", context.getString(com.ugallery.feature.motionphotos.R.string.motion_time,
                        ((motionFixture.durationUs - 1) * 4 / 5) / 1000, motionFixture.durationUs / 1000))
                    .put("viewerMoreLabel", context.getString(com.ugallery.feature.viewer.R.string.viewer_more))
                    .put("viewerDetailsLabel", context.getString(com.ugallery.feature.viewer.R.string.viewer_details))
                    .put("viewerPhotoLabel", context.getString(com.ugallery.feature.viewer.R.string.viewer_photo_description))
                    .put("baselineKeyFrameAbsent", true)
            }
            if (videoBytes != null) {
                val uri = Uri.parse(rows.getJSONObject(0).getString("uri"))
                val duration = resolver.query(uri, arrayOf(MediaStore.Video.VideoColumns.DURATION), null, null, null)!!.use {
                    check(it.moveToFirst()); it.getLong(0).also { value -> check(value > 2_000) }
                }
                record.put("byteSize", videoBytes.size).put("durationMillis", duration)
                    .put("viewerEditLabel", context.getString(com.ugallery.feature.viewer.R.string.viewer_edit))
                    .put("viewerMoreLabel", context.getString(com.ugallery.feature.viewer.R.string.viewer_more))
                    .put("viewerDetailsLabel", context.getString(com.ugallery.feature.viewer.R.string.viewer_details))
                    .put("editorAudioLabel", context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_audio))
                    .put("editorOriginalAudioLabel", context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_original_audio))
                    .put("editorPlayLabel", context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_play))
                    .put("editorPauseLabel", context.getString(com.ugallery.feature.videoeditor.R.string.video_editor_pause))
                    .put("editorDiscardTitleLabel", context.getString(R.string.editor_discard_title))
                    .put("editorDiscardConfirmLabel", context.getString(R.string.editor_discard_confirm))
            }
            if (!motion && !video) {
                val db = com.ugallery.core.database.GalleryDatabaseFactory.open(context)
                try {
                    db.withTransaction {
                        check(manualFixtureMomentIds(db, uuid, rows).isEmpty()) {
                            "Manual fixture title or sources already belong to a memory"
                        }
                    }
                } finally { db.close() }
                record.put("manualPhoto1Label", context.getString(com.ugallery.feature.collections.R.string.manual_moment_photo, 1, 2))
                    .put("manualPhoto2Label", context.getString(com.ugallery.feature.collections.R.string.manual_moment_photo, 2, 2))
                    .put("baselineManualMemoryAbsent", true)
                    .put("manualMemoryBaseline", JSONObject().put("title", "Manual process $uuid")
                        .put("attributableMomentIds", JSONArray()).put("sourceCount", 2))
            }
            val armCommit = InstrumentationRegistry.getArguments().getString("armManualCommitGap")
            require(armCommit == null || armCommit == "true")
            if (armCommit == "true") {
                check(!motion && !video)
                check(ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending")).read() == null) {
                    "Preserve an existing pending manual memory request"
                }
                val arm = AtomicFile(File(directory, "manual-commit-arm.json"))
                check(!arm.baseFile.exists())
                val bytes = manualFixtureCommitArm(uuid, rows).toString().toByteArray(Charsets.UTF_8)
                val output = arm.startWrite()
                try { output.write(bytes); output.fd.sync(); arm.finishWrite(output) }
                catch (failure: Throwable) { arm.failWrite(output); throw failure }
                check(arm.openRead().use { it.readBytes() }.contentEquals(bytes))
                record.put("armManualCommitGap", true)
            }
            record.put("state", "SEEDED")
                .put("previewLabel", context.getString(com.ugallery.feature.videoeditor.R.string.memory_video_preview))
                .put("position1", context.getString(com.ugallery.feature.videoeditor.R.string.memory_video_position, 1, 2))
                .put("position2", context.getString(com.ugallery.feature.videoeditor.R.string.memory_video_position, 2, 2))
                .put("selectionLabel", context.getString(R.string.selection_count, 2))
                .put("photosLabel", context.getString(R.string.nav_photos))
                .put("createLabel", context.getString(R.string.nav_create))
                .put("declineLabel", context.getString(com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline))
            persist()
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "CREATION_PROCESS $uuid SEEDED\n") })
        } catch (failure: Throwable) {
            record.put("state", "SETUP_FAILED").put("failure", failure.stackTraceToString()); persist()
            throw failure
        }
        // Host cleanup uses exact journaled rows; this stage creates no Activity. Motion checks its exact preexisting Room key.
    }
    private data class MotionFixture(val bytes: ByteArray, val videoOffset: Int, val videoLength: Int, val durationUs: Long)

    private fun buildMotionFixture(context: android.content.Context): MotionFixture {
        val clip = InstrumentationRegistry.getInstrumentation().context.assets.open("motion_fixture.mp4").use { it.readBytes() }
        val temporary = File.createTempFile("process-motion-", ".mp4", context.cacheDir)
        val extractor = android.media.MediaExtractor()
        val duration = try {
            temporary.writeBytes(clip)
            extractor.setDataSource(temporary.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.getTrackFormat(track).getLong(android.media.MediaFormat.KEY_DURATION)
        } finally { extractor.release(); temporary.delete() }
        require(duration > 1)
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:C="http://ns.google.com/photos/1.0/camera/" xmlns:G="http://ns.google.com/photos/1.0/container/" xmlns:I="http://ns.google.com/photos/1.0/container/item/"><rdf:RDF><rdf:Description C:MotionPhoto="1" C:MotionPhotoVersion="1"><G:Directory><rdf:Seq><rdf:li><G:Item I:Semantic="Primary" I:Mime="image/jpeg" I:Length="0" I:Padding="7"/></rdf:li><rdf:li><G:Item I:Semantic="MotionPhoto" I:Mime="video/mp4" I:Length="${clip.size}"/></rdf:li></rdf:Seq></G:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(Color.MAGENTA)
            java.io.ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
        val header = byteArrayOf(-1, -31) + java.nio.ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() + payload
        val original = jpeg.copyOfRange(0, 2) + header + jpeg.copyOfRange(2, jpeg.size) + ByteArray(7)
        return MotionFixture(original + clip, original.size, clip.size, duration)
    }

    /** Run after normal-process UI verification, BEFORE deleting the source (the cover FK cascades). */
    @Test fun verifyOwnedMotionHasNoSavedKeyFrame(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == Package)
        val requested = InstrumentationRegistry.getArguments().getString("fixtureUuid")
        org.junit.Assume.assumeTrue("Requires the exact completed Motion host fixture", requested != null)
        val uuid = requireNotNull(requested)
        require(UUID.fromString(uuid).toString() == uuid)
        val directory = File(context.filesDir, "creation-process-$uuid")
        val record = JSONObject(AtomicFile(File(directory, "fixture.json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        check(record.getString("package") == Package && record.getString("fixtureUuid") == uuid && record.getString("state") == "SEEDED")
        check(record.getBoolean("baselineKeyFrameAbsent"))
        val rows = record.getJSONArray("rows");check(rows.length() == 1)
        val row = rows.getJSONObject(0);val id = row.getLong("_id")
        check(row.getString("uri") == "content://media/external/images/media/$id")
        check(row.getString("owner_package_name") == Package && row.getString("relative_path") == "Pictures/creation-process-$uuid/")
        check(row.getString("_display_name") == "creation-process-$uuid-0.jpg")
        val uri = Uri.parse(row.getString("uri"))
        fun verifyCurrentSource() {
            val numeric = setOf("_id", "generation_added", "generation_modified", "is_pending")
            val columns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name", "generation_added", "generation_modified", "is_pending")
            context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
                check(cursor.moveToFirst()) { "Source must still exist before the keyframe verification" }
                for (column in columns) {
                    val index = cursor.getColumnIndexOrThrow(column)
                    if (column in numeric) check(cursor.getLong(index) == row.getLong(column))
                    else check(cursor.getString(index) == row.getString(column))
                }
                check(!cursor.moveToNext())
            }
            check(row.getLong("is_pending") == 0L)
            val hash = context.contentResolver.openInputStream(uri)!!.use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
            }
            check(hash == row.getString("sha256"))
        }
        verifyCurrentSource()
        val db = com.ugallery.core.database.GalleryDatabaseFactory.open(context)
        try {
            db.withTransaction {
                val media = checkNotNull(db.libraryDao().media("external_primary", id))
                check(media.isAccessible && !media.isTrashed && media.mediaType == 1 &&
                    media.generationModified == row.getLong("generation_modified") && media.generationAdded == row.getLong("generation_added"))
                check(db.motionKeyFrameDao().get("external_primary", id) == null) { "A draft unexpectedly persisted a cover" }
            }
        } finally { db.close() }
        verifyCurrentSource()
        val result = JSONObject().put("fixtureUuid", uuid).put("mediaId", id).put("baselineKeyFrameAbsent", true)
            .put("keyFrameRowsVerified", true).put("sourceCurrent", true).put("status", "PASS").toString()
        File(directory, "keyframe-verification.json").outputStream().use { it.write(result.toByteArray());it.fd.sync() }
        check(File(directory, "keyframe-verification.json").readText() == result)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$result\n") })
    }

    /** Title or either exact source identifies an attributable manual result, including renamed duplicates. */
    private fun manualFixtureMomentIds(
        db: com.ugallery.core.database.GalleryDatabase,
        uuid: String,
        rows: JSONArray,
    ): List<String> {
        check(rows.length() == 2)
        return db.openHelper.readableDatabase.query(
            "SELECT momentId FROM moments WHERE title=? OR (origin='MANUAL' AND momentId IN " +
                "(SELECT momentId FROM moment_members WHERE volumeName='external_primary' AND mediaStoreId IN (?,?))) ORDER BY momentId",
            arrayOf<Any>("Manual process $uuid", rows.getJSONObject(0).getLong("_id"), rows.getJSONObject(1).getLong("_id")),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    private fun manualFixtureCommitArm(uuid: String, rows: JSONArray): JSONObject = JSONObject()
        .put("fixtureUuid", uuid).put("title", "Manual process $uuid").put("phase", "ARMED").put("includeSpecialMedia", true)
        .put("sourceIds", JSONArray((0..1).map { rows.getJSONObject(it).getLong("_id") }))
        .put("generationAdded", JSONArray((0..1).map { rows.getJSONObject(it).getLong("generation_added") }))
        .put("generationModified", JSONArray((0..1).map { rows.getJSONObject(it).getLong("generation_modified") }))
        .put("orderedMediaIds", JSONArray((1 downTo 0).map { rows.getJSONObject(it).getLong("_id") }))

    private fun manualFixtureFingerprint(rows: JSONArray, title: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            fun text(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size); output.write(encoded)
            }
            fun key(row: JSONObject) { text("external_primary"); output.writeLong(row.getLong("_id")) }
            text("manual-v1"); output.writeInt(2)
            for (index in 0..1) {
                val row = rows.getJSONObject(index)
                key(row); output.writeLong(row.getLong("generation_added")); output.writeLong(row.getLong("generation_modified"))
            }
            output.writeInt(2)
            for (index in 1 downTo 0) key(rows.getJSONObject(index))
            text(title); output.writeBoolean(true)
        }
        return "manual-v1:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Verify the committed result while both sources exist, then remove only that exact verified memory. */
    @Test fun verifyOwnedManualMemoryAfterProcessRestoration(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == Package)
        val requested = InstrumentationRegistry.getArguments().getString("fixtureUuid")
        org.junit.Assume.assumeTrue("Requires the exact completed manual memory host fixture", requested != null)
        val uuid = requireNotNull(requested)
        require(UUID.fromString(uuid).toString() == uuid)
        val directory = File(context.filesDir, "creation-process-$uuid")
        val record = JSONObject(AtomicFile(File(directory, "fixture.json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        check(record.getString("package") == Package && record.getString("fixtureUuid") == uuid && record.getString("state") == "SEEDED")
        check(record.get("baselineManualMemoryAbsent") == true)
        val baseline = record.getJSONObject("manualMemoryBaseline")
        val title = "Manual process $uuid"
        check(baseline.getString("title") == title && baseline.getInt("sourceCount") == 2 && baseline.getJSONArray("attributableMomentIds").length() == 0)
        val rows = record.getJSONArray("rows"); check(rows.length() == 2)
        val ids = (0..1).map { index ->
            val row = rows.getJSONObject(index)
            check(row.getInt("ordinal") == index)
            val id = row.getLong("_id"); check(id >= 0)
            check(row.getString("uri") == "content://media/external/images/media/$id")
            check(row.getString("owner_package_name") == Package && row.getString("relative_path") == "Pictures/creation-process-$uuid/")
            check(row.getString("_display_name") == "creation-process-$uuid-$index.png")
            check(row.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            check(row.getLong("generation_added") >= 0 && row.getLong("generation_modified") >= 0 && row.getLong("is_pending") == 0L)
            id
        }
        check(ids.distinct().size == 2)
        val fingerprint = manualFixtureFingerprint(rows, title)
        val armed = record.opt("armManualCommitGap") == true
        val pendingStore = ManualMomentPendingCreateStore(File(context.noBackupFilesDir.canonicalFile, "manual-memory-pending"))
        val gateFiles = mutableMapOf<File, ByteArray>()
        fun readGateFile(name: String): JSONObject {
            val file = File(directory, name)
            check(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile && file.length() in 1L..8192L)
            val bytes = AtomicFile(file).openRead().use { it.readBytes() }
            gateFiles[file] = bytes
            return JSONObject(bytes.toString(Charsets.UTF_8))
        }
        val arm = if (armed) readGateFile("manual-commit-arm.json").also {
            check(it.toString() == manualFixtureCommitArm(uuid, rows).toString())
        } else null
        fun cleanupExactGateFiles() {
            if (!armed) return
            check(pendingStore.read() == null) { "Pending UI acknowledgement is not complete" }
            check(!File(directory, "manual-commit-outcome.json").exists() && !File(directory, "manual-commit-release.json").exists()) {
                "Gate was cancelled, timed out or released; preserve evidence"
            }
            for ((file, bytes) in gateFiles) check(file.readBytes().contentEquals(bytes))
            for ((file, _) in gateFiles) check(file.delete() && !file.exists())
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        fun verifyCurrentSources() {
            val numeric = setOf("_id", "generation_added", "generation_modified", "is_pending")
            val columns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name", "generation_added", "generation_modified", "is_pending", "_data")
            for (index in 0..1) {
                val row = rows.getJSONObject(index)
                val uri = Uri.parse(row.getString("uri"))
                fun verifySnapshot() = context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
                    check(cursor.moveToFirst()) { "Manual source must exist before memory verification/cleanup" }
                    for (column in columns) {
                        val position = cursor.getColumnIndexOrThrow(column)
                        if (column in numeric) check(cursor.getLong(position) == row.getLong(column))
                        else check(cursor.getString(position) == row.getString(column))
                    }
                    check(!cursor.moveToNext())
                }
                verifySnapshot()
                val hash = context.contentResolver.openInputStream(uri)!!.use {
                    MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
                }
                check(hash == row.getString("sha256")) { "Manual source bytes changed" }
                verifySnapshot()
            }
        }
        fun persistEvidence(filename: String, value: JSONObject) {
            val file = AtomicFile(File(directory, filename))
            check(!file.baseFile.exists()) { "Preserve prior manual verification evidence" }
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
            check(file.openRead().use { it.readBytes() }.contentEquals(bytes))
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        verifyCurrentSources()
        val expectation = InstrumentationRegistry.getArguments().getString("manualMemoryExpected")
        require(expectation == null || expectation == "absent")
        val db = com.ugallery.core.database.GalleryDatabaseFactory.open(context)
        if (expectation == "absent") {
            try {
                db.withTransaction { check(manualFixtureMomentIds(db, uuid, rows).isEmpty()) }
                verifyCurrentSources()
            } finally { db.close() }
            if (armed) {
                check(!File(directory, "manual-commit-gate.json").exists())
                cleanupExactGateFiles()
            }
            val result = JSONObject().put("fixtureUuid", uuid).put("sourcesCurrent", true)
                .put("manualMemoryAbsent", true).put("status", "PASS")
            persistEvidence("manual-memory-absence.json", result)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$result\n") })
            return@runBlocking
        }
        val momentId: String
        try {
            momentId = db.withTransaction {
                val dao = db.momentDao()
                val attributable = manualFixtureMomentIds(db, uuid, rows)
                check(attributable.size == 1) { "Expected exactly one attributable manual memory" }
                val id = attributable.single()
                check(UUID.fromString(id).toString() == id)
                val moment = checkNotNull(dao.moment(id))
                val commitGate = if (armed) readGateFile("manual-commit-gate.json").also { gate ->
                    check(arm != null && gate.length() == 8 && gate.getString("fixtureUuid") == uuid && gate.getString("draftId") == id &&
                        gate.getString("requestFingerprint") == fingerprint && gate.getString("phase") == "AFTER_ROOM_COMMIT")
                    check(UUID.fromString(gate.getString("token")).toString() == gate.getString("token"))
                    check(gate.getInt("pid") > 1 && gate.getInt("uid") == record.getInt("uid") && gate.getLong("deadlineElapsedRealtimeMillis") > 0)
                    check(pendingStore.read() == null) { "Memory was not acknowledged by the visible restored UI" }
                    check(!File(directory, "manual-commit-outcome.json").exists() && !File(directory, "manual-commit-release.json").exists())
                } else null
                check(moment.origin == "MANUAL" && moment.state == "SAVED" && moment.title == title &&
                    moment.titleMode == "USER" && moment.isUserEdited && moment.includeSpecialMedia && moment.algorithmVersion == fingerprint)
                val current = ids.mapIndexed { index, sourceId ->
                    val row = rows.getJSONObject(index)
                    checkNotNull(db.libraryDao().media("external_primary", sourceId)).also { media ->
                        check(media.isAccessible && !media.isTrashed && media.mediaType == 1 &&
                            media.generationModified == row.getLong("generation_modified") && media.generationAdded == row.getLong("generation_added"))
                    }
                }
                check(moment.startMillis == current.minOf { it.timelineSortMillis } && moment.endMillis == current.maxOf { it.timelineSortMillis })
                val members = dao.allMembers(id)
                check(members.size == 2)
                members.forEachIndexed { ordinal, member ->
                    val expected = rows.getJSONObject(1 - ordinal)
                    check(member.momentId == id && member.ordinal == ordinal && member.volumeName == "external_primary" &&
                        member.mediaStoreId == expected.getLong("_id") && member.generationModifiedAtSelection == expected.getLong("generation_modified") &&
                        member.origin == "MANUAL" && member.score == 1f)
                }
                val cover = db.openHelper.readableDatabase.query(
                    "SELECT volumeName,mediaStoreId,isUserSelected FROM moment_covers WHERE momentId=?", arrayOf(id),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    check(cursor.getString(0) == "external_primary" && cursor.getLong(1) == ids[1] && cursor.getLong(2) == 1L)
                    val value = JSONObject().put("volumeName", cursor.getString(0)).put("mediaStoreId", cursor.getLong(1)).put("isUserSelected", true)
                    check(!cursor.moveToNext()); value
                }
                // Additional annotations would be unreviewed data; do not remove them through cascading cleanup.
                for (table in listOf("moment_participant_state", "moment_participants", "moment_discovery_seen")) {
                    db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE momentId=?", arrayOf(id)).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Manual fixture acquired additional annotations" }
                    }
                }
                verifyCurrentSources()
                val evidence = JSONObject().put("fixtureUuid", uuid).put("momentId", id).put("requestFingerprint", fingerprint)
                    .put("title", moment.title).put("origin", moment.origin).put("state", moment.state).put("titleMode", moment.titleMode)
                    .put("isUserEdited", moment.isUserEdited).put("includeSpecialMedia", moment.includeSpecialMedia)
                    .put("startMillis", moment.startMillis).put("endMillis", moment.endMillis)
                    .put("createdAtMillis", moment.createdAtMillis).put("updatedAtMillis", moment.updatedAtMillis)
                    .put("members", JSONArray().apply { members.forEach { member -> put(JSONObject().put("momentId", member.momentId)
                        .put("ordinal", member.ordinal).put("volumeName", member.volumeName).put("mediaStoreId", member.mediaStoreId)
                        .put("generationModifiedAtSelection", member.generationModifiedAtSelection).put("origin", member.origin).put("score", member.score)) } })
                    .put("cover", cover).put("sourceSnapshots", rows).put("sourcesCurrent", true).put("status", "VERIFIED_BEFORE_DELETE")
                if (commitGate != null) evidence.put("commitGate", commitGate).put("pendingJournalAbsent", true)
                persistEvidence("manual-memory-predelete.json", evidence)
                check(dao.delete(id) == 1) // Same transaction, only the UUID whose full contents were just verified.
                check(dao.moment(id) == null && dao.allMembers(id).isEmpty())
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM moment_covers WHERE momentId=?", arrayOf(id)).use {
                    check(it.moveToFirst() && it.getLong(0) == 0L)
                }
                check(manualFixtureMomentIds(db, uuid, rows).isEmpty())
                id
            }
            verifyCurrentSources()
            db.withTransaction { check(db.momentDao().moment(momentId) == null && manualFixtureMomentIds(db, uuid, rows).isEmpty()) }
        } finally { db.close() }
        cleanupExactGateFiles()
        verifyCurrentSources()
        val result = JSONObject().put("fixtureUuid", uuid).put("momentId", momentId).put("requestFingerprint", fingerprint)
            .put("orderedMediaIds", JSONArray(ids.reversed())).put("includeSpecialMedia", true).put("baselineManualMemoryAbsent", true)
            .put("manualMemoryRowsVerified", true).put("sourcesCurrent", true).put("manualMemoryCleaned", true).put("status", "PASS")
        persistEvidence("manual-memory-verification.json", result)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$result\n") })
    }

    companion object { const val Package = "com.ugallery.app.pdfacceptance" }
}
