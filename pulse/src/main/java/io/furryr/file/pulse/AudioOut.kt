package io.furryr.file.pulse

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer

/**
 * The single Android-side playback device. The PA sink runs at a fixed spec
 * (48000 Hz, stereo, float32) and the core mixes and resamples every stream
 * into it, so this class only ever writes that one format.
 *
 * [write] is called from the PA sink render thread; the blocking AudioTrack
 * write provides back-pressure. The native sample clock renders full blocks
 * without adding another block-duration sleep after a blocking write.
 * Prime the track before playback so scheduler jitter can use real queued
 * audio, rather than an allocated but permanently empty latency buffer.
 */
internal class AudioOut(private val maxLatencyBytes: Int) {
    private var track: AudioTrack? = null
    private var blocks = 0
    private var queuedBytes = 0
    private var underruns = 0

    @Synchronized
    fun write(buffer: ByteBuffer, length: Int) {
        val t = track ?: createTrack()?.also { track = it } ?: return
        if (t.underrunCount != underruns) {
            underruns = t.underrunCount
            // This also re-primes after a long idle period drained the track.
            t.pause()
            t.flush()
            queuedBytes = 0
        }
        if (blocks++ == 0) {
            Log.i(TAG, "first PCM block: $length bytes @ ${t.audioSessionId}")
        }
        buffer.position(0)
        buffer.limit(length)
        var written = 0
        while (written < length) {
            val n = try {
                val mode = if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    AudioTrack.WRITE_BLOCKING
                } else {
                    AudioTrack.WRITE_NON_BLOCKING
                }
                t.write(buffer, length - written, mode)
            } catch (error: Throwable) {
                Log.w(TAG, "AudioTrack write failed", error)
                return
            }
            if (n == 0 && t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                // Capacity can be smaller than a whole render block. Start
                // before blocking, rather than deadlocking a paused track.
                runCatching { t.play() }
                    .onFailure { Log.w(TAG, "AudioTrack prime failed", it) }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return
                continue
            }
            if (n <= 0) {
                Log.w(TAG, "AudioTrack write error $n")
                return
            }
            written += n
        }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            queuedBytes += written
            val primeBytes = minOf(maxLatencyBytes, t.bufferSizeInFrames * BYTES_PER_FRAME)
            if (queuedBytes >= primeBytes) {
                runCatching { t.play() }
                    .onFailure { Log.w(TAG, "AudioTrack play failed", it) }
            }
        }
    }

    @Synchronized
    fun finish() {
        // A short clip can end before it reaches the normal priming threshold.
        val t = track ?: return
        if (queuedBytes > 0 && t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { t.play() }
                .onFailure { Log.w(TAG, "AudioTrack drain failed", it) }
        }
    }

    @Synchronized
    fun release() {
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
        queuedBytes = 0
        underruns = 0
    }

    private fun createTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) {
            Log.w("AudioOut", "AudioTrack float output unavailable")
            return null
        }
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, maxLatencyBytes + minBuffer))
                .build()
        } catch (error: Throwable) {
            Log.w(TAG, "cannot create AudioTrack", error)
            null
        }
    }

    private companion object {
        const val TAG = "AudioOut"
        const val SAMPLE_RATE = 48000
        const val BYTES_PER_FRAME = 8
    }
}
