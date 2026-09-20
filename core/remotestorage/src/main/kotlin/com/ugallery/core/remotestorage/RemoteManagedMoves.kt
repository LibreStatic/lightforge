package com.ugallery.core.remotestorage

import java.security.MessageDigest

/** Path-based protocols never equate rename success with a verified, unchanged managed object. */
internal object RemoteManagedMoves {
    fun digest(connection: RemoteConnection, name: String): RemoteDigest? {
        val before = connection.stat(name) ?: return null
        if (!before.regularFile) throw RemoteStorageException(RemoteFailure.INVALID_PATH)
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        connection.openRead(name).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw RemoteStorageException(RemoteFailure.CONNECTION)
                size = Math.addExact(size, count.toLong())
                if (size > before.size) throw RemoteStorageException(RemoteFailure.CONFLICT)
                hash.update(buffer, 0, count)
            }
        }
        if (size != before.size || connection.stat(name) != before)
            throw RemoteStorageException(RemoteFailure.CONFLICT)
        return RemoteDigest(size, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun move(connection: RemoteConnection, source: String, destination: String,
             expected: RemoteDigest, renameNoReplace: (String, String) -> Unit): RemoteManagedMove {
        RemoteNames.requireChild(source); RemoteNames.requireChild(destination)
        require(!source.equals(destination, ignoreCase = true))
        fun result(state: RemoteManagedMoveState, observed: RemoteDigest? = null) =
            RemoteManagedMove(state, source, destination, observed)
        val initial = try { digest(connection, source) }
            catch (error: RemoteStorageException) {
                if (error.failure == RemoteFailure.CONFLICT || error.failure == RemoteFailure.NOT_FOUND)
                    return result(RemoteManagedMoveState.SourceChanged)
                throw error
            }
        if (initial == null) {
            val existing = digest(connection, destination)
            return if (existing == expected) result(RemoteManagedMoveState.AlreadyMoved, existing)
            else result(RemoteManagedMoveState.RetainedAmbiguous, existing)
        }
        if (initial != expected) return result(RemoteManagedMoveState.SourceChanged)
        if (connection.stat(destination) != null) return result(RemoteManagedMoveState.TargetOccupied)
        try {
            renameNoReplace(source, destination)
            val observed = digest(connection, destination)
            if (observed == expected && connection.stat(source) == null)
                return result(RemoteManagedMoveState.VerifiedMoved, observed)
            // A concurrent replacement is still retained in quarantine. Try to put it back only
            // if its original name is free; the protocol MUST enforce destination non-replacement.
            if (observed != null && connection.stat(source) == null) {
                try {
                    renameNoReplace(destination, source)
                    if (digest(connection, source) == observed && connection.stat(destination) == null)
                        return result(RemoteManagedMoveState.ConflictRestored, observed)
                } catch (_: Exception) { /* Both exact names remain journaled, never unlink. */ }
            }
            return result(RemoteManagedMoveState.RetainedAmbiguous, observed)
        } catch (error: RemoteStorageException) {
            throw RemoteStorageException(error.failure, cause = error, residualNames = listOf(source, destination))
        } catch (error: Exception) {
            throw RemoteStorageException(RemoteFailure.CONNECTION, cause = error, residualNames = listOf(source, destination))
        }
    }
}
