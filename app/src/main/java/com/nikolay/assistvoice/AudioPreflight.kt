package com.nikolay.assistvoice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Cheap "can we actually record?" probe, run before handing the microphone to
 * Vosk.
 *
 * Two failure modes it catches:
 *
 *  1. The recorder can't be created / started at all (mic held by another app,
 *     permission missing).
 *
 *  2. The recorder *works* but every sample is zero. That's what a background
 *     process gets on modern Android and on Huawei's audio policy: appops has
 *     RECORD_AUDIO in MODE_IGNORED and silently substitutes silence, with no
 *     error anywhere.
 *
 * This also avoids a hard process kill: vosk-android's RecognizerThread does
 * `if (nread < 0) throw new RuntimeException(...)` on its own thread, and an
 * uncaught exception there takes the whole process down. Probing first means
 * that thread is never started against a mic already known to be broken.
 */
object AudioPreflight {

    private const val TAG = "AudioPreflight"

    enum class Result {
        /** Real, non-silent audio is flowing. */
        OK,

        /** Recorder works but returns pure silence — appops is muting us. */
        SILENT,

        /** Recorder could not be created or started. */
        UNAVAILABLE
    }

    /**
     * Reads a handful of small buffers and reports what came back. Costs on the
     * order of 60–100 ms, so callers should cache a successful result and only
     * re-probe after a failure rather than on every screen wake.
     */
    @Suppress("MissingPermission")
    fun probe(sampleRate: Int): Result {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return Result.UNAVAILABLE

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return Result.UNAVAILABLE
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return Result.UNAVAILABLE
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return Result.UNAVAILABLE
            }
            val buffer = ShortArray(minBuffer)
            var sawSignal = false
            for (attempt in 0 until 6) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) return Result.UNAVAILABLE
                for (i in 0 until read) {
                    if (buffer[i].toInt() != 0) {
                        sawSignal = true
                        break
                    }
                }
                if (sawSignal) break
            }
            return if (sawSignal) Result.OK else Result.SILENT
        } catch (e: Exception) {
            Log.e(TAG, "Probe failed", e)
            return Result.UNAVAILABLE
        } finally {
            try {
                recorder.stop()
            } catch (e: Exception) {
                // already stopped / never started
            }
            recorder.release()
        }
    }
}
