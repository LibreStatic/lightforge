package com.librestatic.lightforge.feature.localsharing

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

class LocalSharingController(
    private val context: Context,
    private val services: LocalSharingServices,
    private val schedule: (String) -> Unit,
    private val startReceiving: (String) -> Unit,
    private val stopReceiving: () -> Unit,
) {
    private val store = LocalSharingStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableTransfers = MutableStateFlow<List<LocalSharingTransfer>>(emptyList())
    private val mutablePeers = MutableStateFlow<List<LocalSharingPeer>>(emptyList())
    val transfers: StateFlow<List<LocalSharingTransfer>> = mutableTransfers
    val peers: StateFlow<List<LocalSharingPeer>> = mutablePeers
    val receiver
        get() = LocalSharingReceiver.state

    init {
        scope.launch { LocalSharingStore.revision.collect { refresh() } }
    }

    private fun refresh() {
        mutableTransfers.value = store.transfers()
        mutablePeers.value = runCatching { store.peers() }.getOrDefault(emptyList())
    }

    fun reconcile() {
        scope.launch {
            refresh()
            store
                .transfers()
                .filter {
                    !it.terminal &&
                        !it.pauseRequested &&
                        (it.cancelRequested ||
                            it.status in
                                setOf(
                                    LocalSharingStatus.Preparing,
                                    LocalSharingStatus.Queued,
                                    LocalSharingStatus.Transferring,
                                    LocalSharingStatus.Importing,
                                ))
                }
                .forEach { schedule(it.id) }
        }
    }

    fun close() {
        scope.cancel()
    }

    fun receive(host: String) {
        require(peerHost(host))
        startReceiving(host)
    }

    fun stopReceive() {
        stopReceiving()
    }

    fun approvePair(pin: String) {
        LocalSharingReceiver.approve(pin)
    }

    fun rejectPair(pin: String) {
        LocalSharingReceiver.reject(pin)
    }

    fun invitationText(invitation: LocalSharingInvitation) = PeerCodec.invitation(invitation)

    fun parseInvitation(text: String) = PeerCodec.invitation(text)

    /**
     * Explicit sender action confirms the receiver's OOB TLS pin; receiver must separately approve.
     */
    suspend fun pair(invitation: LocalSharingInvitation, name: String): Boolean =
        withContext(Dispatchers.IO) {
            invitation.validate()
            require(peerName(name))
            require(services.networkAllowed())
            val sockets = PeerSockets()
            try {
                val response =
                    PeerClient(context, sockets)
                        .request(
                            invitation.host,
                            invitation.port,
                            invitation.pin,
                            JSONObject()
                                .put("op", "pair")
                                .put("secret", invitation.secret)
                                .put("name", name),
                        )
                if (response.getString("status") != "paired") return@withContext false
                val tls = PeerTls(context)
                tls.saveSecret(invitation.pin, response.getString("token"))
                store.savePeer(
                    LocalSharingPeer(
                        invitation.pin,
                        response.getString("name"),
                        invitation.host,
                        invitation.port,
                    )
                )
                true
            } finally {
                sockets.close()
            }
        }

    suspend fun revoke(pin: String) =
        withContext(Dispatchers.IO) {
            val peer = store.peers().single { it.id == pin }
            store.savePeer(peer.copy(revoked = true))
            PeerTls(context).revoke(pin)
            store
                .transfers()
                .filter { it.peerId == pin && !it.terminal }
                .forEach {
                    store.update(it.id) { t ->
                        t.copy(
                            pauseRequested = true,
                            status = LocalSharingStatus.Paused,
                            failure = "revoked",
                            resumeStatus = t.resumeStatus ?: t.status,
                        )
                    }
                }
        }

    suspend fun enqueue(peerId: String, selection: List<String>, stripLocation: Boolean): String =
        withContext(Dispatchers.IO) {
            require(
                selection.size in 1..LOCAL_SHARING_MAX_FILES &&
                    selection.distinct().size == selection.size
            )
            require(store.peers().any { it.id == peerId && !it.revoked && it.canSend })
            val id = UUID.randomUUID().toString()
            store.create(
                LocalSharingTransfer(
                    id,
                    peerId,
                    LocalSharingDirection.Send,
                    selection = selection,
                    stripLocation = stripLocation,
                )
            )
            try {
                services.sourcePort.retain(id, selection)
                schedule(id)
            } catch (e: Exception) {
                store.update(id) {
                    it.copy(status = LocalSharingStatus.WaitingPeer, failure = "permission")
                }
                throw e
            }
            id
        }

    suspend fun confirmSend(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                require(
                    it.status == LocalSharingStatus.AwaitingReview &&
                        it.direction == LocalSharingDirection.Send
                )
                it.copy(status = LocalSharingStatus.Queued)
            }
            schedule(id)
        }

    suspend fun acceptReceive(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                require(it.status == LocalSharingStatus.AwaitingReceiveConsent)
                require(
                    store.directory(id).usableSpace >= it.manifest!!.totalBytes + 16 * 1024 * 1024
                )
                it.manifest.entries.forEachIndexed { index, e ->
                    if (e.bytes == 0L)
                        java.io.RandomAccessFile(store.received(id, index), "rw").use { out ->
                            require(out.length() == 0L)
                            out.fd.sync()
                        }
                }
                it.copy(status = LocalSharingStatus.Transferring)
            }
        }

    suspend fun reviewImport(id: String): List<LocalSharingImportItem> =
        withContext(Dispatchers.IO) {
            val t = store.transfer(id)!!
            require(t.status == LocalSharingStatus.ReadyToImport)
            services.importPort.review(t.peerId, t.id, t.manifest!!).also { items ->
                require(
                    items.size == t.manifest.entries.size &&
                        items.map { it.sourceId }.toSet() ==
                            t.manifest.entries.map { it.sourceId }.toSet()
                )
            }
        }

    suspend fun confirmImport(id: String, choices: Map<String, LocalSharingConflictChoice>) =
        withContext(Dispatchers.IO) {
            val review = reviewImport(id)
            require(
                choices.keys ==
                    review
                        .filter { it.disposition == LocalSharingImportDisposition.Conflict }
                        .map { it.sourceId }
                        .toSet()
            )
            store.update(id) {
                require(it.status == LocalSharingStatus.ReadyToImport)
                it.copy(status = LocalSharingStatus.Importing, choices = choices)
            }
            schedule(id)
        }

    suspend fun pause(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                if (it.terminal) it
                else
                    it.copy(
                        pauseRequested = true,
                        status = LocalSharingStatus.Paused,
                        resumeStatus = it.resumeStatus ?: it.status,
                    )
            }
        }

    suspend fun resume(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) {
                require(!it.terminal && it.failure != "corrupt")
                require(store.peers().any { p -> p.id == it.peerId && !p.revoked })
                it.copy(
                    pauseRequested = false,
                    failure = null,
                    status =
                        it.resumeStatus
                            ?: if (it.direction == LocalSharingDirection.Receive)
                                LocalSharingStatus.Transferring
                            else if (it.manifest == null) LocalSharingStatus.Preparing
                            else LocalSharingStatus.Queued,
                    resumeStatus = null,
                )
            }
            schedule(id)
        }

    suspend fun regrant(id: String, selection: List<String>) =
        withContext(Dispatchers.IO) {
            val t = store.transfer(id)!!
            require(t.selection.toSet() == selection.toSet())
            services.sourcePort.retain(id, selection)
            resume(id)
        }

    suspend fun cancel(id: String) =
        withContext(Dispatchers.IO) {
            store.update(id) { if (it.terminal) it else it.copy(cancelRequested = true) }
            schedule(id)
        }
}
