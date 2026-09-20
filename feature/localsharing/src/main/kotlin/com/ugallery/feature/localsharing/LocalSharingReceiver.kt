package com.ugallery.feature.localsharing

import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalSharingReceiverState(
    val active: Boolean = false,
    val invitation: LocalSharingInvitation? = null,
    val pending: List<LocalSharingPairRequest> = emptyList(),
    val failure: String? = null,
)

/**
 * App foreground service owns this object. close() ends listening AND every accepted TLS socket.
 */
class LocalSharingReceiver(
    private val context: Context,
    private val services: LocalSharingServices,
) : Closeable {
    private val store = LocalSharingStore(context)
    private val tls = PeerTls(context)
    private val sockets = PeerSockets()
    private var server: SSLServerSocket? = null
    private val pool =
        ThreadPoolExecutor(2, 4, 15, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8))
    private var invitation: LocalSharingInvitation? = null
    private val approved = mutableMapOf<String, String>()
    private var attempts = 0
    private var attemptWindow = 0L

    @Synchronized
    fun start(advertisedHost: String, port: Int = 0): LocalSharingInvitation {
        require(peerHost(advertisedHost))
        require(port == 0 || port in 1024..65535)
        require(server == null && services.networkAllowed())
        synchronized(activeLock) {
            require(active == null)
            active = this
        }
        try {
            val listener =
                tls.context().serverSocketFactory.createServerSocket(port) as SSLServerSocket
            listener.needClientAuth = true
            listener.enabledProtocols =
                listener.supportedProtocols
                    .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                    .toTypedArray()
            server = listener
            sockets.add(listener)
            val info =
                LocalSharingInvitation(
                    advertisedHost,
                    listener.localPort,
                    tls.pin,
                    PeerTls.randomSecret(),
                    System.currentTimeMillis() + 5 * 60_000,
                )
            invitation = info
            mutableState.value = LocalSharingReceiverState(true, info)
            Thread(
                    {
                        try {
                            while (!listener.isClosed) {
                                val socket = listener.accept() as SSLSocket
                                sockets.add(socket)
                                try {
                                    pool.execute { serve(socket) }
                                } catch (e: java.util.concurrent.RejectedExecutionException) {
                                    sockets.remove(socket)
                                    socket.close()
                                }
                            }
                        } catch (e: Exception) {
                            if (!listener.isClosed)
                                mutableState.value = mutableState.value.copy(failure = "network")
                        }
                    },
                    "ugallery-local-receive",
                )
                .apply {
                    isDaemon = true
                    start()
                }
            return info
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun serve(socket: SSLSocket) {
        try {
            socket.soTimeout = 30_000
            socket.startHandshake()
            sockets.check()
            require(services.networkAllowed())
            val pin = tls.remotePin(socket)
            val input = DataInputStream(socket.inputStream)
            val request = PeerCodec.read(input)
            require(request.getInt("v") == 1)
            val response =
                if (request.getString("op") == "pair")
                    pair(pin, socket.inetAddress.hostAddress.orEmpty(), request)
                else {
                    val peer =
                        store.peers().singleOrNull { it.id == pin && !it.revoked }
                            ?: throw PeerFailure("revoked")
                    val secret = tls.secret(peer.id) ?: throw PeerFailure("revoked")
                    if (
                        !MessageDigest.isEqual(
                            secret.toByteArray(),
                            request.optString("token").toByteArray(),
                        )
                    )
                        throw PeerFailure("authentication")
                    authorized(pin, request, input)
                }
            PeerCodec.write(DataOutputStream(socket.outputStream), response)
        } catch (e: Exception) {
            runCatching {
                PeerCodec.write(
                    DataOutputStream(socket.outputStream),
                    JSONObject().put("error", if (e is PeerFailure) e.reason else "invalid"),
                )
            }
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    @Synchronized
    private fun pair(pin: String, host: String, j: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        if (now - attemptWindow > 60_000) {
            attemptWindow = now
            attempts = 0
        }
        if (++attempts > 60) throw PeerFailure("rate")
        val invite = invitation ?: throw PeerFailure("expired")
        if (
            now >= invite.expiresAt ||
                !MessageDigest.isEqual(
                    invite.secret.toByteArray(),
                    j.optString("secret").toByteArray(),
                )
        )
            throw PeerFailure("expired")
        val name = j.getString("name")
        require(peerName(name))
        require(peerHost(host))
        approved[pin]?.let {
            return JSONObject()
                .put("status", "paired")
                .put("token", it)
                .put("name", android.os.Build.MODEL.take(80))
        }
        if (approved.isNotEmpty()) throw PeerFailure("consumed")
        if (mutableState.value.pending.none { it.pin == pin }) {
            if (mutableState.value.pending.size >= 4) throw PeerFailure("busy")
            mutableState.value =
                mutableState.value.copy(
                    pending =
                        mutableState.value.pending + LocalSharingPairRequest(pin, name, host, now)
                )
        }
        return JSONObject().put("status", "awaitingConfirmation")
    }

    @Synchronized
    private fun approveInternal(pin: String) {
        val invite = invitation ?: throw PeerFailure("expired")
        require(System.currentTimeMillis() < invite.expiresAt)
        val request = mutableState.value.pending.single { it.pin == pin }
        val token = PeerTls.randomSecret()
        tls.saveSecret(pin, token)
        // Remote receive port is set only by an explicit endpoint update on that device's
        // invitation.
        // Reverse pairing rotates the shared credential, not an already explicitly confirmed
        // outbound address. A first contact (or revoked peer) still has no trusted receive port.
        val confirmed = store.peers().singleOrNull { it.id == pin && it.canSend && !it.revoked }
        store.savePeer(
            confirmed?.copy(name = request.name)
                ?: LocalSharingPeer(pin, request.name, request.host, invite.port, canSend = false)
        )
        approved[pin] = token
        mutableState.value = mutableState.value.copy(pending = emptyList())
    }

    private fun authorized(pin: String, j: JSONObject, input: DataInputStream): JSONObject {
        val id = j.getString("id").also(::peerUuid)
        val dir = store.directory(id)
        if (store.transfer(id) == null) {
            if (j.getString("op") != "offer") throw PeerFailure("missing")
            if (store.transfers().count { !it.terminal } >= 24) throw PeerFailure("busy")
            dir.mkdirs()
        }
        RandomAccessFile(store.lease(id), "rw").use { lease ->
            (try {
                    lease.channel.tryLock()
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    null
                })
                ?.use {
                    var t = store.transfer(id)
                    if (j.getString("op") == "offer") {
                        val manifest = PeerCodec.manifest(j.getJSONObject("manifest"))
                        if (t == null) {
                            if (dir.usableSpace < manifest.totalBytes + 16 * 1024 * 1024)
                                throw PeerFailure("space")
                            t =
                                LocalSharingTransfer(
                                    id,
                                    pin,
                                    LocalSharingDirection.Receive,
                                    status = LocalSharingStatus.AwaitingReceiveConsent,
                                    stripLocation = manifest.stripLocation,
                                    manifest = manifest,
                                )
                            store.create(t!!)
                        } else
                            require(
                                t!!.peerId == pin &&
                                    t!!.manifest == manifest &&
                                    t!!.direction == LocalSharingDirection.Receive
                            )
                        return JSONObject().put("status", t!!.status.name)
                    }
                    val task = t ?: throw PeerFailure("missing")
                    require(task.peerId == pin && task.direction == LocalSharingDirection.Receive)
                    if (j.getString("op") == "status") return status(task)
                    if (j.getString("op") == "cancel") {
                        if (
                            task.status in
                                setOf(
                                    LocalSharingStatus.Importing,
                                    LocalSharingStatus.LocalTaskCreated,
                                )
                        )
                            return status(task)
                        if (!task.terminal)
                            store.update(id) {
                                it.copy(
                                    status = LocalSharingStatus.Cancelled,
                                    cancelRequested = true,
                                )
                            }
                        return status(store.transfer(id)!!)
                    }
                    if (task.pauseRequested || task.status == LocalSharingStatus.Paused)
                        throw PeerFailure("paused")
                    if (task.cancelRequested || task.terminal) throw PeerFailure("cancelled")
                    if (task.status != LocalSharingStatus.Transferring) throw PeerFailure("consent")
                    val manifest = task.manifest!!
                    when (j.getString("op")) {
                        "chunk" -> {
                            val index = j.getInt("index")
                            require(index in manifest.entries.indices)
                            val entry = manifest.entries[index]
                            val offset = j.getLong("offset")
                            val size = j.getInt("size")
                            require(
                                size in 1..PEER_CHUNK_LIMIT &&
                                    offset >= 0 &&
                                    offset + size <= entry.bytes
                            )
                            val file = store.received(id, index)
                            require(file.length() == offset)
                            val bytes = ByteArray(size)
                            input.readFully(bytes)
                            require(
                                MessageDigest.getInstance("SHA-256").digest(bytes).peerHex() ==
                                    j.getString("sha")
                            )
                            sockets.check()
                            val current = store.transfer(id)!!
                            if (current.pauseRequested || current.cancelRequested)
                                throw PeerStopped()
                            RandomAccessFile(file, "rw").use { out ->
                                out.seek(offset)
                                out.write(bytes)
                                out.fd.sync()
                            }
                            val done =
                                manifest.entries.indices.sumOf { store.received(id, it).length() }
                            store.update(id) { it.copy(bytesDone = done) }
                            return JSONObject().put("offset", file.length())
                        }
                        "finish" -> {
                            manifest.entries.forEachIndexed { index, e ->
                                require(
                                    peerDigest(store.received(id, index), sockets::check) ==
                                        (e.bytes to e.sha256)
                                )
                            }
                            store.update(id) {
                                it.copy(
                                    status = LocalSharingStatus.ReadyToImport,
                                    bytesDone = manifest.totalBytes,
                                )
                            }
                            return JSONObject().put("status", LocalSharingStatus.ReadyToImport.name)
                        }
                        else -> throw PeerFailure("operation")
                    }
                } ?: throw PeerFailure("busy")
        }
    }

    private fun status(t: LocalSharingTransfer): JSONObject {
        val files = JSONArray()
        // Hash every actual prefix before the sender chooses an offset. Never trust persisted
        // length.
        if (t.status == LocalSharingStatus.Transferring)
            t.manifest!!.entries.forEachIndexed { i, e ->
                val f = store.received(t.id, i)
                val digest =
                    if (f.exists()) peerDigest(f, sockets::check)
                    else 0L to MessageDigest.getInstance("SHA-256").digest().peerHex()
                require(digest.first <= e.bytes)
                files.put(JSONObject().put("bytes", digest.first).put("sha", digest.second))
            }
        return JSONObject().put("status", t.status.name).put("files", files)
    }

    override fun close() {
        sockets.close()
        pool.shutdownNow()
        server = null
        invitation = null
        approved.clear()
        synchronized(activeLock) {
            if (active === this) {
                active = null
                mutableState.value = LocalSharingReceiverState()
            }
        }
    }

    companion object {
        private val activeLock = Any()
        private var active: LocalSharingReceiver? = null
        private val mutableState = MutableStateFlow(LocalSharingReceiverState())
        val state: StateFlow<LocalSharingReceiverState> = mutableState

        fun reportStartFailure() =
            synchronized(activeLock) {
                if (active == null)
                    mutableState.value = LocalSharingReceiverState(failure = "network")
            }

        fun approve(pin: String) =
            synchronized(activeLock) {
                (active ?: throw PeerFailure("inactive")).approveInternal(pin)
            }

        fun reject(pin: String) =
            synchronized(activeLock) {
                mutableState.value =
                    mutableState.value.copy(
                        pending = mutableState.value.pending.filterNot { it.pin == pin }
                    )
            }
    }
}
