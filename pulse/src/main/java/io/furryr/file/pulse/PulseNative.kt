package io.furryr.file.pulse

import java.nio.ByteBuffer

/** Embedded PulseAudio server; loaded only in the resident `:pulse` process. */
internal object PulseNative {
    init {
        System.loadLibrary("pulsehost")
    }

    /**
     * Create the core without a listener. Kotlin supplies accepted clients through [addClients].
     * The callback exposes onSinkData(I)V, onSinkIdle()V and onMicState(Z)V.
     * Playback is 48 kHz stereo float32; capture is 48 kHz mono s16le.
     */
    external fun create(
        callback: Any,
        sinkBuffer: ByteBuffer,
        micBuffer: ByteBuffer,
        micEnabled: Boolean,
        bufferMs: Int,
    ): Long

    /** Duplicate the batch into the PA main-loop queue; Kotlin retains ownership of every fd. */
    external fun addClients(handle: Long, clients: IntArray): Boolean

    external fun postMicPcm(handle: Long, length: Int): Int
    external fun destroy(handle: Long)
    external fun error(): String?
}
