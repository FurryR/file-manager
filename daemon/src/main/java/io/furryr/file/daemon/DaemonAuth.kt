package io.furryr.file.daemon

import android.util.Log
import com.google.protobuf.ByteString
import io.furryr.file.proto.AuthRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.Response
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The daemon connection handshake. Run once on a freshly connected [Transport],
 * before any request: read the daemon's nonce, sign it with its launch identity and
 * confirm the daemon accepted it. Consumes exactly the two handshake frames, so
 * the transport's normal reader can take over afterwards.
 */
internal object DaemonAuth {
    private const val TAG = "DaemonAuth"
    private const val TIMEOUT_MS = 15_000L

    suspend fun authenticate(transport: Transport, identity: DaemonIdentity) = withTimeout(TIMEOUT_MS) {
        val hello = transport.awaitResponse()
        val nonce = hello.serverHello.nonce.toByteArray()
        if (!hello.ok || !hello.hasServerHello() || nonce.size != 32) {
            throw SecurityException("daemon sent invalid authentication challenge")
        }

        val signature = identity.sign(identity.authMessage(nonce))
        val auth = Request.newBuilder()
            .setAuth(AuthRequest.newBuilder().setSignature(ByteString.copyFrom(signature)))
            .build()
        transport.send(auth.toByteArray())

        val reply = transport.awaitResponse()
        if (!reply.ok || reply.id != auth.id || reply.hasServerHello()) {
            throw SecurityException("daemon rejected authentication: ${reply.error}")
        }
        Log.i(TAG, "authenticated daemon at ${identity.socketName}")
    }

    private suspend fun Transport.awaitResponse(): Response {
        val event = events.first { it is TransportEvent.Incoming || it is TransportEvent.Closed }
        if (event is TransportEvent.Closed) {
            throw IOException("daemon closed during authentication: ${event.cause?.message}")
        }
        val frame = (event as TransportEvent.Incoming).frame
        return try {
            Response.parseFrom(frame.payload)
        } finally {
            frame.close()
        }
    }
}
