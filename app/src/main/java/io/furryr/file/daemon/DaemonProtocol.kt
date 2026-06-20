package io.furryr.file.daemon

import android.net.LocalSocket
import com.google.protobuf.MessageLite
import io.furryr.file.proto.Response
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.InputStream

data class DaemonResponse(val response: Response, val attachedFd: FileDescriptor? = null)

object DaemonProtocol {
    fun writeRequest(output: DataOutputStream, request: MessageLite) {
        val payload = request.toByteArray()
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    /** Read a framed response + optional ancillary fd from the socket. */
    fun readResponse(socket: LocalSocket): DaemonResponse {
        val input = socket.inputStream
        val sizeBytes = ByteArray(4)
        readFully(input, sizeBytes)
        val size = ((sizeBytes[0].toInt() and 0xFF) shl 24) or
                ((sizeBytes[1].toInt() and 0xFF) shl 16) or
                ((sizeBytes[2].toInt() and 0xFF) shl 8) or
                (sizeBytes[3].toInt() and 0xFF)

        val payload = ByteArray(size)
        readFully(input, payload)

        val fd = socket.ancillaryFileDescriptors?.firstOrNull()

        val response = Response.parseFrom(payload)
        if (!response.ok) {
            throw PermissionDeniedException(response.error.ifBlank { "daemon returned error" })
        }
        return DaemonResponse(response, fd)
    }

    /** Read a raw response frame without checking ok — for streaming. */
    fun readResponseRaw(input: DataInputStream): Response? {
        val size = runCatching { input.readInt() }.getOrNull() ?: return null
        val payload = ByteArray(size)
        input.readFully(payload)
        return Response.parseFrom(payload)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw EOFException("unexpected EOF reading daemon response")
            offset += count
        }
    }
}
