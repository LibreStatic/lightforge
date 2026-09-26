package com.ugallery.feature.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersistableUriGrantsTest {
    private fun row(priorRead: Boolean, priorWrite: Boolean, vararg tasks: Pair<String, Int>) =
        JSONObject()
            .put("priorRead", priorRead)
            .put("priorWrite", priorWrite)
            .put("tasks", JSONObject().apply { tasks.forEach { (id, flags) -> put(id, flags) } })

    @Test
    fun releasesOnlyWhenTheLastTaskIsForgotten() {
        val root = JSONObject().put("content://a", row(false, false, "t1" to 1, "t2" to 1))
        assertEquals(emptyMap<String, Int>(), PersistableUriGrants.forget(root) { it != "t1" })
        assertTrue(root.has("content://a"))
        assertEquals(mapOf("content://a" to 1), PersistableUriGrants.forget(root) { it != "t2" })
        assertFalse(root.has("content://a"))
    }

    @Test
    fun neverReleasesGrantsTheAppHeldBefore() {
        val root =
            JSONObject()
                .put("content://prior", row(true, false, "t1" to 3))
                .put("content://both", row(true, true, "t1" to 3))
        assertEquals(mapOf("content://prior" to 2), PersistableUriGrants.forget(root) { false })
        assertEquals(0, root.length())
    }

    @Test
    fun keepsFlagsOfTasksForgottenWhileTheUriWasShared() {
        val root = JSONObject().put("content://d", row(false, false, "write" to 3, "read" to 1))
        PersistableUriGrants.forget(root) { it != "write" }
        assertEquals(mapOf("content://d" to 3), PersistableUriGrants.forget(root) { false })
    }

    @Test
    fun staleSweepKeepsLiveTasks() {
        val root =
            JSONObject()
                .put("content://done", row(false, false, "done" to 1))
                .put("content://live", row(false, false, "live" to 1, "done" to 1))
        val live = setOf("live")
        assertEquals(mapOf("content://done" to 1), PersistableUriGrants.forget(root) { it in live })
        assertEquals(
            setOf("live"),
            root.getJSONObject("content://live").getJSONObject("tasks").keys().asSequence().toSet(),
        )
    }

    @Test
    fun legacyRowsWithoutOwnedStillRelease() {
        val root = JSONObject().put("content://old", row(false, false, "t" to 1))
        assertFalse(root.getJSONObject("content://old").has("owned"))
        assertEquals(mapOf("content://old" to 1), PersistableUriGrants.forget(root) { false })
    }

    @Test
    fun restoreHandoffTransfersTheDestinationGrantExactlyOnce() {
        // Remote ledger took read+write on the destination for its download task.
        val remote = JSONObject().put("content://dest", row(false, false, "remote" to 3))
        val transferred = PersistableUriGrants.ownedBy(remote, "content://dest", "remote")
        assertEquals(3, transferred)
        // Local ledger saw the grant as already held (protected) and adopts the remote's flags.
        val local = JSONObject().put("protected", 3).put("owned", 0)
        LocalBackupTaskGrants.adopt(local, transferred)
        assertEquals(0, local.getInt("protected"))
        assertEquals(3, local.getInt("owned"))
        // Remote forgets the task without releasing anything, now or on a later sweep.
        PersistableUriGrants.handOff(remote, "content://dest", "remote")
        assertFalse(remote.has("content://dest"))
        assertEquals(emptyMap<String, Int>(), PersistableUriGrants.forget(remote) { false })
        // A repeated handoff is a no-op and transfers nothing more.
        assertEquals(0, PersistableUriGrants.ownedBy(remote, "content://dest", "remote"))
    }

    @Test
    fun sharedHandoffKeepsTheTransferredFlagsOutOfTheRemoteRelease() {
        val remote =
            JSONObject().put("content://dest", row(false, false, "remote" to 3, "other" to 1))
        PersistableUriGrants.handOff(remote, "content://dest", "remote")
        assertEquals(emptyMap<String, Int>(), PersistableUriGrants.forget(remote) { false })
    }
}
