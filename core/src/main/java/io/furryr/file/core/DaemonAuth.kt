package io.furryr.file.core

import android.util.Log
import com.google.protobuf.ByteString
import io.furryr.file.proto.AuthRequest
import io.furryr.file.proto.Request
import io.furryr.file.proto.Response
import io.furryr.file.uds.Frame
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The daemon connection handshake. Run once on a freshly connected [Transport],
 * before any request: read the daemon's nonce, sign it with the app identity and
 * confirm the daemon accepted it. Consumes exactly the two handshake frames, so
 * the transport's normal reader can take over afterwards.
 */
internal object DaemonAuth {
    private const val TAG = "DaemonAuth"
    private const val TIMEOUT_MS = 15_000L

    suspend fun authenticate(transport: Transport, socketName: String) {
        val hello = withTimeout(TIMEOUT_MS) { transport.awaitResponse() }
        val nonce = hello.response.serverHello.nonce.toByteArray()
        hello.frame.close()
        if (nonce.isEmpty()) {
            throw SecurityException("daemon sent no authentication challenge")
        }

        val signature = DaemonIdentity.sign(DaemonIdentity.authMessage(socketName, nonce))
        val auth = Request.newBuilder()
            .setAuth(AuthRequest.newBuilder().setSignature(ByteString.copyFrom(signature)))
            .build()
        transport.send(auth.toByteArray())

        val reply = withTimeout(TIMEOUT_MS) { transport.awaitResponse() }
        reply.frame.close()
        if (!reply.response.ok) {
            throw SecurityException("daemon rejected authentication: ${reply.response.error}")
        }
        Log.i(TAG, "authenticated daemon at $socketName")
    }

    private suspend fun Transport.awaitResponse(): InboundResponse {
        val event = events.first { it is TransportEvent.Incoming || it is TransportEvent.Closed }
        if (event is TransportEvent.Closed) {
            throw IOException("daemon closed during authentication: ${event.cause?.message}")
        }
        val frame = (event as TransportEvent.Incoming).frame
        return InboundResponse(frame, Response.parseFrom(frame.payload))
    }

    private class InboundResponse(val frame: Frame, val response: Response)
}
