package io.furryr.file.core.model

/** Buffered result of a one-shot (non-interactive) command execution. */
data class ExecResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    val stdoutText: String get() = String(stdout, Charsets.UTF_8)
    val stderrText: String get() = String(stderr, Charsets.UTF_8)
}
