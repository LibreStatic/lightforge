package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Ephemeral review of the exact destination bytes; never persisted as authorization. */
data class MoveCopyReview(
    val destinationUri: String,
    val documentId: String,
    val displayName: String?,
    val mime: String?,
    val modifiedMillis: Long?,
    val bytes: Long,
    val sha256: String,
)

/** Only Move uses this pre-journaled destination path. Ordinary Copy remains unchanged. */
object MoveCopyOperations {
    suspend fun capture(
        resolver: ContentResolver, target: MediaActionTarget, tree: Uri, name: String,
        mime: String, lastModifiedMillis: Long? = null, grantReadAcquired: Boolean = false,
    ): MoveCopyDraft = withContext(Dispatchers.IO) {
        ScopedMediaOperations.validateDisplayName(name)
        val source = target.mediaUri()
        val before = requireNotNull(VerifiedMoveOperations.identity(resolver, source))
        val fingerprint = fingerprint(resolver, source)
        check(VerifiedMoveOperations.identity(resolver, source) == before) { "Original changed during capture" }
        MoveCopyDraft(UUID.randomUUID().toString(), target.key.volumeName, target.key.mediaStoreId,
            target.kind.name, source.toString(), tree.toString(), name, resolver.getType(source) ?: mime.takeUnless { it.endsWith("/*") } ?: "application/octet-stream", lastModifiedMillis,
            fingerprint.bytes, fingerprint.sha256, before.generationAdded, before.generationModified,
            destinationUri = null, grantReadAcquired = grantReadAcquired).validated()
    }

    suspend fun verifySource(resolver: ContentResolver, draft: MoveCopyDraft) = withContext(Dispatchers.IO) {
        draft.validated()
        val source = Uri.parse(draft.sourceUri)
        val expected = MoveSourceIdentity(draft.generationAdded, draft.generationModified)
        check(VerifiedMoveOperations.identity(resolver, source) == expected) { "Original identity changed" }
        check(fingerprint(resolver, source) == VerifiedStreamCopy.Fingerprint(draft.bytes, draft.sha256)) { "Original bytes changed" }
        check(VerifiedMoveOperations.identity(resolver, source) == expected) { "Original changed during verification" }
    }

    /** Caller must persist CopyIntent before this, and attach returned URI before opening output. */
    suspend fun createDestination(resolver: ContentResolver, draft: MoveCopyDraft): Uri = withContext(Dispatchers.IO) {
        require(draft.destinationUri == null)
        verifySource(resolver, draft)
        val tree = Uri.parse(draft.treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = resolver.getType(Uri.parse(draft.sourceUri))
            ?: draft.mime.takeUnless { it.endsWith("/*") } ?: "application/octet-stream"
        val destination = requireNotNull(DocumentsContract.createDocument(resolver, parent, mime, draft.name))
        draft.copy(destinationUri = destination.toString()).validated()
        check(destination != Uri.parse(draft.sourceUri) && destination != parent)
        destination
    }

    suspend fun review(resolver: ContentResolver, draft: MoveCopyDraft): MoveCopyReview = withContext(Dispatchers.IO) {
        draft.validated()
        val uri = Uri.parse(requireNotNull(draft.destinationUri))
        val before = documentIdentity(resolver, uri)
        val bytes = fingerprint(resolver, uri)
        check(documentIdentity(resolver, uri) == before) { "Destination changed during review" }
        before.copy(bytes = bytes.bytes, sha256 = bytes.sha256)
    }

    /** Initial writes also require a bound empty-destination review after URI journal commit. */
    suspend fun rewrite(resolver: ContentResolver, draft: MoveCopyDraft, reviewed: MoveCopyReview) = withContext(Dispatchers.IO) {
        verifySource(resolver, draft)
        check(review(resolver, draft) == reviewed) { "Reviewed copy changed" }
        if (reviewed.bytes != draft.bytes || reviewed.sha256 != draft.sha256) {
            val job = coroutineContext
            resolver.openInputStream(Uri.parse(draft.sourceUri)).use { source ->
                // wt explicitly requests truncation; w alone is provider-dependent.
                resolver.openOutputStream(Uri.parse(requireNotNull(draft.destinationUri)), "wt").use { output ->
                    val actual = VerifiedStreamCopy.copy(requireNotNull(source), requireNotNull(output)) { job.ensureActive() }
                    check(actual == VerifiedStreamCopy.Fingerprint(draft.bytes, draft.sha256)) { "Original changed while copying" }
                }
            }
        }
        draft.lastModifiedMillis?.takeIf { it > 0L }?.let { timestamp ->
            runCatching {
                resolver.update(Uri.parse(requireNotNull(draft.destinationUri)), android.content.ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, timestamp)
                }, null, null)
            }
        }
        // Any failure leaves the known partial destination and draft intact for explicit recovery.
        VerifiedMoveOperations.verify(resolver, draft.toProof())
        coroutineContext.ensureActive()
    }

    suspend fun discard(resolver: ContentResolver, draft: MoveCopyDraft, reviewed: MoveCopyReview) = withContext(Dispatchers.IO) {
        check(review(resolver, draft) == reviewed) { "Reviewed copy changed" }
        coroutineContext.ensureActive()
        val uri = Uri.parse(requireNotNull(draft.destinationUri))
        check(DocumentsContract.deleteDocument(resolver, uri)) { "Destination deletion was not acknowledged" }
        val absent = try {
            val cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            if (cursor != null) cursor.use { !it.moveToFirst() }
            else {
                // DocumentsProvider can convert FileNotFoundException from queryDocument to null.
                // Null alone proves nothing: require an explicit missing-file result from open.
                resolver.openFileDescriptor(uri, "r").use { requireNotNull(it); false }
            }
        } catch (_: FileNotFoundException) { true }
        check(absent) { "Destination still exists" }
    }

    private fun documentIdentity(resolver: ContentResolver, uri: Uri): MoveCopyReview {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return requireNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
            check(cursor.moveToFirst()) { "Destination is missing" }
            fun value(i: Int): String? = cursor.getColumnIndex(columns[i]).takeIf { it >= 0 }?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
            val id = requireNotNull(value(0))
            check(id == DocumentsContract.getDocumentId(uri)) { "Destination identity changed" }
            MoveCopyReview(uri.toString(), id, value(1), value(2), value(3)?.toLong(), 0, "")
        }
    }

    private suspend fun fingerprint(resolver: ContentResolver, uri: Uri): VerifiedStreamCopy.Fingerprint {
        val job = coroutineContext
        return resolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                job.ensureActive()
                val count = input.read(buffer)
                job.ensureActive()
                if (count < 0) break
                if (count == 0) continue
                bytes = Math.addExact(bytes, count.toLong())
                digest.update(buffer, 0, count)
            }
            VerifiedStreamCopy.Fingerprint(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
