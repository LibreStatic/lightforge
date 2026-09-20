package com.ugallery.app

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.mediastore.ScopedMediaOperations
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import android.net.Uri
import android.os.Bundle
import java.security.MessageDigest
import java.util.UUID

class SafCopyVerificationDeviceTest {
    @Test fun successfulCopyReturnsOnlyAfterExactDestinationReadback() = exercise("normal", true)
    @Test fun truncatedDestinationFailsAndDeletesOnlyOwnCopy() = exercise("truncate", false)
    @Test fun sameLengthCorruptedDestinationFailsAndDeletesOnlyOwnCopy() = exercise("corrupt", false)
    @Test fun sourceChangedDuringReadbackRejectsCopyAndPreservesChangedSource() = exercise("source-mutation", false)

    private fun exercise(mode: String, success: Boolean) = runBlocking {
        // Provider belongs to instrumentation APK, not normal target app.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val authority = fixturePackage + ".safcopyfixture"
        val uuid = UUID.randomUUID().toString()
        val endpoint = Uri.parse("content://$authority")
        context.sendBroadcast(android.content.Intent("com.ugallery.SAF_FIXTURE").addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES or android.content.Intent.FLAG_RECEIVER_FOREGROUND).setComponent(android.content.ComponentName(fixturePackage,
            "com.ugallery.app.SafCopyFixtureGrantReceiver")).putExtra("target", InstrumentationRegistry.getInstrumentation().targetContext.packageName))
        kotlinx.coroutines.withTimeout(5_000) {
            while (true) {
                try {
                    val client = context.contentResolver.acquireUnstableContentProviderClient(endpoint)
                    if (client != null) { client.close(); break }
                } catch (_: SecurityException) { }
                kotlinx.coroutines.delay(25)
            }
        }
        fun status() = requireNotNull(context.contentResolver.call(endpoint, "fixtureStatus", uuid, null))
        check(requireNotNull(context.contentResolver.call(endpoint, "fixtureSetup", uuid,
            Bundle().apply { putString("mode", mode) })).getBoolean("ready"))
        val bytes = ByteArray(32789) { (it % 251).toByte() }
        val sourceUri = DocumentsContract.buildDocumentUri(authority, "$uuid:source")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "$uuid:root")
        try {
            val outcome = runCatching { ScopedMediaOperations.copyToTree(context.contentResolver, sourceUri, tree, "copy.bin", "application/octet-stream") }
            assertTrue("Destination readback fault must be exercised: $mode", status().getBoolean("injected"))
            if (success) {
                val output = outcome.getOrThrow()
                assertTrue("Engine must read destination before return", status().getBoolean("injected"))
                val observed = context.contentResolver.openInputStream(output)!!.use { it.readBytes() }
                assertArrayEquals(bytes, observed)
            } else {
                assertTrue("Damaged copy unexpectedly returned success: $mode", outcome.isFailure)
                assertFalse("Failed destination not removed", status().getBoolean("destinationExists"))
            }
            val expectedSource = if (mode == "source-mutation") bytes + byteArrayOf(99) else bytes
            val hash = MessageDigest.getInstance("SHA-256").digest(expectedSource).joinToString("") { "%02x".format(it) }
            assertEquals(hash, status().getString("sourceSHA"))
            assertEquals(expectedSource.size, status().getInt("sourceSize"))
            println("SAF_COPY mode=$mode success=${outcome.isSuccess} originalSHA=$hash destinationExists=${status().getBoolean("destinationExists")} failure=${outcome.exceptionOrNull()?.javaClass?.name}")
        } finally { check(requireNotNull(context.contentResolver.call(endpoint, "fixtureCleanup", uuid, null)).getBoolean("absent")) }
    }
}
