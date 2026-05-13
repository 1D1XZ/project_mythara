package com.mythara.secret.observe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone capture for Observe mode. 16 kHz mono 16-bit PCM — the
 * format Vosk expects. Source is `VOICE_RECOGNITION`, which on Pixels
 * routes through the platform's far-field/noise-reduction pipeline
 * (better signal for ASR than raw `MIC`).
 *
 * Single-shot: construct → [start] → [read] in a loop → [stop] →
 * [release]. The class isn't thread-safe; one consumer per instance.
 *
 * RECORD_AUDIO must already be granted; we throw a clear error if it
 * isn't (the foreground service shouldn't have launched in that case).
 */
class AudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
) {
    private var recorder: AudioRecord? = null
    private var bufferSize: Int = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed (state=${r.state})")
            r.release()
            return false
        }
        recorder = r
        r.startRecording()
        Log.d(TAG, "AudioRecord started; bufferSize=$bufferSize")
        return true
    }

    /** Read up to [out].size samples. Returns the count actually read, or <0 on error. */
    fun read(out: ShortArray): Int {
        val r = recorder ?: return -1
        return r.read(out, 0, out.size)
    }

    fun stop() {
        val r = recorder ?: return
        runCatching {
            if (r.state == AudioRecord.STATE_INITIALIZED &&
                r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                r.stop()
            }
        }
    }

    fun release() {
        recorder?.release()
        recorder = null
    }

    /** Frame size in samples — about 100ms at 16kHz. Tuned for Vosk throughput. */
    val readFrameSamples: Int get() = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1024)

    companion object {
        private const val TAG = "Mythara/Observe"
        const val SAMPLE_RATE = 16_000
        const val MIN_BUFFER_BYTES = 4096
        const val FRAMES_PER_SECOND = 10
    }
}
