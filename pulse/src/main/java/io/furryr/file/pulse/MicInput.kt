package io.furryr.file.pulse

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * The microphone half: records at the source's fixed spec (48000 Hz, mono,
 * s16le) and pushes the PCM into the native ring for the android source. It
 * only runs while the core moves the source to RUNNING and the RECORD_AUDIO
 * permission is granted.
 */
internal class MicInput(private val service: PulseService) {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val buffer = ByteArray(4096)

    @SuppressLint("MissingPermission") // checked by the caller
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (error: Throwable) {
            Log.w("MicInput", "cannot create AudioRecord", error)
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            Log.w("MicInput", "AudioRecord not initialized")
            return false
        }
        synchronized(this) { record = r }
        running = true
        try {
            r.startRecording()
        } catch (error: Throwable) {
            Log.w("MicInput", "cannot start AudioRecord", error)
            running = false
            record = null
            r.release()
            return false
        }
        thread = Thread({ loop() }, "pulse-mic").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        val r: AudioRecord?
        val t: Thread?
        synchronized(this) {
            r = record.also { record = null }
            t = thread.also { thread = null }
        }
        // release() interrupts a blocking read.
        runCatching { r?.release() }
        runCatching { t?.join(500) }
    }

    private fun loop() {
        while (running) {
            val r = record ?: break
            val n = try {
                r.read(buffer, 0, buffer.size)
            } catch (error: Throwable) {
                if (running) Log.w("MicInput", "AudioRecord read failed", error)
                break
            }
            if (!running) break
            if (n <= 0) {
                Log.w("MicInput", "AudioRecord read error $n")
                break
            }
            val handle = service.nativeHandle()
            if (handle == 0L) continue
            service.micBuffer.position(0)
            if (n <= service.micBuffer.capacity()) {
                service.micBuffer.put(buffer, 0, n)
                service.postMicPcm(handle, n)
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48000
    }
}
