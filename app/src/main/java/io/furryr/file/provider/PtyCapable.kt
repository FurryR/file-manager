package io.furryr.file.provider

import java.io.FileDescriptor

/**
 * Optional interface for providers that support PTY (pseudo-terminal) creation.
 * Only the native daemon provider implements this.
 */
interface PtyCapable {
    suspend fun spawnPty(
        command: String = "/system/bin/sh",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        termType: String = "xterm-256color",
        rows: Int = 24,
        cols: Int = 80,
        cwd: String? = null,
        useRoot: Boolean = false,
    ): Result<PtyConnection>

    suspend fun resizePty(ptyId: Long, rows: Int, cols: Int): Result<Unit>
    suspend fun closePty(ptyId: Long): Result<Unit>
}

data class PtyConnection(
    val ptyId: Long,
    val fileDescriptor: FileDescriptor,
    val inputStream: java.io.InputStream,
    val outputStream: java.io.OutputStream,
)
