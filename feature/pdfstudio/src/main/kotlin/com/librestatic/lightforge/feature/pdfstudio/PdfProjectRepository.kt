package com.librestatic.lightforge.feature.pdfstudio

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.*

class PdfProjectRepository(
    private val context: Context,
    private val engine: PdfEngine = IsolatedPdfEngine(context),
    private val sourceChunk: suspend (Long) -> Unit = {},
    freeBytes: (File) -> Long = { it.usableSpace },
) {
    private val storage = PdfStorageBudget(freeBytes)
    private val database = PdfProjectDatabase.get(context)
    private val dao = database.projects()
    private val root = File(context.filesDir, "pdf-studio").apply { mkdirs() }
    private val assets = File(root, "assets").apply { mkdirs() }
    private val lock = PdfStorageLock.mutex
    val projects = dao.observe()

    fun file(hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(assets, hash)
    }

    suspend fun load(id: String): PdfProject? =
        withContext(Dispatchers.IO) { dao.get(id)?.let { PdfCodec.decode(it.manifest) } }

    suspend fun loadEditor(id: String): Pair<PdfProject, PdfEditorSession>? =
        withContext(Dispatchers.IO) {
            dao.get(id)?.let { row ->
                val p = PdfCodec.decode(row.manifest)
                p to PdfEditorSessionCodec.decode(row.editor, p)
            }
        }

    suspend fun save(p: PdfProject, editor: PdfEditorSession? = null) =
        withContext(Dispatchers.IO) { lock.withLock { saveUnlocked(p, editor) } }

    private suspend fun saveUnlocked(
        p: PdfProject,
        editor: PdfEditorSession? = null,
        requestId: String? = null,
    ) {
        p.validate()
        val previous = dao.get(p.id)
        val filtered = p.copy(assets = p.assets.filter { it.hash in p.usedAssets() })
        val unchanged =
            previous?.let { PdfCodec.decode(it.manifest) == filtered.copy(updated = it.updated) } ==
                true
        // Persisting picker ownership/editor selection must not change a failed import's project.
        val value =
            filtered.copy(
                updated = if (unchanged) previous!!.updated else System.currentTimeMillis()
            )
        val session = editor?.normalized(value)
        require(
            (value.usedAssets() + (session?.usedAssets() ?: emptySet())).all { file(it).isFile }
        )
        val encoded = session?.let(PdfEditorSessionCodec::encode) ?: previous?.editor.orEmpty()
        val summary = summarize(value)
        database.withTransaction {
            dao.put(
                PdfProjectRow(
                    value.id,
                    value.name,
                    value.updated,
                    PdfCodec.encode(value),
                    encoded,
                    summary.pageCount,
                    summary.sourceBytes,
                    summary.coverPageId,
                )
            )
            requestId?.let { database.imports().put(PdfImportReceipt(it, value.id)) }
        }
    }

    private data class ProjectSummary(
        val pageCount: Int,
        val sourceBytes: Long,
        val coverPageId: String?,
    )

    /** Page count, on-disk asset bytes and the cover page id, for the library list card. */
    /** Hash -> on-disk byte size. A hash is a content address (sha256 of the asset file), so its
     * size never changes once written; caching it means an autosave (which calls [summarize] on
     * every save, not only when assets change) never re-stats every asset file it already knows
     * about. Entries for assets [delete] has removed are simply never looked up again. */
    private val assetSizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun assetSize(hash: String): Long =
        assetSizeCache.getOrPut(hash) { runCatching { file(hash).length() }.getOrDefault(0L) }

    private fun summarize(p: PdfProject): ProjectSummary =
        ProjectSummary(
            pageCount = p.pages.size,
            sourceBytes = p.usedAssets().sumOf(::assetSize),
            coverPageId = p.pages.firstOrNull()?.id,
        )

    /**
     * Backfills [PdfProjectRow.pageCount]/[sourceBytes]/[coverPageId] for rows saved before the
     * v9 migration (pageCount = 0 marks them pending). Safe to call repeatedly and off the main
     * thread; the library screen triggers it once per load.
     *
     * A real [save] always computes a fresh, correct summary from whatever is currently open, so
     * it must never be clobbered by this lazy pass computed from a manifest read earlier and
     * possibly now stale (e.g. the project was opened and edited while backfill was still
     * running). [PdfProjectDao.updateSummaryIfPending]'s `WHERE pageCount = 0` guard makes that
     * race harmless without needing the global storage lock: a project's real pageCount is never
     * 0 (every project has at least one page), so the conditional update simply misses rows a
     * concurrent save has already summarized correctly, leaving them alone.
     */
    suspend fun backfillSummaries() =
        withContext(Dispatchers.IO) {
            dao.withoutSummary()
                .filter { it.manifest.isNotEmpty() }
                .forEach { row ->
                    val project = runCatching { PdfCodec.decode(row.manifest) }.getOrNull() ?: return@forEach
                    if (project.pages.isEmpty()) return@forEach
                    val summary = summarize(project)
                    dao.updateSummaryIfPending(row.id, summary.pageCount, summary.sourceBytes, summary.coverPageId)
                }
        }

    suspend fun delete(id: String, protected: Set<String> = emptySet()) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                dao.delete(id)
                val used =
                    dao.all()
                        .flatMap { row ->
                            val p = PdfCodec.decode(row.manifest)
                            val session =
                                runCatching { PdfEditorSessionCodec.decode(row.editor, p) }
                                    .getOrElse {
                                        return@withLock
                                    }
                            p.usedAssets() + session.usedAssets()
                        }
                        .toSet() + protected
                val jobSources =
                    PdfProjectDatabase.get(context)
                        .exports()
                        .all()
                        .filter { it.keepsSources }
                        .flatMap { PdfCodec.decode(it.manifest).usedAssets() }
                        .toSet()
                assets
                    .listFiles()
                    ?.filter { it.name !in used && it.name !in jobSources }
                    ?.forEach { it.delete() }
            }
        }

    suspend fun import(
        p: PdfProject,
        uris: List<Uri>,
        pageIndex: Int,
        progress: (Int, Int) -> Unit,
    ): PdfProject =
        withContext(Dispatchers.IO) {
            lock.withLock { importUnlocked(p, uris, pageIndex, progress) }
        }

    internal suspend fun importPicked(
        request: PdfImportRequest,
        progress: (Int, Int) -> Unit,
    ): PdfProject =
        withContext(Dispatchers.IO) {
            lock.withLock {
                database.imports().get(request.id)?.let { receipt ->
                    return@withLock load(receipt.projectId)
                        ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
                }
                require(request.uris.isNotEmpty())
                if (request.portable)
                    return@withLock importPortableUnlocked(
                        Uri.parse(request.uris.single()),
                        request.id,
                    )
                val (project, session) =
                    loadEditor(requireNotNull(request.projectId))
                        ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
                val page = project.pages.indexOfFirst { it.id == request.pageId }
                if (page < 0) throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
                importUnlocked(
                    project,
                    request.uris.map(Uri::parse),
                    page,
                    progress,
                    request.id,
                    session.copy(undo = (session.undo + project).takeLast(40), redo = emptyList()),
                )
            }
        }

    internal suspend fun importGallery(
        row: PdfGalleryDelivery,
        progress: (Int, Int) -> Unit,
    ): PdfProject =
        withContext(Dispatchers.IO) {
            lock.withLock {
                database.imports().get(row.id)?.let { receipt ->
                    return@withLock load(receipt.projectId)
                        ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
                }
                val targetProjectId = row.targetProjectId
                if (targetProjectId != null) {
                    val targetPageId =
                        row.targetPageId ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
                    val placement =
                        if (row.placementX != null && row.placementY != null)
                            row.placementX to row.placementY
                        else null
                    return@withLock importMediaIntoPage(
                        targetProjectId,
                        targetPageId,
                        row.sources().single(),
                        placement,
                        row.id,
                        progress,
                    )
                }
                importUnlocked(
                    PdfProject(name = row.name),
                    row.sources(),
                    0,
                    progress,
                    row.id,
                    galleryLayout = true,
                )
            }
        }

    /**
     * Media panel tap/drop (Phase F item 3): appends a single image durably into the CURRENT
     * project/page instead of creating a new project - same receipts/all-or-nothing/undo
     * machinery as every other import path, but deliberately NOT [importUnlocked]: that function
     * mutates a project snapshot taken once at entry, which is fine for a batch import into a
     * brand-new project (nothing else can be editing it), but wrong here, since the caller keeps
     * the editor unlocked during this call (only a background progress chip shows) so the user
     * can keep editing while a slow source copies. This method copies the source FIRST, then
     * re-reads the project/page fresh from disk right before appending and saving, so a
     * concurrent edit/autosave that happened during the copy is never clobbered - the append
     * always lands on top of whatever is truly latest. The whole call stays serialized against
     * every other save/import through [lock] (the caller already holds it), so no interleaved
     * write can corrupt the project file itself either way.
     */
    private suspend fun importMediaIntoPage(
        targetProjectId: String,
        targetPageId: String,
        uri: Uri,
        placement: Pair<Double, Double>?,
        requestId: String,
        progress: (Int, Int) -> Unit,
    ): PdfProject {
        // Fail fast, before touching the source at all, if the target is already full: avoids an
        // unnecessary copy and - the point of the review finding - avoids the limit surfacing as
        // a per-source read failure (PdfSourceFailure) instead of a clear, dedicated message.
        val start =
            loadEditor(targetProjectId) ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
        val startPage =
            start.first.pages.find { it.id == targetPageId }
                ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
        if (startPage.source != null) throw PdfOperationFailure(PdfFailure.UnsupportedFormat)
        if (!PdfMediaPlacement.hasRoomForOneMore(startPage.images.size + startPage.texts.size))
            throw PdfOperationFailure(PdfFailure.PageFull)

        cleanInterruptedImports()
        val staging = File(root, "import-${newId()}").apply { mkdirs() }
        var createdFile: File? = null
        var committed = false
        return try {
            val dest = File(staging, "source-0")
            var total = 0L
            context.contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source)
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 250L * 1024 * 1024)
                            throw PdfOperationFailure(PdfFailure.LimitExceeded)
                        storage.beforeWrite(dest, count.toLong())
                        out.write(buffer, 0, count)
                        sourceChunk(total)
                    }
                    out.fd.sync()
                }
            }
            val hash = sha256(dest)
            val header = ByteArray(PdfImageSignature.LENGTH)
            val read =
                dest.inputStream().use { input ->
                    var filled = 0
                    while (filled < header.size) {
                        val n = input.read(header, filled, header.size - filled)
                        if (n < 0) break
                        filled += n
                    }
                    filled
                }
            val signature = PdfImageSignature.of(header, read)
            // A dropped PDF document (Documents scope of the Media panel) has nowhere sensible to
            // land as an appended IMAGE on an existing page; the Media panel only offers images
            // for insertion by construction, but guard it here too rather than silently misusing
            // a whole PDF as image bytes.
            if (signature == PdfImageSignature.PDF) throw PdfOperationFailure(PdfFailure.UnsupportedFormat)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.path, o)
            val mime =
                PdfImageSignature.accept(header, read, o.outMimeType, o.outWidth, o.outHeight)
                    ?: throw PdfOperationFailure(PdfFailure.UnsupportedFormat)
            val orientation =
                runCatching {
                        androidx.exifinterface.media
                            .ExifInterface(dest)
                            .getAttributeInt(
                                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                1,
                            )
                    }
                    .getOrDefault(1)
                    .coerceIn(1, 8)
            val a =
                PdfAsset(
                    hash,
                    mime,
                    if (orientation >= 5) o.outHeight else o.outWidth,
                    if (orientation >= 5) o.outWidth else o.outHeight,
                    orientation,
                )
            val final = file(hash)
            if (!final.exists()) {
                check(dest.renameTo(final))
                createdFile = final
            } else dest.delete()
            progress(1, 1)

            // Re-fetch the FRESHEST project/session now, right before mutating - never build on
            // the possibly-stale snapshot read above; the copy above can take long enough for the
            // user to have edited (and autosaved) the very same project in the meantime.
            val (freshProject, freshSession) =
                loadEditor(targetProjectId) ?: throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
            val freshPageIndex = freshProject.pages.indexOfFirst { it.id == targetPageId }
            if (freshPageIndex < 0) throw PdfOperationFailure(PdfFailure.ImportTargetMissing)
            val freshPage = freshProject.pages[freshPageIndex]
            if (freshPage.source != null) throw PdfOperationFailure(PdfFailure.UnsupportedFormat)
            if (!PdfMediaPlacement.hasRoomForOneMore(freshPage.images.size + freshPage.texts.size))
                throw PdfOperationFailure(PdfFailure.PageFull)
            val (w, h) = PdfMediaPlacement.fitSize(freshPage.width, freshPage.margin, a.width, a.height)
            // [placement], when given, is the drop point's page-space CENTER (Media panel drag &
            // drop); convert to the top-left PdfImage stores.
            val (x, y) =
                placement?.let { (cx, cy) -> PdfMediaPlacement.centerToTopLeft(cx, cy, w, h) }
                    ?: PdfMediaPlacement.autoSlotTopLeft(freshPage.images.size, freshPage.margin)
            val image = PdfGeometry.constrain(PdfImage(asset = hash, x = x, y = y, width = w, height = h), freshPage)
            val updatedPage = freshPage.copy(images = freshPage.images + image)
            val updatedPages =
                freshProject.pages.toMutableList().also { it[freshPageIndex] = updatedPage }
            val updatedAssets =
                if (freshProject.assets.any { it.hash == hash }) freshProject.assets
                else freshProject.assets + a
            val next = freshProject.copy(pages = updatedPages, assets = updatedAssets).validate()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                saveUnlocked(
                    next,
                    freshSession.copy(
                        undo = (freshSession.undo + freshProject).takeLast(40),
                        redo = emptyList(),
                    ),
                    requestId,
                )
                committed = true
            }
            next
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PdfOperationFailure) {
            // A clear, dedicated failure (limit reached, target missing, unsupported format) -
            // never re-wrapped as a generic per-source read failure (the review's device-fail
            // finding: the per-page limit used to surface as "Source 1" instead of this).
            if (!committed) createdFile?.delete()
            throw e
        } catch (e: Exception) {
            if (!committed) createdFile?.delete()
            throw PdfSourceFailure(1, e)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun importUnlocked(
        p: PdfProject,
        uris: List<Uri>,
        pageIndex: Int,
        progress: (Int, Int) -> Unit,
        requestId: String? = null,
        editor: PdfEditorSession? = null,
        galleryLayout: Boolean = false,
        /** Explicit top-left placement in page millimeters (Media panel drag & drop, Phase F item
         * 3), used only for a single-image target import instead of the default incremental
         * offset. Ignored for gallery/PDF batch imports and constrained to the page afterward. */
        placement: Pair<Double, Double>? = null,
    ): PdfProject {
        require(uris.isNotEmpty() && uris.size <= 100)
        cleanInterruptedImports()
        val staging = File(root, "import-${newId()}").apply { mkdirs() }
        val created = mutableListOf<File>()
        var committed = false
        return try {
            var pages = p.pages.toMutableList()
            val known = p.assets.associateBy { it.hash }.toMutableMap()
            var total = 0L
            var imageTarget = pageIndex
            uris.forEachIndexed { n, uri ->
                currentCoroutineContext().ensureActive()
                try {
                    val dest = File(staging, "source-$n")
                    context.contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source)
                        FileOutputStream(dest).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 250L * 1024 * 1024)
                                    throw PdfOperationFailure(PdfFailure.LimitExceeded)
                                storage.beforeWrite(dest, count.toLong())
                                out.write(buffer, 0, count)
                                sourceChunk(total)
                            }
                            out.fd.sync()
                        }
                    }
                    val hash = sha256(dest)
                    val header = ByteArray(PdfImageSignature.LENGTH)
                    val read =
                        dest.inputStream().use { input ->
                            var filled = 0
                            while (filled < header.size) {
                                val n = input.read(header, filled, header.size - filled)
                                if (n < 0) break
                                filled += n
                            }
                            filled
                        }
                    val signature = PdfImageSignature.of(header, read)
                    if (signature == PdfImageSignature.PDF) {
                        val imported = engine.inspect(dest)
                        if (pages.size + imported.size > 100)
                            throw PdfOperationFailure(PdfFailure.LimitExceeded)
                        pages.addAll(imported.map { it.copy(source = hash) })
                        known[hash] = PdfAsset(hash, PdfImageSignature.PDF)
                    } else {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(dest.path, o)
                        val mime =
                            PdfImageSignature.accept(
                                header,
                                read,
                                o.outMimeType,
                                o.outWidth,
                                o.outHeight,
                            ) ?: throw PdfOperationFailure(PdfFailure.UnsupportedFormat)
                        val orientation =
                            runCatching {
                                    androidx.exifinterface.media
                                        .ExifInterface(dest)
                                        .getAttributeInt(
                                            androidx.exifinterface.media.ExifInterface
                                                .TAG_ORIENTATION,
                                            1,
                                        )
                                }
                                .getOrDefault(1)
                                .coerceIn(1, 8)
                        val a =
                            PdfAsset(
                                hash,
                                mime,
                                if (orientation >= 5) o.outHeight else o.outWidth,
                                if (orientation >= 5) o.outWidth else o.outHeight,
                                orientation,
                            )
                        known[hash] = a
                        val target =
                            if (
                                pages[imageTarget].source == null &&
                                    (!galleryLayout || pages[imageTarget].images.size < 24)
                            )
                                imageTarget
                            else {
                                require(pages.size < 100)
                                pages.add(PdfPage())
                                imageTarget = pages.lastIndex
                                imageTarget
                            }
                        val page = pages[target]
                        require(PdfMediaPlacement.hasRoomForOneMore(page.images.size + page.texts.size))
                        val (w, h) = PdfMediaPlacement.fitSize(page.width, page.margin, a.width, a.height)
                        // [placement], when given, is the drop point's page-space CENTER (Media
                        // panel drag & drop); convert to the top-left PdfImage stores.
                        val (x, y) =
                            placement?.let { (cx, cy) -> PdfMediaPlacement.centerToTopLeft(cx, cy, w, h) }
                                ?: PdfMediaPlacement.autoSlotTopLeft(page.images.size, page.margin)
                        val image =
                            PdfGeometry.constrain(
                                PdfImage(asset = hash, x = x, y = y, width = w, height = h),
                                page,
                            )
                        pages[target] = page.copy(images = page.images + image)
                    }
                    val final = file(hash)
                    if (!final.exists()) {
                        check(dest.renameTo(final))
                        created.add(final)
                    } else dest.delete()
                    progress(n + 1, uris.size)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw PdfSourceFailure(n + 1, e)
                }
            }
            // Phase G4: the gallery batch handoff still creates directly (no template picker in
            // its UX), but its default grid now comes from the single PdfTemplate source of truth
            // instead of its own literal 2/4.0 — PdfTemplate.Blank carries exactly those values.
            if (galleryLayout)
                pages =
                    pages
                        .map {
                            if (it.source == null) PdfGeometry.grid(it, PdfTemplate.Blank.columns, PdfTemplate.Blank.gap)
                            else it
                        }
                        .toMutableList()
            val next = p.copy(pages = pages, assets = known.values.toList()).validate()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                saveUnlocked(next, editor, requestId)
                committed = true
            }
            next
        } catch (e: Throwable) {
            if (!committed) created.forEach { it.delete() }
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun imageBitmap(file: File, rotation: Int, side: Int): android.graphics.Bitmap =
        PdfBitmapStore.get(context).load(file, rotation, side, immutable = true)

    suspend fun previewBitmap(page: PdfPage, side: Int): android.graphics.Bitmap? {
        val hash = page.source ?: return null
        return PdfDiskPreviewCache(context.cacheDir)
            .read(
                hash,
                page.sourcePage,
                render = { engine.preview(file(hash), page.sourcePage, it) },
                decode = { imageBitmap(it, page.rotation, side) },
            )
    }

    suspend fun prepareExport(p: PdfProject, compact: Boolean): File =
        withContext(Dispatchers.IO) {
            p.validate()
            val tmp = File(context.cacheDir, "pdf-export-${newId()}.pdf")
            try {
                val used = p.copy(assets = p.assets.filter { it.hash in p.usedAssets() })
                engine.export(used, used.assets.map { file(it.hash) }, tmp, compact)
                ensureActive()
                require(tmp.length() > 0)
                require(engine.inspect(tmp).size == p.pages.size)
                tmp
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }

    suspend fun publish(source: File, uri: Uri, deleteOnFailure: Boolean = true) =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output)
                    source.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                val actual =
                    context.contentResolver.openInputStream(uri).use {
                        requireNotNull(it)
                        hash(it)
                    }
                require(actual == sha256(source)) { "Export verification failed" }
            } catch (e: Throwable) {
                if (deleteOnFailure)
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                throw e
            }
        }

    suspend fun portable(p: PdfProject): File =
        withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, "pdf-project-${newId()}.ugpdfproject")
            writePortable(p, out)
            out
        }

    internal suspend fun writePortable(
        p: PdfProject,
        out: File,
        progress: suspend (Int, Int) -> Unit = { _, _ -> },
    ) =
        withContext(Dispatchers.IO) {
            try {
                val used = p.copy(assets = p.assets.filter { it.hash in p.usedAssets() })
                val manifest = PdfCodec.encode(used).toByteArray()
                if (
                    manifest.size + used.assets.sumOf { file(it.hash).length() } >
                        PdfPortableArchive.EXPANDED_LIMIT
                )
                    throw PdfOperationFailure(PdfFailure.LimitExceeded)
                storage.beforeWrite(out, manifest.size.toLong())
                ZipOutputStream(out.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest)
                    zip.closeEntry()
                    progress(1, used.assets.size + 1)
                    used.assets.forEachIndexed { index, a ->
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(ZipEntry("assets/${a.hash}"))
                        file(a.hash).inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                storage.beforeWrite(out, n.toLong())
                                zip.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                        progress(index + 2, used.assets.size + 1)
                    }
                }
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
        }

    suspend fun importPortable(uri: Uri): PdfProject =
        withContext(Dispatchers.IO) { lock.withLock { importPortableUnlocked(uri) } }

    private suspend fun importPortableUnlocked(uri: Uri, requestId: String? = null): PdfProject {
        cleanInterruptedImports()
        val temp = File(root, "import-${newId()}").apply { mkdirs() }
        return try {
            val first =
                context.contentResolver.openInputStream(uri).use {
                    requireNotNull(it)
                    it.read()
                }
            if (first != 80) return importBrowser(uri, temp, requestId)
            var manifest: String? = null
            var expanded = 0L
            val names = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source)
                ZipInputStream(source).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        require(names.add(entry.name))
                        require(names.size <= 2401)
                        require(
                            entry.name == "manifest.json" ||
                                entry.name.matches(Regex("assets/[a-f0-9]{64}"))
                        )
                        val dest = File(temp, entry.name)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buf)
                                if (n < 0) break
                                expanded += n
                                require(expanded <= 500L * 1024 * 1024)
                                storage.beforeWrite(dest, n.toLong())
                                out.write(buf, 0, n)
                                sourceChunk(expanded)
                            }
                        }
                        if (entry.name == "manifest.json") {
                            require(dest.length() <= 2_000_000)
                            manifest = dest.readText()
                        }
                        zip.closeEntry()
                    }
                }
            }
            val p = PdfCodec.decode(requireNotNull(manifest)).copy(id = newId())
            p.assets.forEach { a ->
                val f = File(temp, "assets/${a.hash}")
                require(f.isFile && sha256(f) == a.hash)
            }
            p.assets.forEach { a ->
                val f = File(temp, "assets/${a.hash}")
                if (!file(a.hash).exists()) check(f.renameTo(file(a.hash)))
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { saveUnlocked(p, requestId = requestId) }
            p
        } finally {
            temp.deleteRecursively()
        }
    }

    /** One-way migration of the V3 HTML lab's self-contained JSON; never resolves URLs. */
    private suspend fun importBrowser(uri: Uri, temp: File, requestId: String? = null): PdfProject {
        val bytes =
            context.contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 100 * 1024 * 1024)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        require(o.getString("schema") == "lightforge.pdf-studio" && o.getInt("version") == 3)
        val source = o.getJSONObject("assets")
        require(source.length() <= 2400)
        val mapped = mutableMapOf<String, PdfAsset>()
        val pageCounts = mutableMapOf<String, Int>()
        source.keys().forEach { id ->
            currentCoroutineContext().ensureActive()
            val url = source.getJSONObject(id).getString("url")
            val comma = url.indexOf(',')
            require(comma in 1..60)
            val header = url.substring(0, comma)
            require(
                header in
                    setOf(
                        "data:application/pdf;base64",
                        "data:image/jpeg;base64",
                        "data:image/png;base64",
                        "data:image/webp;base64",
                    )
            )
            val f = File(temp, newId())
            f.writeBytes(
                android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
            )
            val hash = sha256(f)
            val mime = header.removePrefix("data:").removeSuffix(";base64")
            val asset =
                if (mime == "application/pdf") {
                    pageCounts[hash] = engine.inspect(f).size
                    PdfAsset(hash, mime)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, bounds)
                    val head = ByteArray(PdfImageSignature.LENGTH)
                    val readHead =
                        f.inputStream().use { input ->
                            var filled = 0
                            while (filled < head.size) {
                                val n = input.read(head, filled, head.size - filled)
                                if (n < 0) break
                                filled += n
                            }
                            filled
                        }
                    // The archive's declared type must match the bytes, not the decoder's optional
                    // naming of them.
                    require(
                        PdfImageSignature.accept(
                            head,
                            readHead,
                            bounds.outMimeType,
                            bounds.outWidth,
                            bounds.outHeight,
                        ) == mime
                    )
                    val orientation =
                        runCatching {
                                androidx.exifinterface.media
                                    .ExifInterface(f)
                                    .getAttributeInt(
                                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                        1,
                                    )
                            }
                            .getOrDefault(1)
                            .coerceIn(1, 8)
                    PdfAsset(hash, mime, bounds.outWidth, bounds.outHeight, orientation)
                }
            mapped[id] = asset
            val target = File(temp, hash)
            if (!target.exists()) check(f.renameTo(target)) else f.delete()
        }
        val array = o.getJSONArray("pages")
        require(array.length() in 1..100)
        val pages =
            List(array.length()) { n ->
                val page = array.getJSONObject(n)
                val kind = page.getString("kind")
                require(kind in setOf("pdf", "canvas"))
                if (kind == "pdf") {
                    val hash = mapped.getValue(page.getString("source")).hash
                    val index = page.getInt("sourceIndex")
                    require(index in 0 until pageCounts.getValue(hash))
                    PdfPage(
                        width = page.getDouble("w"),
                        height = page.getDouble("h"),
                        margin = 0.0,
                        source = hash,
                        sourcePage = index,
                        rotation = page.getInt("rotation"),
                    )
                } else {
                    val items = page.getJSONArray("items")
                    require(items.length() <= 24)
                    PdfPage(
                        width = page.getDouble("w"),
                        height = page.getDouble("h"),
                        margin = page.getDouble("margin"),
                        images =
                            List(items.length()) { j ->
                                val i = items.getJSONObject(j)
                                val fit =
                                    when (i.getString("fit")) {
                                        "contain" -> PdfFit.Contain
                                        "cover" -> PdfFit.Cover
                                        else -> error("Invalid fit")
                                    }
                                PdfImage(
                                    asset = mapped.getValue(i.getString("src")).hash,
                                    x = i.getDouble("x"),
                                    y = i.getDouble("y"),
                                    width = i.getDouble("w"),
                                    height = i.getDouble("h"),
                                    fit = fit,
                                    focusX = i.getDouble("fx"),
                                    focusY = i.getDouble("fy"),
                                    locked = i.getBoolean("lock"),
                                )
                            },
                    )
                }
            }
        val unit =
            when (o.getString("units")) {
                "mm" -> PdfUnit.Millimeter
                "cm" -> PdfUnit.Centimeter
                "in" -> PdfUnit.Inch
                "px" -> PdfUnit.Pixel
                else -> error("Invalid unit")
            }
        val p =
            PdfProject(
                    name = o.getString("name").take(80),
                    pages = pages,
                    assets = mapped.values.distinctBy { it.hash },
                    unit = unit,
                    dpi = o.getInt("dpi"),
                    columns = o.optInt("columns", 2),
                    gap = o.optDouble("gap", 4.0),
                    snap = o.optBoolean("snap", false),
                )
                .validate()
        p.assets.forEach { a ->
            if (!file(a.hash).exists()) check(File(temp, a.hash).renameTo(file(a.hash)))
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { saveUnlocked(p, requestId = requestId) }
        return p
    }

    private fun cleanInterruptedImports() {
        // Every importer uses PdfStorageLock; no active writer can own these attempt directories.
        root
            .listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("import-") }
            ?.forEach { check(it.deleteRecursively()) }
    }

    companion object {

        fun sha256(file: File): String = file.inputStream().use(::hash)

        private fun hash(input: InputStream): String {
            val d = MessageDigest.getInstance("SHA-256")
            val b = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                d.update(b, 0, n)
            }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
