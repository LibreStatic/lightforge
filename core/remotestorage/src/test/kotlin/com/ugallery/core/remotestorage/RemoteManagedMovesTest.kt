package com.ugallery.core.remotestorage

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

class RemoteManagedMovesTest {
    private class Memory : RemoteConnection {
        val files = linkedMapOf<String, ByteArray>()
        var renames = 0
        override val capabilities = RemoteCapabilities(true, true, true)
        override val identity = "fixture"
        override fun list(limit: Int) = files.keys.map { stat(it)!! }
        override fun stat(name: String) = files[name]?.let { RemoteEntry(name, it.size.toLong(), 0, true) }
        override fun openRead(name: String, offset: Long) = ByteArrayInputStream(files[name]!!.drop(offset.toInt()).toByteArray())
        override fun createExclusive(name: String) = object : ByteArrayOutputStream() {
            init { check(name !in files) }
            override fun close() { files[name] = toByteArray() }
        }
        override fun publishNoReplace(staging: String, destination: String, expected: RemoteDigest) = error("unused")
        override fun close() = Unit
        fun rename(from: String, to: String) {
            renames++
            if (to in files) throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
            files[to] = files.remove(from) ?: throw RemoteStorageException(RemoteFailure.NOT_FOUND)
        }
    }
    private fun digest(bytes: ByteArray) = RemoteDigest(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private val bytes = "original-photo".toByteArray()
    private fun fixture() = Memory().also { it.files["photo.jpg"] = bytes.copyOf() }

    @Test fun verifiedMoveRetryAndRestorationKeepExactBytes() {
        val remote = fixture(); val expected = digest(bytes)
        assertEquals(RemoteManagedMoveState.VerifiedMoved, RemoteManagedMoves.move(remote,"photo.jpg","quarantine",expected,remote::rename).state)
        assertEquals(RemoteManagedMoveState.AlreadyMoved, RemoteManagedMoves.move(remote,"photo.jpg","quarantine",expected,remote::rename).state)
        assertEquals(1,remote.renames)
        assertTrue(RemoteManagedMoves.move(remote,"quarantine","photo.jpg",expected,remote::rename).verified)
        assertArrayEquals(bytes,remote.files["photo.jpg"]); assertFalse(remote.files.containsKey("quarantine"))
    }
    @Test fun changedSourceIsNotMoved() {
        val remote = fixture(); remote.files["photo.jpg"] = "changed".toByteArray()
        assertEquals(RemoteManagedMoveState.SourceChanged,RemoteManagedMoves.move(remote,"photo.jpg","quarantine",digest(bytes),remote::rename).state)
        assertEquals(0,remote.renames);assertEquals(1,remote.files.size)
    }
    @Test fun occupiedDestinationIsNotReplaced() {
        val remote=fixture();remote.files["quarantine"]="someone else's".toByteArray()
        assertEquals(RemoteManagedMoveState.TargetOccupied,RemoteManagedMoves.move(remote,"photo.jpg","quarantine",digest(bytes),remote::rename).state)
        assertEquals(0,remote.renames);assertArrayEquals(bytes,remote.files["photo.jpg"])
    }
    @Test fun concurrentReplacementIsRestoredWithoutBeingDeleted() {
        val remote=fixture();val changed="concurrent replacement".toByteArray()
        val outcome=RemoteManagedMoves.move(remote,"photo.jpg","quarantine",digest(bytes)) { from,to ->
            if (from=="photo.jpg") remote.files[from]=changed
            remote.rename(from,to)
        }
        assertEquals(RemoteManagedMoveState.ConflictRestored,outcome.state)
        assertArrayEquals(changed,remote.files["photo.jpg"]);assertFalse(remote.files.containsKey("quarantine"))
    }
    @Test fun concurrentSourceOccupancyRetainsBothChangedObjects() {
        val remote=fixture();val changed="concurrent replacement".toByteArray();val other="new source".toByteArray()
        val outcome=RemoteManagedMoves.move(remote,"photo.jpg","quarantine",digest(bytes)) { from,to ->
            remote.files[from]=changed;remote.rename(from,to);remote.files[from]=other
        }
        assertEquals(RemoteManagedMoveState.RetainedAmbiguous,outcome.state)
        assertArrayEquals(changed,remote.files["quarantine"]);assertArrayEquals(other,remote.files["photo.jpg"])
    }
    @Test fun uncertainReplyRetainsNamesAndRetryVerifiesDestination() {
        val remote=fixture();val expected=digest(bytes)
        try {
            RemoteManagedMoves.move(remote,"photo.jpg","quarantine",expected) { from,to -> remote.rename(from,to);throw IOException("lost response") }
            fail("Expected an uncertain outcome")
        } catch (error: RemoteStorageException) { assertEquals(listOf("photo.jpg","quarantine"),error.residualNames) }
        assertEquals(RemoteManagedMoveState.AlreadyMoved,RemoteManagedMoves.move(remote,"photo.jpg","quarantine",expected,remote::rename).state)
        assertArrayEquals(bytes,remote.files["quarantine"])
    }
    @Test fun missingSourceAndWrongDestinationAreAmbiguousNotSuccess() {
        val remote=Memory();remote.files["quarantine"]="wrong".toByteArray()
        assertEquals(RemoteManagedMoveState.RetainedAmbiguous,RemoteManagedMoves.move(remote,"photo.jpg","quarantine",digest(bytes),remote::rename).state)
        assertEquals(0,remote.renames)
    }
}
