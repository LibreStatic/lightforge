package com.librestatic.lightforge.feature.ownsync

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnSyncSafDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = Uri.parse("content://com.librestatic.lightforge.feature.ownsync.test.source")
    private val tree = Uri.parse("$base/tree/root")

    @Test
    fun realSafRecursiveEnumerationPreservesPathAndVerifiesHashes() =
        runBlocking<Unit> {
            context.contentResolver.call(base, "fixture-reset", null, null)
            val source = SafOwnSyncSource(context)
            val snapshot = source.scan(tree)
            assertTrue(snapshot.issues.toString(), snapshot.complete)
            assertEquals(2, snapshot.entries.size)
            assertTrue(snapshot.entries.any { it.path == listOf("Photos", "nested.png") })
            assertTrue(snapshot.entries.any { it.path == listOf("top.pdf") })
            snapshot.entries.forEach { entry ->
                assertEquals(entry.digest, source.open(entry).use { OwnSyncIO.copy(it) })
            }
        }

    @Test
    fun loadingCursorIsIncompleteEvenWhenAllVisibleBytesAreReadable() =
        runBlocking<Unit> {
            try {
                context.contentResolver.call(base, "fixture-loading", null, null)
                val snapshot = SafOwnSyncSource(context).scan(tree)
                assertEquals(2, snapshot.entries.size)
                assertFalse(snapshot.complete)
                assertTrue("loading" in snapshot.issues)
            } finally {
                context.contentResolver.call(base, "fixture-reset", null, null)
            }
        }
}
