package com.ugallery.core.mediastore

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class VerifiedTreeCopy(val uri: Uri, val bytes: Long, val sha256: String)
data class MoveSourceIdentity(val generationAdded: Long, val generationModified: Long)

object VerifiedMoveOperations {
    fun target(proof: VerifiedMoveProof) = MediaActionTarget(MediaKey(proof.targetVolume, proof.targetId), MediaKind.valueOf(proof.targetKind))

    suspend fun identity(resolver: ContentResolver, source: Uri): MoveSourceIdentity? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        requireNotNull(resolver.query(source, arrayOf(MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)) { "Source query failed" }.use { cursor ->
            if (!cursor.moveToFirst()) null else MoveSourceIdentity(cursor.getLong(0), cursor.getLong(1))
        }
    }

    suspend fun verifyDestination(resolver: ContentResolver, proof: VerifiedMoveProof) = withContext(Dispatchers.IO) {
        proof.validated()
        val job = coroutineContext
        resolver.openInputStream(Uri.parse(proof.destinationUri)).use { input ->
            VerifiedStreamCopy.verify(requireNotNull(input), VerifiedStreamCopy.Fingerprint(proof.bytes, proof.sha256)) { job.ensureActive() }
        }
    }

    /** A missing source is accepted only for reconciliation, never for a new Delete request. */
    suspend fun verify(resolver: ContentResolver, proof: VerifiedMoveProof, allowMissingSource: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        proof.validated()
        check(target(proof).mediaUri().toString() == proof.sourceUri)
        verifyDestination(resolver, proof)
        val source = Uri.parse(proof.sourceUri)
        val before = identity(resolver, source)
        if (before == null) {
            check(allowMissingSource) { "Original is no longer available" }
            return@withContext false
        }
        check(before == MoveSourceIdentity(proof.generationAdded, proof.generationModified)) { "Original identity changed" }
        val job = coroutineContext
        resolver.openInputStream(source).use { input ->
            VerifiedStreamCopy.verify(requireNotNull(input), VerifiedStreamCopy.Fingerprint(proof.bytes, proof.sha256)) { job.ensureActive() }
        }
        check(identity(resolver, source) == before) { "Original changed during verification" }
        coroutineContext.ensureActive()
        true
    }
}
