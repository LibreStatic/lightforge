package com.ugallery.feature.localsharing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

internal class PeerFixtureContext(base: Context, val label: String) : ContextWrapper(base) {
    private val storage = File(base.cacheDir, "peer-fixture-$label").apply { mkdirs() }

    override fun getFilesDir() = storage

    override fun getApplicationContext(): Context = this

    override fun getPackageName() = super.getPackageName() + ".fixture." + label

    fun cleanup() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(
                ".ugallery-local-sharing-identity-v1",
                ".ugallery-local-sharing-identity-v2",
                ".ugallery-local-sharing-vault-v1",
            )
            .forEach { keys.deleteEntry(packageName + it) }
        storage.deleteRecursively()
    }
}

internal class PeerFixtureSource(
    var bytes: ByteArray = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
) : LocalSharingSourcePort {
    var sanitized = true
    var fail = false
    var grants = 0

    override suspend fun retain(selection: List<String>) {
        grants++
    }

    override suspend fun prepare(
        selection: List<String>,
        stripLocation: Boolean,
        destination: File,
        check: () -> Unit,
    ): List<LocalSharingPreparedSource> {
        check()
        if (fail) throw java.io.IOException("fixture")
        val file = File(destination, "original.bin")
        file.writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).peerHex()
        return listOf(
            LocalSharingPreparedSource(
                LocalSharingEntry(
                    "fixture-source",
                    "rev-$sha",
                    "original.jpg",
                    "image/jpeg",
                    bytes.size.toLong(),
                    sha,
                    100,
                    sanitized,
                ),
                file.name,
            )
        )
    }
}

internal class PeerFixtureImport(private val context: Context) : LocalSharingImportPort {
    var enqueues = 0
    var copies = 0
    var disposition = LocalSharingImportDisposition.Add

    override suspend fun review(
        peerId: String,
        transferId: String,
        manifest: LocalSharingManifest,
    ) = manifest.entries.map { LocalSharingImportItem(it.sourceId, disposition, 50) }

    override suspend fun existing(transferId: String) =
        File(context.filesDir, "receipt-$transferId").takeIf { it.exists() }?.readText()

    override suspend fun enqueueOnce(
        peerId: String,
        transferId: String,
        manifest: LocalSharingManifest,
        files: List<LocalSharingReceivedFile>,
        choices: Map<String, LocalSharingConflictChoice>,
    ): String {
        existing(transferId)?.let {
            return it
        }
        enqueues++
        files.forEach { require(peerDigest(it.file) == (it.entry.bytes to it.entry.sha256)) }
        copies +=
            if (disposition == LocalSharingImportDisposition.AlreadyReceived) 0 else files.size
        val id = UUID.randomUUID().toString()
        File(context.filesDir, "receipt-$transferId").writeText(id)
        return id
    }
}

internal class PeerFixture : AutoCloseable {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val sender = PeerFixtureContext(base, UUID.randomUUID().toString())
    val recipient = PeerFixtureContext(base, UUID.randomUUID().toString())
    val source = PeerFixtureSource()
    val imported = PeerFixtureImport(recipient)
    val services = LocalSharingServices(source, imported) { true }
    val sendController = LocalSharingController(sender, services, {}, {}, {})
    val receiveController = LocalSharingController(recipient, services, {}, {}, {})
    val sendStore = LocalSharingStore(sender)
    val receiveStore = LocalSharingStore(recipient)
    val server = LocalSharingReceiver(recipient, services)
    val invitation = server.start("127.0.0.1")

    suspend fun pair() {
        check(!sendController.pair(invitation, "Fixture sender"))
        LocalSharingReceiver.approve(PeerTls(sender).pin)
        check(sendController.pair(invitation, "Fixture sender"))
    }

    suspend fun prepare(): String {
        val id = sendController.enqueue(invitation.pin, listOf("content://fixture/original"), true)
        LocalSharingRunner(sender, services).run(id)
        check(sendStore.transfer(id)!!.status == LocalSharingStatus.AwaitingReview)
        sendController.confirmSend(id)
        LocalSharingRunner(sender, services).run(id)
        check(receiveStore.transfer(id)!!.status == LocalSharingStatus.AwaitingReceiveConsent)
        return id
    }

    suspend fun complete(id: String) {
        receiveController.acceptReceive(id)
        sendController.resume(id)
        LocalSharingRunner(sender, services).run(id)
        check(sendStore.transfer(id)!!.status == LocalSharingStatus.Completed)
    }

    override fun close() {
        server.close()
        sendController.close()
        receiveController.close()
        sender.cleanup()
        recipient.cleanup()
    }
}
