package com.nikolay.assistvoice

import android.content.Context

/**
 * Tunable parameters for the near-field voice gate, plus a live level monitor
 * the settings page reads to let the person calibrate the threshold by
 * actually speaking into the watch instead of guessing.
 *
 * Only the loudness threshold and the post-command screen hold are exposed —
 * the gate's hangover/preroll timing is fixed internally (see
 * AudioCaptureLoop's own constants) since testing found no perceptible
 * benefit to making them user-adjustable.
 *
 * Lives in its own SharedPreferences file, separate from TargetAppPrefs, so
 * VoiceAccessibilityService's listener here never fires on an ordinary slot
 * edit and vice versa.
 */
object VadSettings {

    const val PREFS_NAME = "vad_settings"

    private const val KEY_THRESHOLD = "threshold_rms"
    private const val KEY_SCREEN_HOLD_SECONDS = "screen_hold_seconds"

    /**
     * RMS level (0..32767 scale of a 16-bit sample) a 20 ms frame must reach
     * for the gate to open.
     *
     * This is an ABSOLUTE threshold on purpose, not one that adapts to the
     * ambient noise floor. The whole premise is that a command is spoken with
     * the watch held right at the mouth, which is enormously louder than any
     * background sound — an adaptive threshold would quietly re-normalise that
     * advantage away and start opening the gate for room noise whenever the
     * room got quiet.
     *
     * The default is a starting point, not a tuned value: open the VAD page,
     * speak a command, and set it between your own speech peak and the
     * background level you see there.
     */
    const val DEFAULT_THRESHOLD_RMS = 1200
    const val MIN_THRESHOLD_RMS = 100
    const val MAX_THRESHOLD_RMS = 8000

    /**
     * How long to hold the screen on (bright, no dim/timeout) after a command
     * fires, so the target app has time to actually appear before the watch's
     * own screen timeout can kick back in mid-launch. 0 disables the hold
     * entirely — the screen behaves exactly as it did before this setting
     * existed. See VoiceAccessibilityService.onCommandDetected(), which
     * acquires a SCREEN_BRIGHT_WAKE_LOCK for this many seconds.
     */
    const val DEFAULT_SCREEN_HOLD_SECONDS = 15
    const val MIN_SCREEN_HOLD_SECONDS = 0
    const val MAX_SCREEN_HOLD_SECONDS = 60

    data class Params(
        val thresholdRms: Int,
        val screenHoldSeconds: Int
    )

    fun load(context: Context): Params {
        val prefs = prefs(context)
        return Params(
            thresholdRms = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_RMS)
                .coerceIn(MIN_THRESHOLD_RMS, MAX_THRESHOLD_RMS),
            screenHoldSeconds = prefs.getInt(KEY_SCREEN_HOLD_SECONDS, DEFAULT_SCREEN_HOLD_SECONDS)
                .coerceIn(MIN_SCREEN_HOLD_SECONDS, MAX_SCREEN_HOLD_SECONDS)
        )
    }

    fun saveThreshold(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD_RMS, MAX_THRESHOLD_RMS))
            .apply()
    }

    fun saveScreenHoldSeconds(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(
                KEY_SCREEN_HOLD_SECONDS,
                value.coerceIn(MIN_SCREEN_HOLD_SECONDS, MAX_SCREEN_HOLD_SECONDS)
            )
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Live state published by the capture loop for the VAD settings page.
 *
 * Plain volatile fields rather than broadcasts or a bound service: the
 * settings Activity and VoiceAccessibilityService run in the same process, so
 * this is both the cheapest and the least fragile way to get a number across.
 * If the service isn't running there's simply nothing writing here, which the
 * page reports as such.
 */
object VadMonitor {

    /** RMS of the most recent 20 ms frame. */
    @Volatile
    var currentRms: Int = 0

    /** Highest RMS seen since the last reset — the number to calibrate against. */
    @Volatile
    var peakRms: Int = 0

    /** True while the capture loop is actually reading from the microphone. */
    @Volatile
    var capturing: Boolean = false

    /** True while the gate is open and audio is reaching the decoder. */
    @Volatile
    var gateOpen: Boolean = false

    fun report(rms: Int, gateOpen: Boolean) {
        currentRms = rms
        if (rms > peakRms) peakRms = rms
        this.gateOpen = gateOpen
    }

    fun resetPeak() {
        peakRms = 0
    }

    fun onCaptureStopped() {
        capturing = false
        gateOpen = false
        currentRms = 0
    }
}
