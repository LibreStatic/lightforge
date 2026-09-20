package com.ugallery.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction
import com.ugallery.core.data.*
import com.ugallery.core.database.*
import com.ugallery.core.mediastore.MediaStoreReader
import com.ugallery.core.mediastore.MediaStoreRecord
import com.ugallery.core.model.*
import com.ugallery.core.preferences.GallerySettingsRepository
import com.ugallery.feature.settings.*
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** App-owned bridge: portable bytes never select a pre-existing destination MediaStore identity. */
class GalleryOrganizationBackupAdapter(
    private val context: Context,
    private val database: GalleryDatabase,
) : LocalBackupDurableOrganizationPort {
    private val app = context.applicationContext
    private val repository = PortableOrganizationRepository(database)
    private val reader = MediaStoreReader(app.contentResolver)

    override suspend fun export(sources: List<LocalBackupSourceRef>): LocalBackupSidecar =
        withContext(Dispatchers.IO) {
            require(sources.size in 1..BackupManifest.MAX_ENTRIES)
            require(sources.map { it.entry.sourceId }.distinct().size == sources.size)
            val bindings = mutableListOf<PortableSourceBinding>()
            sources.forEach { source ->
                val original = source.sourceUri?.let(Uri::parse) ?: return@forEach
                val key = resolveMediaKey(original) ?: return@forEach
                val before = reader.readOne(key) ?: return@forEach
                val indexed =
                    database.libraryDao().media(key.volumeName, key.mediaStoreId) ?: return@forEach
                require(
                    before.generationModified == indexed.generationModified &&
                        before.generationAdded == indexed.generationAdded &&
                        before.sizeBytes == source.entry.bytes
                )
                // Archive copying happened earlier. Revalidate its content identity before
                // attaching current organization.
                verifyCurrentBytes(original, source.entry)
                val after =
                    reader.readOne(key) ?: error("Source disappeared during organization export")
                require(
                    after.generationModified == before.generationModified &&
                        after.generationAdded == before.generationAdded &&
                        after.sizeBytes == before.sizeBytes
                )
                bindings +=
                    PortableSourceBinding(
                        source.entry.sourceId,
                        key,
                        after.generationModified,
                        source.entry.sha256,
                        source.entry.bytes,
                    )
            }
            val preferences = GallerySettingsRepository(app).exportJson().toString()
            val snapshot =
                database.withTransaction {
                    val base = repository.export(bindings, preferences)
                    // Review explicitly acknowledges persistent state outside this versioned
                    // organization scope.
                    val unsupported =
                        database.openHelper.readableDatabase
                            .query(
                                """SELECT
                (SELECT COUNT(*) FROM moment_participant_state WHERE mode='MANUAL')+
                (SELECT COUNT(*) FROM motion_key_frames)+
                (SELECT COUNT(*) FROM video_edit_recipes)+(SELECT COUNT(*) FROM custom_luts)+
                (SELECT COUNT(*) FROM person_face_overrides)+(SELECT COUNT(*) FROM person_constraints)+
                (SELECT COUNT(*) FROM label_suppressions)+(SELECT COUNT(*) FROM me_profiles)+(SELECT COUNT(*) FROM me_references)+
                (SELECT COUNT(*) FROM pet_identities)+
                (SELECT COUNT(*) FROM pet_observations WHERE identityId IS NOT NULL OR excluded=1)"""
                            )
                            .use { c ->
                                check(c.moveToFirst())
                                c.getLong(0)
                            }
                    base
                        .copy(
                            scope =
                                base.scope.copy(
                                    omittedReferences =
                                        Math.addExact(base.scope.omittedReferences, unsupported)
                                )
                        )
                        .validate()
                }
            LocalBackupSidecar(PortableOrganizationCodec.encode(snapshot), snapshot.schemaVersion)
        }

    override suspend fun review(
        sidecar: ByteArray,
        manifest: BackupManifest,
    ): LocalBackupOrganizationReview =
        withContext(Dispatchers.IO) {
            val snapshot = validated(sidecar, manifest)
            val total = snapshot.totals()
            val knownSources = snapshot.sources.map { it.sourceId }.toSet()
            val missingFiles = manifest.entries.count { entry -> entry.sourceId !in knownSources }
            fun count(kind: LocalBackupOrganizationKind, value: Int) =
                LocalBackupOrganizationCount(kind, value)
            LocalBackupOrganizationReview(
                counts =
                    listOf(
                        count(LocalBackupOrganizationKind.PhotoEdits, snapshot.photoRecipes.size),
                        count(LocalBackupOrganizationKind.Albums, snapshot.albums.size),
                        count(LocalBackupOrganizationKind.Memories, snapshot.memories.size),
                        count(LocalBackupOrganizationKind.Stacks, snapshot.stacks.size),
                        count(LocalBackupOrganizationKind.SmartAlbums, snapshot.smartAlbums.size),
                        count(
                            LocalBackupOrganizationKind.Documents,
                            snapshot.decisions.count { it.documentCategory != null },
                        ),
                        count(
                            LocalBackupOrganizationKind.Archived,
                            snapshot.decisions.count { it.archivedAtMillis != null },
                        ),
                        count(LocalBackupOrganizationKind.DateRules, snapshot.memoryDates.size),
                        count(LocalBackupOrganizationKind.PersonRules, snapshot.memoryPeople.size),
                        count(
                            LocalBackupOrganizationKind.Favorites,
                            snapshot.sources.count { it.isFavorite },
                        ),
                        count(
                            LocalBackupOrganizationKind.Preferences,
                            if (snapshot.preferencesJsonForReview == null) 0 else 1,
                        ),
                        count(
                            LocalBackupOrganizationKind.AutomaticRules,
                            if (snapshot.autoArchiveReview == null) 0 else 1,
                        ),
                    ),
                sourceCount = snapshot.sources.size,
                omitted = Math.toIntExact(snapshot.scope.omittedReferences + missingFiles),
                unresolved = total.unresolvedPeople,
                globalRuleCount = snapshot.memoryDates.size + snapshot.memoryPeople.size,
                canRestore = true,
                totalSources =
                    Math.toIntExact(
                        maxOf(snapshot.scope.librarySourceCount, manifest.entries.size.toLong())
                    ),
                hasPreferences = snapshot.preferencesJsonForReview != null,
                hasAutomaticRules = snapshot.autoArchiveReview != null,
            )
        }

    override suspend fun preferencesForReview(
        sidecar: ByteArray,
        manifest: BackupManifest,
    ): ByteArray? = withContext(Dispatchers.IO) {
        validated(sidecar, manifest).preferencesJsonForReview?.toByteArray(Charsets.UTF_8)
    }

    override suspend fun committedResult(operationId: String): LocalRestoreGalleryResult? =
        withContext(Dispatchers.IO) {
            require(UUID.fromString(operationId).toString() == operationId)
            database.galleryRestoreReceiptDao().get(operationId)?.let {
                LocalRestoreGalleryResult(it.files, it.importedObjects, it.skippedObjects)
            }
        }

    override suspend fun beginRestore(
        sidecar: ByteArray,
        manifest: BackupManifest,
        options: LocalRestoreOrganizationOptions,
    ): LocalRestoreGallerySession = createRestore(UUID.randomUUID().toString(), sidecar, manifest, options, false)

    override suspend fun resumeRestore(
        operationId: String,
        sidecar: ByteArray,
        manifest: BackupManifest,
        options: LocalRestoreOrganizationOptions,
    ): LocalRestoreGallerySession = createRestore(operationId, sidecar, manifest, options, true)

    private suspend fun createRestore(
        operationId: String,
        sidecar: ByteArray,
        manifest: BackupManifest,
        options: LocalRestoreOrganizationOptions,
        resuming: Boolean,
    ): LocalRestoreGallerySession = withContext(Dispatchers.IO) {
            require(UUID.fromString(operationId).toString() == operationId)
            val snapshot = validated(sidecar, manifest)
            val sourceIds = snapshot.sources.map { it.sourceId }.toSet()
            if (
                (snapshot.scope.partial || manifest.entries.any { it.sourceId !in sourceIds }) &&
                    !options.allowPartial
            )
                throw PortableOrganizationConflict("PartialSnapshotNeedsReview")
            val namespace = UUID.fromString(operationId)
            val facts = snapshot.sources.associateBy { it.sourceId }
            val names = restoredAlbumNames(snapshot, namespace)
            GalleryRestoreMediaSession(
                context = app,
                operationId = namespace.toString(),
                facts = facts,
                commitMetadata = { operationId, restored ->
                    database.withTransaction {
                        database.galleryRestoreReceiptDao().get(operationId)?.let { receipt ->
                            require(receipt.snapshotId == snapshot.snapshotId)
                            return@withTransaction LocalRestoreGalleryResult(
                                receipt.files,
                                receipt.importedObjects,
                                receipt.skippedObjects,
                            )
                        }
                        require(operationId == namespace.toString())
                        require(
                            restored.map { it.entry.sourceId }.toSet() ==
                                manifest.entries.map { it.sourceId }.toSet() &&
                                restored.size == manifest.entries.size
                        )
                        val mapped = mutableListOf<PortableRestoredSource>()
                        restored.forEach { item ->
                            val key = item.key ?: return@forEach
                            val record = reader.readOne(key) ?: error("Published copy is missing")
                            require(
                                record.generationModified == item.generationModified &&
                                    record.generationAdded == item.generationAdded &&
                                    record.sizeBytes == item.entry.bytes &&
                                    !record.isTrashed
                            )
                            val fact = facts[item.entry.sourceId]
                            if (fact != null)
                                require(record.isFavorite == fact.isFavorite) {
                                    "Favorite publication was not preserved"
                                }
                            val entity = record.portableEntity()
                            database.libraryDao().upsertMedia(listOf(entity))
                            if (fact != null) {
                                database
                                    .portableTimelineOverrideDao()
                                    .put(
                                        PortableTimelineOverrideEntity(
                                            key.volumeName,
                                            key.mediaStoreId,
                                            record.generationAdded,
                                            fact.timelineSortMillis,
                                            fact.dateTakenMillis,
                                        )
                                    )
                                database.libraryDao().upsertMedia(listOf(entity))
                                mapped +=
                                    PortableRestoredSource(
                                        fact.sourceId,
                                        key,
                                        record.generationModified,
                                        fact.sha256,
                                        fact.sizeBytes,
                                        operationId,
                                    )
                            }
                        }
                        val result =
                            repository.importSnapshot(
                                snapshot,
                                mapped,
                                PortableImportOptions(
                                    options.allowPartial,
                                    options.importGlobalRules,
                                    names,
                                ),
                                namespace,
                            )
                        val imported =
                            result.createdIds.size +
                                result.createdMemoryDateIds.size +
                                result.createdMemoryPersonIds.size +
                                snapshot.decisions.size
                        val skipped =
                            result.skippedGlobalRules +
                                result.reviewOnlyItems +
                                result.omittedEmptyStackCount
                        database
                            .galleryRestoreReceiptDao()
                            .insert(
                                GalleryRestoreReceiptEntity(
                                    operationId,
                                    snapshot.snapshotId,
                                    restored.size,
                                    imported,
                                    skipped,
                                    System.currentTimeMillis(),
                                )
                            )
                        LocalRestoreGalleryResult(restored.size, imported, skipped)
                    }
                },
                isCommitted = { id -> database.galleryRestoreReceiptDao().get(id) != null },
                resume = resuming,
            )
        }

    private fun validated(
        bytes: ByteArray,
        manifest: BackupManifest,
    ): PortableOrganizationSnapshot {
        val checked = BackupManifest.decode(manifest.encode())
        require(checked.version == 2)
        val descriptor = requireNotNull(checked.organization)
        require(
            descriptor.schemaVersion in 1..3 &&
                descriptor.bytes == bytes.size.toLong() &&
                descriptor.sha256 == digest(bytes)
        )
        val snapshot = PortableOrganizationCodec.decode(bytes)
        require(snapshot.schemaVersion == descriptor.schemaVersion) { "Organization schema descriptor mismatch" }
        val entries = checked.entries.associateBy { it.sourceId }
        snapshot.sources.forEach { source ->
            val entry =
                entries[source.sourceId]
                    ?: error("Organization references a file outside this backup")
            require(entry.sha256 == source.sha256 && entry.bytes == source.sizeBytes)
            require(
                when (source.kind) {
                    PortableMediaKind.Image -> entry.mime.startsWith("image/")
                    PortableMediaKind.Video -> entry.mime.startsWith("video/")
                    PortableMediaKind.Document -> true
                }
            )
        }
        return snapshot
    }

    private suspend fun restoredAlbumNames(
        snapshot: PortableOrganizationSnapshot,
        namespace: UUID,
    ): Map<String, String> {
        val used = mutableSetOf<String>()
        fun reserve(name: String): Boolean {
            val normalized = name.lowercase(Locale.ROOT)
            if (normalized in used) return false
            val exists =
                database.openHelper.readableDatabase
                    .query(
                        "SELECT albumId FROM virtual_albums WHERE normalizedName=? LIMIT 1",
                        arrayOf(normalized),
                    )
                    .use { it.moveToFirst() }
            return !exists && used.add(normalized)
        }
        return snapshot.albums
            .mapIndexed { index, album ->
                var name = album.name
                if (!reserve(name)) {
                    val suffix = "${namespace.toString().take(8)}-${index+1}"
                    name =
                        app.getString(
                                com.ugallery.feature.settings.R.string
                                    .local_backup_restored_album_name,
                                album.name.take(40),
                                suffix,
                            )
                            .take(80)
                    if (!reserve(name)) throw PortableOrganizationConflict("VirtualAlbumNameExists")
                }
                album.entityId to name
            }
            .toMap()
    }

    private fun resolveMediaKey(original: Uri): MediaKey? {
        if (original.scheme != "content") return null
        val media =
            if (original.authority == MediaStore.AUTHORITY) original
            else runCatching { MediaStore.getMediaUri(app, original) }.getOrNull() ?: return null
        if (media.authority != MediaStore.AUTHORITY) return null
        return app.contentResolver
            .query(
                media,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME),
                null,
                null,
                null,
            )
            ?.use { c -> if (!c.moveToFirst()) null else MediaKey(c.getString(1), c.getLong(0)) }
    }

    private suspend fun verifyCurrentBytes(uri: Uri, entry: BackupManifest.Entry) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        app.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                require(n.toLong() <= entry.bytes - size) { "Source grew during export" }
                digest.update(buffer, 0, n)
                size += n
            }
        }
        require(size == entry.bytes && hex(digest.digest()) == entry.sha256) {
            "Source content changed before organization snapshot"
        }
    }

    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun MediaStoreRecord.portableEntity(): MediaItemEntity =
    MediaItemEntity(
        key.volumeName,
        key.mediaStoreId,
        if (kind == MediaKind.Image) 1 else 3,
        mimeType,
        displayName,
        sizeBytes,
        width,
        height,
        durationMillis,
        orientationDegrees,
        dateTakenMillis,
        dateAddedSeconds,
        dateModifiedSeconds,
        dateTakenMillis ?: dateAddedSeconds * 1000,
        generationAdded,
        generationModified,
        bucketId,
        bucketDisplayName,
        relativePath,
        isFavorite,
        isTrashed,
        true,
        0,
        dateExpiresSeconds,
    )
