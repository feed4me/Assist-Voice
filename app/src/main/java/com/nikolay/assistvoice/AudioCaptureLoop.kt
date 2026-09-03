package com.nikolay.assistvoice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Microphone capture with a near-field voice gate, replacing vosk-android's
 * SpeechService.
 *
 * SpeechService owns its AudioRecord privately and hands frames straight to
 * the recognizer, which makes three things impossible that this app needs:
 * gating audio before it reaches the decoder, replaying pre-roll so the start
 * of a word isn't clipped, and running the decode at a different thread
 * priority than the capture. So capture is done here instead.
 *
 * ## Why gating is the main performance lever
 *
 * Decoder cost is (cost per frame) x (number of frames). Shrinking the
 * grammar's vocabulary only ever attacks the first factor, and barely: a
 * grammar with no language model behind it is a flat word loop whose beam
 * stays wide no matter how the vocabulary is sized, while the acoustic
 * network's forward pass costs the same regardless. Gating attacks the second
 * factor instead, and it is the far larger one: the decoder is fed only while
 * a loud, close-range voice is present, so it sits idle essentially all the
 * time the watch is simply awake. That is what stops other apps stuttering.
 *
 * ## Threads
 *
 * Reader thread: AudioRecord.read plus an RMS per 20 ms frame. It does almost
 * nothing but must never fall behind the recorder, so it runs at audio
 * priority.
 *
 * Decoder thread: everything Vosk. This is the CPU hog, so it runs at
 * THREAD_PRIORITY_BACKGROUND, which places it in the background scheduling
 * group — it gets a small share of the CPU when anything in the foreground
 * wants it. Recognition latency is unaffected in practice (there is nothing
 * else to decode), while a foreground app competing for the CPU wins.
 *
 * Frames move between them through a bounded queue of pooled buffers, so a
 * steady-state segment allocates nothing.
 */
