package com.librestatic.lightforge.feature.localsharing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic never sends a pairing secret or application request; preserves the server cause. */
@RunWith(AndroidJUnit4::class)
class LocalSharingTlsDiagnosticDeviceTest {
    @Test
    fun mutualTlsIdentityHandshakeReportsBothEndpoints() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val clientContext = PeerFixtureContext(base, UUID.randomUUID().toString())
        val serverContext = PeerFixtureContext(base, UUID.randomUUID().toString())
        val pool = Executors.newSingleThreadExecutor()
        var listener: SSLServerSocket? = null
        try {
            val serverTls = PeerTls(serverContext)
            val clientTls = PeerTls(clientContext)
            val server =
                serverTls.context().serverSocketFactory.createServerSocket(0) as SSLServerSocket
            listener = server
            server.needClientAuth = true
            server.soTimeout = 15000
            server.enabledProtocols =
                server.supportedProtocols
                    .filter { it == "TLSv1.2" || it == "TLSv1.3" }
                    .toTypedArray()
            val accepted =
                pool.submit<Throwable?> {
                    try {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 15000
                            socket.startHandshake()
                            assertEquals(clientTls.pin, serverTls.remotePin(socket))
                            assertEquals(71, socket.inputStream.read())
                            socket.outputStream.write(82)
                            socket.outputStream.flush()
                        }
                        null
                    } catch (error: Throwable) {
                        error
                    }
                }
            var clientFailure: Throwable? = null
            try {
                (clientTls
                        .context(serverTls.pin)
                        .socketFactory
                        .createSocket("127.0.0.1", server.localPort) as SSLSocket)
                    .use { socket ->
                        socket.soTimeout = 15000
                        socket.enabledProtocols =
                            socket.supportedProtocols
                                .filter { it == "TLSv1.2" || it == "TLSv1.3" }
                                .toTypedArray()
                        socket.startHandshake()
                        assertEquals(serverTls.pin, clientTls.remotePin(socket))
                        socket.outputStream.write(71)
                        socket.outputStream.flush()
                        assertEquals(82, socket.inputStream.read())
                    }
            } catch (error: Throwable) {
                clientFailure = error
            }
            val serverFailure = accepted.get(20, TimeUnit.SECONDS)
            if (serverFailure != null) {
                if (clientFailure != null) serverFailure.addSuppressed(clientFailure)
                throw serverFailure
            }
            clientFailure?.let { throw it }
        } finally {
            listener?.close()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            clientContext.cleanup()
            serverContext.cleanup()
        }
    }
}
