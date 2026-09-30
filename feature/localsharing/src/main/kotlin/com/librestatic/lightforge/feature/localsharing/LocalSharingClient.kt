package com.librestatic.lightforge.feature.localsharing

import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import org.json.JSONObject

internal class PeerSockets : Closeable {
    @Volatile private var closed = false
    private val active = ConcurrentHashMap.newKeySet<Closeable>()

    fun add(socket: Closeable) {
        active.add(socket)
        if (closed) {
            socket.close()
            throw PeerStopped()
        }
    }

    fun remove(socket: Closeable) {
        active.remove(socket)
    }

    fun check() {
        if (closed) throw PeerStopped()
    }

    override fun close() {
        closed = true
        active.toList().forEach { runCatching { it.close() } }
        active.clear()
    }
}

internal class PeerClient(context: Context, private val sockets: PeerSockets) {
    private val tls = PeerTls(context)

    fun request(
        host: String,
        port: Int,
        pin: String,
        request: JSONObject,
        payload: ByteArray? = null,
    ): JSONObject {
        require(peerHost(host) && port in 1024..65535 && peerHash(pin))
        sockets.check()
        val socket = tls.context(pin).socketFactory.createSocket() as SSLSocket
        sockets.add(socket)
        try {
            socket.soTimeout =
                if (request.optString("op") in setOf("status", "finish")) 30 * 60_000 else 30_000
            socket.enabledProtocols =
                socket.supportedProtocols
                    .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                    .toTypedArray()
            socket.connect(InetSocketAddress(host, port), 15_000)
            socket.startHandshake()
            sockets.check()
            val output = DataOutputStream(socket.outputStream)
            PeerCodec.write(output, request.put("v", 1))
            if (payload != null) {
                require(payload.size <= PEER_CHUNK_LIMIT)
                output.write(payload)
                output.flush()
            }
            val result = PeerCodec.read(DataInputStream(socket.inputStream))
            if (result.optString("error").isNotEmpty()) throw PeerFailure(result.getString("error"))
            return result
        } finally {
            sockets.remove(socket)
            socket.close()
        }
    }

    fun authorized(
        peer: LocalSharingPeer,
        request: JSONObject,
        payload: ByteArray? = null,
    ): JSONObject {
        if (peer.revoked) throw PeerFailure("revoked")
        val secret = tls.secret(peer.id) ?: throw PeerFailure("revoked")
        return request(peer.host, peer.port, peer.id, request.put("token", secret), payload)
    }
}