class AudioCaptureLoop(
    private val sampleRate: Int,
    private val params: () -> VadSettings.Params,
    private val sink: Sink
) {

    /**
     * Callbacks are delivered on the decoder thread, never the main thread —
     * implementations must not touch UI directly and must not assume main-
     * thread confinement of anything they read.
     */
    interface Sink {
        /** A gated frame of audio. `data` is only valid for the duration of the call. */
        fun onAudioFrame(data: ShortArray, length: Int)

        /** The gate closed: the utterance is over. */
        fun onSegmentEnd()

        /** Capture died (mic taken away, read error). Delivered once, then the loop stops. */
        fun onCaptureError(error: Exception)
    }

    companion object {
        private const val TAG = "AudioCaptureLoop"

        /** 20 ms at 16 kHz. Short enough for responsive gating, long enough for a stable RMS. */
        private const val FRAME_MS = 20

        /**
         * Hard ceiling on a single gated segment. Without it, a sustained loud
         * noise (wind on the mic, a nearby machine) could hold the gate open
         * indefinitely and put us right back to decoding continuously — the
         * exact failure this design exists to avoid.
         */
        private const val MAX_SEGMENT_MS = 6000

        /** ~3 s of gated audio in flight before we start dropping frames. */
        private const val QUEUE_CAPACITY = 150

        private val END_OF_SEGMENT = Any()
    }

    private val frameSamples = sampleRate * FRAME_MS / 1000

    private val queue = ArrayBlockingQueue<Any>(QUEUE_CAPACITY)

    /** Recycled frame buffers, so a long utterance doesn't churn the heap. */
    private val pool = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY + 8)

    @Volatile
    private var running = false

    private var recorder: AudioRecord? = null
    private var readerThread: Thread? = null
    private var decoderThread: Thread? = null

    @Volatile
    private var errorReported = false

    /**
     * Starts capture. Returns false if the recorder could not be created or
     * started; in that case nothing was left running and no callback fires.
     */
    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBuffer")
            return false
        }

        val bufferSize = maxOf(minBuffer * 2, frameSamples * 8 * 2)

        val record = try {
            AudioRecord(
                // VOICE_RECOGNITION is specified to come without automatic gain
                // control or noise suppression, which matters here beyond the
                // usual reasons: AGC would pull quiet background audio up
                // towards the level of close-range speech and erase the very
                // difference the gate relies on.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            record.release()
            return false
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "Recorder did not enter RECORDING state")
            try {
                record.stop()
            } catch (e: Exception) {
                // Nothing to do — we're already failing the start.
            }
            record.release()
            return false
        }

        recorder = record
        running = true
        errorReported = false
        queue.clear()
        pool.clear()

        decoderThread = Thread({ decodeLoop() }, "AssistVoice-decode").apply {
            isDaemon = true
            start()
        }
        readerThread = Thread({ readLoop(record) }, "AssistVoice-capture").apply {
            isDaemon = true
            start()
        }

        VadMonitor.capturing = true
        return true
    }

    /**
     * Stops capture and waits for both threads to finish.
     *
     * The join is not optional politeness: the caller owns the Vosk Recognizer
     * that the decoder thread is calling into, and closing it while that
     * thread is still inside acceptWaveForm would be a use-after-free on a
     * native pointer.
     */
    fun stop() {
        if (!running && readerThread == null && decoderThread == null) return
        running = false

        readerThread?.let { joinQuietly(it) }
        readerThread = null

        // Unblock the decoder's queue.take() so it can observe running == false.
        queue.offer(END_OF_SEGMENT)
        decoderThread?.let { joinQuietly(it) }
        decoderThread = null

        recorder?.let { record ->
            try {
                record.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Recorder stop failed", e)
            }
            try {
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Recorder release failed", e)
            }
        }
        recorder = null

        queue.clear()
        pool.clear()
        VadMonitor.onCaptureStopped()
    }

    private fun joinQuietly(thread: Thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            // Should not happen — both loops check `running` every frame. Worth
            // a log rather than silence, since it would mean the recognizer is
            // still potentially in use by the time the caller frees it.
            Log.e(TAG, "Thread ${thread.name} did not stop in time")
        }
    }

    // ------------------------------------------------------------------
    // Reader thread: capture, level metering, gating
    // ------------------------------------------------------------------

    private fun readLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Pre-roll ring: whole frames, written in place, so nothing is
        // allocated while the gate is closed (which is nearly always).
        var prerollFrames = prerollFrameCount(params().prerollMs)
        var ring = Array(maxOf(prerollFrames, 1)) { ShortArray(frameSamples) }
        var ringHead = 0
        var ringCount = 0

        var gateOpen = false
        var lastLoudAt = 0L
        var segmentStartedAt = 0L

        try {
            while (running) {
                val current = params()
                val wantedPreroll = prerollFrameCount(current.prerollMs)
                if (wantedPreroll != prerollFrames) {
                    prerollFrames = wantedPreroll
                    ring = Array(maxOf(prerollFrames, 1)) { ShortArray(frameSamples) }
                    ringHead = 0
                    ringCount = 0
                }

                val target = if (gateOpen) obtainBuffer() else ring[ringHead]
                val read = record.read(target, 0, frameSamples)

                if (read < 0) {
                    if (gateOpen) recycle(target)
                    throw IllegalStateException("AudioRecord.read returned $read")
                }
                if (read == 0) {
                    if (gateOpen) recycle(target)
                    continue
                }

                val rms = rms(target, read)
                val loud = rms >= current.thresholdRms
                val now = System.currentTimeMillis()

                if (!gateOpen) {
                    VadMonitor.report(rms, false)
                    if (loud) {
                        gateOpen = true
                        lastLoudAt = now
                        segmentStartedAt = now
                        // Replay the pre-roll oldest-first, then the frame that
                        // opened the gate. Copies into pooled buffers because
                        // the ring's own arrays get overwritten immediately.
                        flushPreroll(ring, ringHead, ringCount, prerollFrames)
                        ringCount = 0
                        ringHead = 0
                        enqueueCopy(target, read)
                    } else {
                        if (prerollFrames > 0) {
                            ringHead = (ringHead + 1) % prerollFrames
                            if (ringCount < prerollFrames) ringCount++
                        }
                    }
                } else {
                    VadMonitor.report(rms, true)
                    enqueue(target, read)
                    if (loud) lastLoudAt = now

                    val quietFor = now - lastLoudAt
                    val segmentFor = now - segmentStartedAt
                    if (quietFor >= current.hangoverMs || segmentFor >= MAX_SEGMENT_MS) {
                        if (segmentFor >= MAX_SEGMENT_MS) {
                            Log.i(TAG, "Segment hit the ${MAX_SEGMENT_MS}ms ceiling — closing gate")
                        }
                        gateOpen = false
                        ringCount = 0
                        ringHead = 0
                        queue.offer(END_OF_SEGMENT)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) reportError(e)
        } finally {
            VadMonitor.gateOpen = false
        }
    }

    private fun prerollFrameCount(prerollMs: Int): Int = prerollMs / FRAME_MS

    private fun flushPreroll(
        ring: Array<ShortArray>,
        head: Int,
        count: Int,
        capacity: Int
    ) {
        if (count <= 0 || capacity <= 0) return
        // head points at the slot just written (the frame that opened the
        // gate), so the oldest retained frame sits `count` slots behind it.
        var index = (head - count + capacity) % capacity
        for (i in 0 until count) {
            enqueueCopy(ring[index], frameSamples)
            index = (index + 1) % capacity
        }
    }

    private fun enqueueCopy(source: ShortArray, length: Int) {
        val buffer = obtainBuffer()
        System.arraycopy(source, 0, buffer, 0, length)
        enqueue(buffer, length)
    }

    /** Hands a pooled buffer to the decoder. Takes ownership of `buffer`. */
    private fun enqueue(buffer: ShortArray, length: Int) {
        if (length < frameSamples) {
            // Short read — pad rather than track per-frame lengths. Twenty
            // milliseconds of trailing zeros is inaudible to the decoder.
            java.util.Arrays.fill(buffer, length, frameSamples, 0.toShort())
        }
        if (!queue.offer(buffer)) {
            // The decoder is behind. Dropping the newest frame is better than
            // blocking the reader, which would overrun the recorder.
            Log.e(TAG, "Decode queue full — dropping a frame")
            recycle(buffer)
        }
    }

    private fun obtainBuffer(): ShortArray = pool.poll() ?: ShortArray(frameSamples)

    private fun recycle(buffer: ShortArray) {
        pool.offer(buffer)
    }

    private fun rms(data: ShortArray, length: Int): Int {
        if (length <= 0) return 0
        var sum = 0L
        for (i in 0 until length) {
            val sample = data[i].toLong()
            sum += sample * sample
        }
        return Math.sqrt(sum.toDouble() / length).toInt()
    }

    // ------------------------------------------------------------------
    // Decoder thread
    // ------------------------------------------------------------------

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (item === END_OF_SEGMENT) {
                    if (running) sink.onSegmentEnd()
                    continue
                }
                val buffer = item as ShortArray
                try {
                    if (running) sink.onAudioFrame(buffer, frameSamples)
                } finally {
                    recycle(buffer)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (running) reportError(e)
        }
    }

    private fun reportError(error: Exception) {
        if (errorReported) return
        errorReported = true
        running = false
        Log.e(TAG, "Capture failed", error)
        try {
            sink.onCaptureError(error)
        } catch (e: Exception) {
            Log.e(TAG, "Error callback threw", e)
        }
    }
}
