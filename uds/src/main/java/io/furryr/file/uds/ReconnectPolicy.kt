package io.furryr.file.uds

/**
 * How a client [Transport] re-attaches after a transient drop. Absent means a
 * dropped connection is final.
 */
data class ReconnectPolicy(
    val attempts: Int = 50,
    val delayMillis: Long = 100L,
)
