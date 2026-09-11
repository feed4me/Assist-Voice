package com.nikolay.assistvoice

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * AccessibilityService hosting the background voice-trigger pipeline.
 *
 * Offline Vosk recognition, listening only while the watch screen is on,
 * matching two-word commands («открой <слово>», «позвони <имя>») and performing
 * each matched slot's action. AccessibilityServices resist OEM battery-manager
 * kills better than a plain foreground service.
 *
 * Enabling the service requires the person to turn it on manually in system
 * Settings → Accessibility — Android does not let an app enable its own.
 *
 * The service doesn't consume accessibility events; onAccessibilityEvent is a
 * no-op and accessibility_service_config.xml requests the narrowest feed
 * available. Microphone access is not granted by being an AccessibilityService
 * either — RECORD_AUDIO is while-in-use, so MicForegroundService is started
 * alongside capture to put the process in a foreground state with the
 * `microphone` service type.
 *
 * ## Recognition design
 *
 * Two mechanisms carry the load, and they attack different things:
 *
 * 1. **Two-word phrase grammar** (see VoicePhrases) fixes false triggers. A
 *    grammar recognizer must map any sound onto its nearest entry; requiring
 *    two specific words in sequence makes that a much harder accident.
 * 2. **A near-field VAD gate** (see AudioCaptureLoop) fixes the CPU cost. The
 *    decoder is fed only during loud close-range speech, so it idles almost
 *    all the time instead of decoding continuously while the screen is on.
 *
 * A grammar carries no language model, so padding it with a large general
 * vocabulary instead of gating audio does not reduce CPU cost: it's a flat
 * word loop whose search beam stays wide however the vocabulary is sized,
 * while the acoustic forward pass costs the same either way. Vocabulary size
 * is not the lever; frames-fed-to-the-decoder is.
 *
 * ## The screen "flashlight"
 *
 * "включи фонарик" / "выключи фонарик" (VoicePhrases.FLASHLIGHT_PHRASES) are
 * two more reserved two-word phrases folded into the same grammar, but
 * outside the slot mechanism entirely: not configurable, not shown in any
 * settings screen, always present regardless of what slots exist. See
 * ensureRecognizer() for how they're added to the grammar and
 * handleHypothesis() for how they're matched ahead of, and independently of,
 * user slots; FlashlightController and FlashlightActivity for what they do.
 */
class VoiceAccessibilityService : AccessibilityService(), AudioCaptureLoop.Sink {

    companion object {
        private const val TAG = "VoiceAccessibilityService"

        // Vosk's small Russian model is trained at 16 kHz. This is not a knob:
        // feeding it 8 kHz to "save battery" produces garbage, not savings.
        private const val SAMPLE_RATE = 16000.0f
        private const val SAMPLE_RATE_INT = 16000

        // Delay before restarting recognition after a plain match (covers the
        // tail of the same utterance + any echo).
        private const val POST_MATCH_RESTART_DELAY_MS = 2500L

        // Longer fallback delay used specifically after a CALL action — the
        // watch's audio stack can keep silencing this app's mic for a while
        // after a call, even once the call itself has ended. The call-state
        // listener below is the primary restart trigger; this is a backstop.
        private const val POST_CALL_RESTART_DELAY_MS = 5000L

        // Backstop in case MicForegroundService never reports ready (e.g. the
        // ROM refused the foreground start). We try to capture anyway.
        private const val FOREGROUND_WAIT_FALLBACK_MS = 700L

        private val RETRY_DELAYS_MS = longArrayOf(300L, 800L, 2000L, 4000L, 8000L)

        // Once RETRY_DELAYS_MS's fast burst is exhausted, scheduleRetry()
        // keeps trying forever at this steady interval instead of giving up
        // — see its doc comment for why "give up permanently" was the bug.
        private const val SUSTAINED_RETRY_DELAY_MS = 15000L

        private const val WAKE_STARTUP_LOCK_MS = 3000L

        /**
         * Keep the indicator overlay window alive while the screen is off,
         * instead of tearing it down and rebuilding it on each listen cycle.
         *
         * A window that already exists at the moment the MCU lights the
         * display may get the application processor involved, where a window
         * created *after* the wake cannot — because on a static watch face our
         * code never runs to create it.
         *
         * Known side effect either way: the ROM screenshots the live wallpaper
         * at screen-off, so a visible icon will be baked into that screenshot
         * and appear (frozen, wrong colour) on the static face at next wake.
         *
         * Flip to false to restore the previous show-while-listening behaviour.
         */
        private const val KEEP_OVERLAY_WHILE_ASLEEP = true

        /**
         * Packages that count as "the watch face is showing".
         *
         * Read off this device from logcat — both the static and the animated
         * capture show `HybridService: onStartedWakingUp frontComponentName:
         * ComponentInfo{com.huawei.watch.home/...HomeMainActivity}` and
         * `ScenarioService: front pkg : com.huawei.watch.home launcher: true`.
         * The watch-face *renderer* is a separate package
         * (com.huawei.watch.watchface) but it draws as a wallpaper behind
         * watch.home rather than owning the foreground window, so it is
         * watch.home that window-state events report.
         *
         * Every foreground package is logged at INFO, so if a ROM update
         * changes this, the new name is one logcat line away.
         */
        /**
         * Window-state events from these packages never change the foreground
         * package we track — see onAccessibilityEvent for why this matters.
         * "android" covers system dialogs, toasts and alert-window notices that
         * float above whatever is really in front. Our own package is excluded
         * too, but via Context.packageName rather than a literal here, so a
         * rename or a build flavour can't quietly break the check.
         */
        private val IGNORED_WINDOW_PACKAGES = setOf("android")

        private val WATCH_FACE_PACKAGES = setOf(
            "com.huawei.watch.home",
            "com.huawei.watch.watchface"
        )

        /**
         * Sentinel foregroundPackage value set the instant a LAUNCH_APP/CALL
         * action fires — see onCommandDetected(). Deliberately not a real
         * package name and not a member of WATCH_FACE_PACKAGES, so
         * isOnWatchFace() reads false immediately, without waiting on a
         * TYPE_WINDOW_STATE_CHANGED event that may be delayed — under load
         * from launching a heavy app, that event can lag past
         * POST_MATCH_RESTART_DELAY_MS, and resumeAfterActionRunnable would
         * otherwise see a stale foregroundPackage still claiming the watch
         * face and restart the mic/overlay on top of the app that just
         * opened. We already know for certain we're leaving the watch face
         * right here, by our own action, so there's no need to wait for a
         * signal to tell us something we already know.
         *
         * Trade-off: if the action's target never actually takes focus (e.g.
         * launchTargetApp/placeCall falls through to a notification fallback
         * because SYSTEM_ALERT_WINDOW isn't granted), no corrective event will
         * ever arrive either — nothing changed — and this marker sticks until
         * the next real window-state event or screen-off/on cycle (which
         * unconditionally reseeds foregroundPackage to the watch face; see
         * the SCREEN_ON branch below). Auto-resume is paused rather than
         * silently listening in the wrong place; a wrist-down/up or manual
         * return to the watch face clears it.
         */
        private const val AWAY_FROM_WATCH_FACE_MARKER = "<away-from-watch-face>"

        /**
         * Arbitrary, fixed request code for the flashlight's launch-fallback
         * notification (see VoiceNotifications.postLaunchFallback) — slots use
         * their own slot.id.hashCode() as this app never has more than one
         * flashlight window, so one constant is enough here.
         */
        private const val FLASHLIGHT_NOTIFICATION_REQUEST_CODE = -1001
    }

    // ---- Vosk state ----

    /**
     * Owned by ModelHolder, which does the closing. This field is just a local
     * convenience reference and is cleared, never closed, on teardown.
     */
    private var model: Model? = null

    /**
     * Kept alive across start/stop cycles and rebuilt only when the set of
     * command phrases changes. Constructing a grammar-constrained Recognizer
     * builds a decoding graph, so rebuilding it on every screen wake would be
     * both slow and, if the old one isn't closed, a native memory leak.
     *
     * Only touched from the main thread, and only while the capture loop is
     * stopped — see releaseRecognizer's note.
     */
    private var recognizer: Recognizer? = null

    /** Identifies the phrase set the current recognizer was built with. */
    private var recognizerGrammarKey: String? = null

    /** True when the grammar was rejected and we fell back to open recognition. */
    private var recognizerIsFallback = false

    private var captureLoop: AudioCaptureLoop? = null
    private var isListening = false

    // ---- Lifecycle / wiring ----

    private var initialized = false
    private var screenReceiver: BroadcastReceiver? = null
    private var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var vadPrefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = false
    private var wakeStartupLock: PowerManager.WakeLock? = null
    private var screenHoldWakeLock: PowerManager.WakeLock? = null

    private var telephonyManager: TelephonyManager? = null
    private var legacyPhoneStateListener: PhoneStateListener? = null
    private var modernTelephonyCallback: TelephonyCallback? = null

    /**
     * Read on the capture loop's reader thread on every frame, written on the
     * main thread when the settings page changes something.
     */
    @Volatile
    private var vadParams: VadSettings.Params = VadSettings.Params(
        thresholdRms = VadSettings.DEFAULT_THRESHOLD_RMS,
        hangoverMs = VadSettings.DEFAULT_HANGOVER_MS,
        prerollMs = VadSettings.DEFAULT_PREROLL_MS,
        screenHoldSeconds = VadSettings.DEFAULT_SCREEN_HOLD_SECONDS
    )

    // ---- Slot cache ----

    private var cachedSlots: List<VoiceSlot> = emptyList()

    /** Written on the main thread, read on the decoder thread. */
    @Volatile
    private var activeSlots: List<VoiceSlot> = emptyList()

    /** Phrase per active slot, in the same order — built once per cache refresh. */
    @Volatile
    private var activePhrases: List<String> = emptyList()

    // ---- Trigger state ----

    /** Written on the main thread, read on the decoder thread. */
    @Volatile
    private var launchInFlight = false

    private var lastActionWasCall = false

    /**
     * Last hypothesis string already examined. Vosk re-emits an unchanged
     * partial several times a second; skipping identical payloads avoids a
     * JSON parse and several allocations per emission. Decoder thread only.
     */
    private var lastHypothesis: String? = null

    // ---- Mic availability ----

    private var micVerified = false
    private val indicatorOverlay by lazy { MicIndicatorOverlay(this) }
    private var retryIndex = 0

    /**
     * Appearance of the indicator. Read on the main thread only, but reloaded
     * from the same preferences listener as the VAD parameters.
     *
     * Named arguments rather than positional: every field is an Int except the
     * first, so adding one to Params (as the colour settings did) would
     * otherwise compile happily with the values silently shifted.
     */
    private var overlayParams: OverlaySettings.Params = OverlaySettings.Params(
        enabled = OverlaySettings.DEFAULT_ENABLED,
        sizeDp = OverlaySettings.DEFAULT_SIZE_DP,
        offsetXDp = OverlaySettings.DEFAULT_OFFSET_X_DP,
        offsetYDp = OverlaySettings.DEFAULT_OFFSET_Y_DP,
        colorInitIndex = OverlaySettings.DEFAULT_COLOR_INIT_INDEX,
        colorActiveIndex = OverlaySettings.DEFAULT_COLOR_ACTIVE_INDEX
    )

    /**
     * Package of the window currently in front, from accessibility events.
     *
     * Seeded to the watch face on every SCREEN_ON rather than left stale: this
     * ROM wakes showing the watch face first (`showType:
     * WAKE_UP_SHOW_WATCH_FACE_FIRST` in the wake logs), and no window-state
     * event necessarily fires for a wake that changes nothing, so waiting for
     * one would mean never starting.
     */
    @Volatile
    private var foregroundPackage: String = WATCH_FACE_PACKAGES.first()

    private val retryRunnable = Runnable {
        // Capped rather than incremented unconditionally: once retryIndex
        // reaches RETRY_DELAYS_MS.size, scheduleRetry() switches to the
        // sustained long-interval retry below, and this must keep reading
        // that same past-the-end value on every subsequent firing rather
        // than walking off the end of RETRY_DELAYS_MS.
        if (retryIndex < RETRY_DELAYS_MS.size) retryIndex++
        startListening()
    }

    private val resumeAfterActionRunnable = Runnable {
        launchInFlight = false
        if (screenOn) startListening()
    }

    private val foregroundFallbackRunnable = Runnable { beginCapture() }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        // onServiceConnected can fire more than once for the same instance when
        // the system rebinds. Re-registering the receivers each time left
        // duplicate handlers all racing to start the recognizer.
        if (initialized) {
            refreshSlotCache()
            return
        }
        initialized = true

        VoiceNotifications.ensureChannels(this)
        postStatus("Инициализация…")
        vadParams = VadSettings.load(this)
        overlayParams = OverlaySettings.load(this)
        refreshSlotCache()
        registerPrefsChangeListener()
        registerVadPrefsChangeListener()
        registerScreenReceiver()
        registerCallStateListener()
        screenOn = isScreenCurrentlyOn()

        MicForegroundService.onForegroundReady = {
            handler.removeCallbacks(foregroundFallbackRunnable)
            beginCapture()
        }

        loadModel()
    }

    /**
     * The only accessibility event this service acts on: which package owns the
     * front window. Screen *content* is never inspected — `packageName` is
     * carried by the event itself, so `canRetrieveWindowContent` stays false.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (pkg.isNullOrEmpty() || pkg == foregroundPackage) return
        if (pkg == packageName || pkg in IGNORED_WINDOW_PACKAGES) {
            // Our own indicator overlay is a window, so adding it fires this
            // event naming us. Acting on it meant: show icon -> "not the watch
            // face" -> stop listening -> hide icon, a loop that killed capture
            // ~300 ms after it started. Toasts and the system's own
            // "displaying over other apps" notification arrive the same way.
            // None of these replace what the person is looking at, so none of
            // them should change whether we listen.
            return
        }
        if (isInputMethodWindow(event) && OwnAppForegroundTracker.isForeground) {
            // The on-screen keyboard (e.g. typing into the screen-hold-seconds
            // field on the VAD settings page) fires its own
            // TYPE_WINDOW_STATE_CHANGED, with the IME's own package — not
            // ours, not a watch-face package, not "android". Treating that as
            // real navigation would set foregroundPackage to the IME's
            // package and tear down listening/the overlay, and it would stay
            // torn down: when the keyboard closes and focus returns to our
            // own app, that event is exactly the one filtered out above
            // (pkg == packageName), so nothing would correct foregroundPackage
            // back on its own.
            //
            // Gated on OwnAppForegroundTracker.isForeground so a keyboard
            // opening inside some OTHER app is not ignored the same way —
            // that app's own field-entry screen must still be able to report
            // itself as "not the watch face".
            //
            // android.inputmethodservice.SoftInputWindow is the standard
            // class name Android reports for the system keyboard's window
            // regardless of which IME app is installed, so detecting it
            // needs no extra permission or window-content lookup.
            return
        }
        foregroundPackage = pkg
        // Logged unconditionally: this is how the watch-face package name gets
        // discovered if a ROM update ever changes it.
        Log.i(TAG, "Foreground package: $pkg (watch face: ${isOnWatchFace()})")
        handler.post { onForegroundPackageChanged() }
    }

    private fun isInputMethodWindow(event: AccessibilityEvent): Boolean =
        event.className?.toString() == "android.inputmethodservice.SoftInputWindow"

    private fun isOnWatchFace(): Boolean = foregroundPackage in WATCH_FACE_PACKAGES

    /**
     * Listening is deliberately confined to the watch face. Leaving it drops
     * the microphone immediately rather than waiting for the screen to go off,
     * which both removes the indicator from on top of whatever the person
     * opened and stops the decoder while they are actually using the watch.
     */
    private fun onForegroundPackageChanged() {
        if (isOnWatchFace()) {
            if (screenOn) startListening()
        } else {
            // Left the watch face: the icon must not sit on top of whatever the
            // person opened, so this is a real teardown, not the sleep case.
            if (isListening) stopListening(updateStatus = false)
            hideOverlay()
            postStatus("Ждёт циферблата")
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to do here.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        MicForegroundService.onForegroundReady = null
        releaseWakeStartupLock()
        releaseScreenHoldWakeLock()

        stopListening(updateStatus = false)
        hideOverlay()
        releaseRecognizer()

        // Strict ordering: the Recognizer holds a native pointer into the
        // Model, so the Model must outlive it. releaseRecognizer() above has
        // already closed the Recognizer by this point.
        model = null
        ModelHolder.closeAndClear()

        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — onUnbind can be called more than once.
            }
        }
        screenReceiver = null

        unregisterPrefsChangeListener()
        unregisterVadPrefsChangeListener()
        unregisterCallStateListener()
        MicForegroundService.stop(this)
        VoiceNotifications.releaseOwnership(this)
        initialized = false
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------
    // Slot cache
    // ------------------------------------------------------------------

    private fun refreshSlotCache() {
        cachedSlots = TargetAppPrefs.getSlots(this)
        val previousPhrases = activePhrases

        activeSlots = cachedSlots.filter { it.enabled && it.wakeWord.isNotBlank() }
        activePhrases = activeSlots.map { VoicePhrases.phraseFor(it) }
        val phrasesChanged = activePhrases != previousPhrases

        // The grammar is built from these phrases plus the fixed flashlight
        // phrases (see ensureRecognizer), so a changed set means the decoding
        // graph is stale, not just the status text. Listening is never gated
        // on activeSlots being non-empty any more — "включи/выключи фонарик"
        // must keep working even with zero configured slots.
        if (phrasesChanged && isListening) {
            stopListening(updateStatus = false)
            releaseRecognizer()
            if (screenOn) startListening()
        } else if (isListening) {
            postStatus(listeningStatusText())
        } else if (screenOn && isOnWatchFace()) {
            startListening()
        }
    }

    private fun registerPrefsChangeListener() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Listeners fire on whichever thread called apply(). Everything that
            // touches the recognizer must stay on one thread, so hop to main.
            handler.post { refreshSlotCache() }
        }
        getSharedPreferences(TargetAppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
        prefsChangeListener = listener
    }

    private fun unregisterPrefsChangeListener() {
        prefsChangeListener?.let {
            getSharedPreferences(TargetAppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsChangeListener = null
    }

    /**
     * VAD tuning lives in its own prefs file precisely so that changing it
     * takes this path instead of refreshSlotCache() — the grammar doesn't
     * depend on it, and rebuilding the decoding graph every time a slider
     * moves would be both wasteful and disruptive while capture is live.
     * New values are picked up by the reader thread on its next frame.
     */
    private fun registerVadPrefsChangeListener() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.post {
                vadParams = VadSettings.load(this)
                val newOverlay = OverlaySettings.load(this)
                if (newOverlay != overlayParams) {
                    overlayParams = newOverlay
                    // Re-show so size/position/visibility changes land straight
                    // away — the person is looking at the icon while dragging
                    // the sliders, so applying this on the next listen cycle
                    // would make the settings feel broken.
                    if (indicatorOverlay.isShowing) {
                        indicatorOverlay.show(
                            newOverlay,
                            if (isListening) MicIndicatorView.State.RECORDING
                            else MicIndicatorView.State.INITIALIZING
                        )
                    }
                }
            }
        }
        getSharedPreferences(VadSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
        vadPrefsChangeListener = listener
    }

    private fun unregisterVadPrefsChangeListener() {
        vadPrefsChangeListener?.let {
            getSharedPreferences(VadSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        vadPrefsChangeListener = null
    }

    // ------------------------------------------------------------------
    // Model loading
    // ------------------------------------------------------------------

    private fun loadModel() {
        // StorageService.unpack copies assets/model-ru into
        // <externalFilesDir>/model/model-ru on first run (skipping the copy on
        // later runs by comparing the `uuid` file), off the main thread, and
        // calls back on the main thread.
        StorageService.unpack(
            this, "model-ru", "model",
            { unpackedModel: Model ->
                model = unpackedModel
                // Published so MainActivity's wake-word check can borrow it
                // instead of loading a second copy.
                ModelHolder.publish(unpackedModel)
                postStatus("Готов, ждёт включения экрана")
                if (isScreenCurrentlyOn()) {
                    screenOn = true
                    startListening()
                } else if (KEEP_OVERLAY_WHILE_ASLEEP && isOnWatchFace()) {
                    // Put the window up straight away even with the screen off,
                    // so that the experiment's precondition — a window that
                    // already exists at wake time — holds from the very first
                    // sleep, not only after the first successful listen.
                    indicatorOverlay.show(
                        overlayParams, MicIndicatorView.State.INITIALIZING
                    )
                }
            },
            { exception: java.io.IOException ->
                Log.e(TAG, "Failed to unpack Vosk model", exception)
                postStatus("Ошибка загрузки модели распознавания")
            }
        )
    }

    // ------------------------------------------------------------------
    // Screen on/off handling
    // ------------------------------------------------------------------

    private fun isScreenCurrentlyOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        // isInteractive is already false in ambient/always-on display, so no
        // separate ambient check is needed.
        return pm.isInteractive
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        // This ROM wakes showing the watch face first only
                        // when nothing else was in front — a wake that
                        // changes no window fires no window-state event, so
                        // there's nothing to wait for in that case. But if
                        // some other app was genuinely in front when the
                        // screen timed out, this ROM can wake straight back
                        // into that app, and foregroundPackage already
                        // correctly says so from the real event that fired
                        // when it was opened — overwriting a concretely-known
                        // package with "assume watch face" would put the
                        // mic/overlay on top of that app after the wake.
                        //
                        // So: only re-assume the watch face when we
                        // genuinely don't know where we are — the
                        // AWAY_FROM_WATCH_FACE_MARKER sentinel set by
                        // onCommandDetected() when a launched target never
                        // actually took focus (see that constant's doc). A
                        // concretely-known other package is left exactly as
                        // it is; if the true watch face IS what's showing
                        // after this wake, the real TYPE_WINDOW_STATE_CHANGED
                        // event for that transition corrects foregroundPackage
                        // within milliseconds regardless.
                        if (foregroundPackage == AWAY_FROM_WATCH_FACE_MARKER) {
                            foregroundPackage = WATCH_FACE_PACKAGES.first()
                        }
                        acquireWakeStartupLock()
                        // Slots may have been edited while the screen was off.
                        refreshSlotCache()
                        cancelRetry()
                        startListening()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        cancelRetry()
                        handler.removeCallbacks(resumeAfterActionRunnable)
                        handler.removeCallbacks(foregroundFallbackRunnable)
                        launchInFlight = false
                        stopListening()
                        releaseWakeStartupLock()
                        releaseScreenHoldWakeLock()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiver = receiver
    }

    /**
     * Briefly holds a partial wake lock right after SCREEN_ON, released a
     * couple of seconds later or on the next SCREEN_OFF, whichever comes first.
     *
     * This does NOT fix the static-watch-face microphone-silencing limit (see
     * MicForegroundService) — a wake lock keeps the CPU running, not the
     * microphone gated open, and this app has no API to influence that. It's
     * here so the foreground-service start and the audio preflight probe
     * don't lose their race against the ROM's own wake housekeeping on a slow
     * watch CPU right after wake.
     */
    private fun acquireWakeStartupLock() {
        releaseWakeStartupLock()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mic_startup"
            )
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_STARTUP_LOCK_MS)
            wakeStartupLock = lock
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire startup wake lock", e)
        }
    }

    private fun releaseWakeStartupLock() {
        val lock = wakeStartupLock ?: return
        wakeStartupLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release startup wake lock", e)
        }
    }

    /**
     * Keeps the screen on (bright, exempt from the idle timeout) for
     * vadParams.screenHoldSeconds after a command fires — see
     * onCommandDetected(). Without this, the watch's own screen timeout can
     * land mid-launch: the person says the command, the screen goes dark
     * while the target app is still opening, and they have to press the
     * button again to actually see it.
     *
     * SCREEN_BRIGHT_WAKE_LOCK is deprecated in favour of
     * FLAG_KEEP_SCREEN_ON on a window, which isn't available here — this
     * service has no window of its own, only the 1x1 overlay, and keeping an
     * overlay window flagged KEEP_SCREEN_ON has no effect on the screen the
     * *target* Activity is shown on. The deprecated wake lock is still fully
     * functional and is the only mechanism that reaches across processes
     * like this.
     */
    private fun holdScreenAfterCommand() {
        val seconds = vadParams.screenHoldSeconds
        if (seconds <= 0) return
        releaseScreenHoldWakeLock()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "$packageName:command_screen_hold"
            )
            lock.setReferenceCounted(false)
            lock.acquire(seconds * 1000L)
            screenHoldWakeLock = lock
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire screen-hold wake lock", e)
        }
    }

    private fun releaseScreenHoldWakeLock() {
        val lock = screenHoldWakeLock ?: return
        screenHoldWakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release screen-hold wake lock", e)
        }
    }

    // ------------------------------------------------------------------
    // Call-state handling (works around mic silencing after calls)
    // ------------------------------------------------------------------

    private fun registerCallStateListener() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "READ_PHONE_STATE not granted — falling back to the fixed delay only")
            return
        }
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val tm = telephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) onCallEnded()
                }
            }
            try {
                tm.registerTelephonyCallback(mainExecutor, callback)
                modernTelephonyCallback = callback
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register TelephonyCallback", e)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) onCallEnded()
                }
            }
            try {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyPhoneStateListener = listener
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PhoneStateListener", e)
            }
        }
    }

    private fun unregisterCallStateListener() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernTelephonyCallback?.let {
                try {
                    tm.unregisterTelephonyCallback(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister TelephonyCallback", e)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneStateListener?.let {
                try {
                    tm.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister PhoneStateListener", e)
                }
            }
        }
        modernTelephonyCallback = null
        legacyPhoneStateListener = null
        telephonyManager = null
    }

    private fun onCallEnded() {
        if (!lastActionWasCall) return
        Log.i(TAG, "Call ended — forcing recognizer restart")
        lastActionWasCall = false
        // The post-action timer may still be pending; without clearing the
        // in-flight flag here the service would resume listening but ignore
        // every command until that timer fired.
        handler.removeCallbacks(resumeAfterActionRunnable)
        launchInFlight = false
        // The audio policy may still be settling after a call, so re-probe
        // rather than trusting the earlier verification.
        micVerified = false
        stopListening(updateStatus = false)
        if (!screenOn) return

        // CALL_STATE_IDLE fires the instant the call itself ends, which on
        // this ROM is consistently a beat before the UI has actually finished
        // animating back to the watch face — so startListening() right here
        // can bail out on isOnWatchFace() being false, and unlike its other
        // callers this path must actually retry rather than wait on a window
        // event that may not arrive promptly. retryIndex is reset first so an
        // unrelated bounded retry sequence used up earlier doesn't count
        // against this one.
        retryIndex = 0

        // foregroundPackage was pointed at the dialer/call-UI's real package
        // when the call started (see AWAY_FROM_WATCH_FACE_MARKER's doc), and
        // only a TYPE_WINDOW_STATE_CHANGED event normally corrects it back.
        // If the call UI finishes straight into the watch face without such
        // an event firing for that transition, foregroundPackage would be
        // left pointing at the call UI forever and no retry would help.
        // CALL_STATE_IDLE is telephony itself confirming the call is over, so
        // — exactly like the SCREEN_ON handler does for its own stale-sentinel
        // case — it's safe to assume we're back on the watch face rather than
        // wait on a window event that may not come; a wrong assumption (the
        // person navigated elsewhere during the call) is corrected within
        // milliseconds by the next real TYPE_WINDOW_STATE_CHANGED.
        if (!isOnWatchFace()) {
            foregroundPackage = WATCH_FACE_PACKAGES.first()
        }

        startListening()
        if (!isListening) scheduleRetry()
    }

    // ------------------------------------------------------------------
    // Listening control
    // ------------------------------------------------------------------

    private fun startListening() {
        if (isListening) return
        if (model == null) return // loadModel's callback will call back in

        if (!isOnWatchFace()) {
            postStatus("Ждёт циферблата")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            postStatus("Нет разрешения на микрофон")
            return
        }

        if (MicForegroundService.isForeground) {
            beginCapture()
        } else {
            MicForegroundService.start(this, "Слушает…")
            handler.removeCallbacks(foregroundFallbackRunnable)
            handler.postDelayed(foregroundFallbackRunnable, FOREGROUND_WAIT_FALLBACK_MS)
        }
    }

    /**
     * Second half of startListening(), split out because it may run either
     * inline (foreground service already up) or from the service's ready
     * callback.
     */
    private fun beginCapture() {
        if (isListening || !screenOn || model == null) return
        if (!isOnWatchFace()) return

        // The overlay goes up BEFORE the microphone is probed, not after it
        // fails. On a static preset watch face this ROM wakes by putting up a
        // co-processor-held screenshot and never composites an Android frame,
        // and Huawei's audio service mutes recording in that state through its
        // own path (see MicIndicatorOverlay). A real visible window is what
        // forces a composition, so it has to exist before the first read, not
        // as a recovery step afterwards.
        indicatorOverlay.show(overlayParams, MicIndicatorView.State.INITIALIZING)

        if (!micVerified && !verifyMicrophone()) return

        val activeRecognizer = ensureRecognizer() ?: run {
            postStatus("Ошибка распознавателя")
            return
        }

        try {
            activeRecognizer.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer.reset failed", e)
        }
        lastHypothesis = null

        val loop = AudioCaptureLoop(SAMPLE_RATE_INT, { vadParams }, this)
        if (!loop.start()) {
            Log.e(TAG, "Capture loop refused to start")
            micVerified = false
            scheduleRetry()
            return
        }

        captureLoop = loop
        isListening = true
        retryIndex = 0
        indicatorOverlay.setState(MicIndicatorView.State.RECORDING)
        postStatus(listeningStatusText())
    }

    /**
     * Probes the microphone and, if the process is being fed silence, tries the
     * 1x1 overlay window trick before giving up. Sets micVerified on success.
     */
    private fun verifyMicrophone(): Boolean {
        when (AudioPreflight.probe(SAMPLE_RATE_INT)) {
            AudioPreflight.Result.OK -> {
                micVerified = true
                return true
            }
            AudioPreflight.Result.SILENT -> {
                // The overlay is already up by this point (see beginCapture),
                // so there is nothing left to escalate to — one more probe only
                // gives the window a moment to actually reach the compositor.
                if (indicatorOverlay.isShowing &&
                    AudioPreflight.probe(SAMPLE_RATE_INT) == AudioPreflight.Result.OK
                ) {
                    micVerified = true
                    return true
                }
                Log.e(TAG, "Microphone returns silence — the system is muting this process")
                postStatus("Микрофон заглушен системой")
                scheduleRetry()
                return false
            }
            AudioPreflight.Result.UNAVAILABLE -> {
                Log.e(TAG, "Microphone unavailable")
                postStatus("Микрофон недоступен")
                scheduleRetry()
                return false
            }
        }
    }

    private fun stopListening(updateStatus: Boolean = true) {
        releaseAudio()
        // The overlay deliberately survives here — see KEEP_OVERLAY_WHILE_ASLEEP.
        // It is only really torn down when we leave the watch face or the
        // service unbinds, both of which call hideOverlay() explicitly.
        if (KEEP_OVERLAY_WHILE_ASLEEP && isOnWatchFace()) {
            indicatorOverlay.show(overlayParams, MicIndicatorView.State.INITIALIZING)
        } else {
            indicatorOverlay.hide()
        }
        MicForegroundService.stop(this)
        if (updateStatus) postStatus("Готов, ждёт включения экрана")
    }

    /**
     * Unconditional teardown, for the cases where the overlay must not stay:
     * the person navigated away from the watch face, or the service is going
     * away entirely.
     */
    private fun hideOverlay() {
        indicatorOverlay.hide()
    }

    /**
     * Stops capture unconditionally — deliberately not guarded by isListening,
     * because the case that most needs cleaning up is exactly the one where a
     * start failed halfway and isListening is false.
     *
     * AudioCaptureLoop.stop() joins both of its threads before returning, so
     * once this call completes nothing is inside the Recognizer any more.
     *
     * The Recognizer itself is intentionally kept: it's expensive to rebuild
     * and isn't tied to the recorder. releaseRecognizer() handles it.
     */
    private fun releaseAudio() {
        val loop = captureLoop
        captureLoop = null
        isListening = false
        lastHypothesis = null
        if (loop != null) {
            try {
                loop.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop stop failed", e)
            }
        }
    }

    /**
     * Builds the recognizer for the current command phrases.
     *
     * Three tiers, in order:
     *
     *  1. A grammar of the full two-word phrases plus `[unk]`. This is the
     *     intended mode: the smallest possible decoding graph, and noise has to
     *     land on two specific words in sequence to trigger anything.
     *  2. If that constructor is rejected, a grammar of the individual words.
     *     Weaker against false triggers but still cheap, and it isolates the
     *     failure to phrase support rather than losing recognition entirely.
     *  3. Fully open recognition. Correct but slow enough that other apps
     *     stutter — the status line says so explicitly rather than leaving a
     *     silently degraded build looking healthy.
     *
     * Tier 1 should not normally fail: every wake word is checked against the
     * model's vocabulary at save time (WakeWordDictionary) and both prefixes
     * are known-present words. The ladder exists so that a model swap or a
     * hand-edited prefs file degrades visibly instead of silently.
     */
    private fun ensureRecognizer(): Recognizer? {
        val currentModel = model ?: return null
        // The two flashlight phrases are folded in unconditionally — they are
        // reserved commands outside the slot mechanism entirely (see
        // VoicePhrases), always active regardless of what slots exist.
        val phrases = (activePhrases.filter { it.isNotBlank() } + VoicePhrases.FLASHLIGHT_PHRASES)
            .distinct()
        val key = phrases.joinToString("|")

        recognizer?.let { existing ->
            if (recognizerGrammarKey == key) return existing
        }
        releaseRecognizer()

        if (phrases.isNotEmpty()) {
            // Tried first: the real phrases plus a handful of words
            // phonetically close to each prefix (see VoicePhrases' doc),
            // purely so ambiguous audio has somewhere else to land instead of
            // snapping onto an actual command. If any decoy turns out to be
            // outside the model's vocabulary, grammar construction is
            // rejected as a whole and this falls through to the identical
            // decoy-free attempt right below — never to the slow open-vocab
            // tier over a guess that didn't pan out.
            val decoys = VoicePhrases.DECOY_WORDS_LAUNCH_APP + VoicePhrases.DECOY_WORDS_CALL
            buildWithGrammar(currentModel, grammarJson(phrases + decoys))?.let { built ->
                recognizer = built
                recognizerGrammarKey = key
                recognizerIsFallback = false
                Log.i(TAG, "Recognizer built with phrase grammar + decoys: $phrases / $decoys")
                return built
            }
            Log.e(TAG, "Phrase grammar + decoys rejected — retrying without decoys")

            buildWithGrammar(currentModel, grammarJson(phrases))?.let { built ->
                recognizer = built
                recognizerGrammarKey = key
                recognizerIsFallback = false
                Log.i(TAG, "Recognizer built with phrase grammar: $phrases")
                return built
            }

            val words = phrases.flatMap { it.split(" ") }.filter { it.isNotBlank() }.distinct()
            buildWithGrammar(currentModel, grammarJson(words))?.let { built ->
                recognizer = built
                recognizerGrammarKey = key
                recognizerIsFallback = false
                Log.e(TAG, "Phrase grammar rejected — fell back to a word grammar: $words")
                return built
            }
        }

        return try {
            val built = Recognizer(currentModel, SAMPLE_RATE)
            built.setWords(true)
            recognizer = built
            recognizerGrammarKey = key
            recognizerIsFallback = true
            Log.e(TAG, "Grammar unavailable — running fully open (slow)")
            built
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer creation failed", e)
            recognizer = null
            recognizerGrammarKey = null
            recognizerIsFallback = false
            null
        }
    }

    private fun buildWithGrammar(currentModel: Model, grammar: String): Recognizer? = try {
        Recognizer(currentModel, SAMPLE_RATE, grammar).apply {
            // Per-word confidence, available in final results only (Vosk never
            // puts it in partials). Nothing gates on it yet — it's logged on
            // every completed utterance so a threshold can be set from real
            // numbers later if false triggers survive the phrase requirement.
            setWords(true)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Grammar recognizer construction failed", e)
        null
    }

    /**
     * `[unk]` is what lets the decoder answer "none of these" instead of being
     * forced to pick the nearest entry. Padding the grammar with a large
     * vocabulary instead would cost as much CPU as fully open recognition
     * (see class doc) without buying anything the phrase requirement doesn't
     * already provide more cheaply.
     */
    private fun grammarJson(entries: List<String>): String {
        val array = JSONArray()
        for (entry in entries) array.put(entry)
        array.put("[unk]")
        return array.toString()
    }

    /**
     * Must only be called with the capture loop stopped: the decoder thread
     * calls into this Recognizer, and closing it underneath that thread is a
     * use-after-free on a native pointer, not a caught exception.
     */
    private fun releaseRecognizer() {
        val current = recognizer
        recognizer = null
        recognizerGrammarKey = null
        recognizerIsFallback = false
        if (current != null) {
            try {
                current.close()
            } catch (e: Exception) {
                Log.e(TAG, "Recognizer.close failed", e)
            }
        }
    }

    private fun listeningStatusText(): String =
        if (recognizerIsFallback) "Слушает (резервный режим, медленно)" else "Слушает…"

    private fun scheduleRetry() {
        if (!screenOn) return
        handler.removeCallbacks(retryRunnable)
        if (retryIndex < RETRY_DELAYS_MS.size) {
            handler.postDelayed(retryRunnable, RETRY_DELAYS_MS[retryIndex])
            return
        }
        // The fast backoff burst (300ms .. 8s, ~15s total) covers a normal
        // transient hiccup. Past that, keep trying indefinitely at a slower,
        // steady interval instead of giving up — cheap while idle, and it
        // means the app recovers by itself whenever the mic becomes available
        // again (e.g. the ROM stops silencing this process after a call),
        // without requiring the person to notice and cycle the screen.
        postStatus("Микрофон недоступен — жду восстановления…")
        handler.postDelayed(retryRunnable, SUSTAINED_RETRY_DELAY_MS)
    }

    private fun cancelRetry() {
        handler.removeCallbacks(retryRunnable)
        retryIndex = 0
    }

    // ------------------------------------------------------------------
    // Overlay fallback for a muted microphone
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // AudioCaptureLoop.Sink — all three run on the decoder thread
    // ------------------------------------------------------------------

    override fun onAudioFrame(data: ShortArray, length: Int) {
        val active = recognizer ?: return
        try {
            if (active.acceptWaveForm(data, length)) {
                // Vosk considers the utterance finished on its own terms.
                handleHypothesis(active.result, final = true)
            } else {
                handleHypothesis(active.partialResult, final = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptWaveForm failed", e)
        }
    }

    override fun onSegmentEnd() {
        val active = recognizer ?: return
        try {
            // The gate closed, so whatever is still buffered in the decoder is
            // the tail of the command — flush it, then clear the decoder state
            // so the next utterance starts clean rather than accumulating.
            handleHypothesis(active.finalResult, final = true)
            active.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Segment finalisation failed", e)
        }
        lastHypothesis = null
    }

    override fun onCaptureError(error: Exception) {
        Log.e(TAG, "Capture error", error)
        handler.post {
            // Most commonly the mic was taken away mid-stream. Tear down and
            // retry with backoff instead of sitting on a dead stream.
            micVerified = false
            releaseAudio()
            if (KEEP_OVERLAY_WHILE_ASLEEP && isOnWatchFace()) {
                indicatorOverlay.setState(MicIndicatorView.State.INITIALIZING)
            } else {
                hideOverlay()
            }
            if (screenOn) scheduleRetry()
        }
    }

    // ------------------------------------------------------------------
    // Command matching (decoder thread)
    // ------------------------------------------------------------------

    private fun handleHypothesis(hypothesisJson: String?, final: Boolean) {
        if (hypothesisJson == null || launchInFlight) return
        // Vosk re-emits an unchanged partial several times a second; bailing
        // out on an identical payload saves a JSON parse and several string
        // allocations per emission, which matters on this CPU.
        if (!final && hypothesisJson == lastHypothesis) return
        lastHypothesis = hypothesisJson

        val phrases = activePhrases
        val slots = activeSlots
        // Read once: this flips on the main thread, and every branch below
        // must act on one consistent snapshot rather than possibly changing
        // mid-decision.
        val flashlightShowing = FlashlightController.isShowing

        // Fast reject before touching JSON at all: phrases are lowercase and
        // Vosk emits lowercase Russian, so a raw substring scan rules out the
        // overwhelming majority of emissions without allocating anything.
        // "выключи фонарик" is checked unconditionally; the rest of the
        // grammar (slots, "включи фонарик") only matters when the flashlight
        // screen isn't already showing — see the flashlightShowing branch
        // below for why.
        var candidate = hypothesisJson.contains(VoicePhrases.PHRASE_FLASHLIGHT_OFF)
        if (!candidate && !flashlightShowing) {
            if (hypothesisJson.contains(VoicePhrases.PHRASE_FLASHLIGHT_ON)) {
                candidate = true
            } else {
                for (phrase in phrases) {
                    if (phrase.isNotEmpty() && hypothesisJson.contains(phrase)) {
                        candidate = true
                        break
                    }
                }
            }
        }
        if (!candidate) {
            if (final) logFinalForCalibration(hypothesisJson)
            return
        }

        val text = try {
            val json = JSONObject(hypothesisJson)
            val partial = json.optString("partial", "")
            if (partial.isNotEmpty()) partial else json.optString("text", "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recognizer output", e)
            return
        }
        if (text.isEmpty()) return

        if (containsWholePhrase(text, VoicePhrases.PHRASE_FLASHLIGHT_OFF)) {
            Log.i(TAG, "Match: heard «$text» → ${VoicePhrases.PHRASE_FLASHLIGHT_OFF} (final=$final)")
            if (final) logFinalForCalibration(hypothesisJson)
            handler.post { onFlashlightOffCommand() }
            return
        }

        if (flashlightShowing) {
            // Per spec, while the white screen is up we listen for nothing
            // else — not "включи фонарик" again, not any slot — only the tap
            // and "выключи фонарик" above can close it.
            return
        }

        if (containsWholePhrase(text, VoicePhrases.PHRASE_FLASHLIGHT_ON)) {
            Log.i(TAG, "Match: heard «$text» → ${VoicePhrases.PHRASE_FLASHLIGHT_ON} (final=$final)")
            if (final) logFinalForCalibration(hypothesisJson)
            handler.post { onFlashlightOnCommand() }
            return
        }

        val matchedIndex = phrases.indexOfFirst {
            it.isNotEmpty() && containsWholePhrase(text, it)
        }
        if (matchedIndex < 0) return
        val matchedSlot = slots.getOrNull(matchedIndex) ?: return

        Log.i(TAG, "Match: heard «$text» → command «${phrases[matchedIndex]}» (final=$final)")
        if (final) logFinalForCalibration(hypothesisJson)

        handler.post { onCommandDetected(matchedSlot) }
    }

    /**
     * Logs the recognized text of a completed utterance together with its
     * per-word confidences.
     *
     * This is the calibration hook: if false triggers ever survive the phrase
     * requirement and the VAD gate, these lines are what a confidence
     * threshold would be chosen from. Nothing acts on the numbers today —
     * a cutoff should come from real data, not a guess.
     */
    private fun logFinalForCalibration(hypothesisJson: String) {
        try {
            val json = JSONObject(hypothesisJson)
            val text = json.optString("text", "")
            if (text.isEmpty()) return
            val words = json.optJSONArray("result")
            if (words == null) {
                Log.i(TAG, "Final: «$text»")
                return
            }
            val builder = StringBuilder()
            for (i in 0 until words.length()) {
                val word = words.optJSONObject(i) ?: continue
                if (builder.isNotEmpty()) builder.append(", ")
                builder.append(word.optString("word", "?"))
                    .append('=')
                    .append(String.format("%.2f", word.optDouble("conf", -1.0)))
            }
            Log.i(TAG, "Final: «$text» conf[$builder]")
        } catch (e: Exception) {
            // Diagnostics only — never let logging break recognition.
        }
    }

    /**
     * Whole-word (or whole-phrase) containment. A plain contains() would also
     * match a command buried inside a longer word, which is an easy source of
     * false triggers.
     */
    private fun containsWholePhrase(text: String, phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        var index = text.indexOf(phrase)
        while (index >= 0) {
            val beforeOk = index == 0 || !text[index - 1].isLetterOrDigit()
            val end = index + phrase.length
            val afterOk = end >= text.length || !text[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = text.indexOf(phrase, index + 1)
        }
        return false
    }

    // ------------------------------------------------------------------
    // Flashlight command dispatch (main thread)
    // ------------------------------------------------------------------

    /**
     * Unlike onCommandDetected() below, this deliberately does NOT touch the
     * microphone, the overlay, or foregroundPackage: FlashlightActivity opens
     * in our own package, which onAccessibilityEvent already ignores (see its
     * `pkg == packageName` check), so none of the watch-face/leave-watch-face
     * bookkeeping that a LAUNCH_APP/CALL slot needs applies here — capture
     * just keeps running underneath it exactly as it was.
     */
    private fun onFlashlightOnCommand() {
        if (FlashlightController.isShowing) return
        val intent = Intent(this, FlashlightActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FlashlightActivity, falling back to notification", e)
            }
        }
        VoiceNotifications.postLaunchFallback(
            this, intent, "Фонарик…", FLASHLIGHT_NOTIFICATION_REQUEST_CODE
        )
    }

    private fun onFlashlightOffCommand() {
        FlashlightController.requestClose()
    }

    // ------------------------------------------------------------------
    // Action dispatch (main thread)
    // ------------------------------------------------------------------

    private fun onCommandDetected(slot: VoiceSlot) {
        if (launchInFlight) return
        launchInFlight = true

        lastActionWasCall = slot.actionType == SlotActionType.CALL

        // Release the microphone *before* performing the action. Launching the
        // target (or placing the call) while still holding an open
        // VOICE_RECOGNITION recorder meant the telephony/assistant audio path
        // came up against a mic this app was still using — a large part of the
        // "the ROM silences the mic after a call" behaviour.
        releaseAudio()
        hideOverlay()
        MicForegroundService.stop(this)

        // See AWAY_FROM_WATCH_FACE_MARKER's doc: we know for a fact we're
        // about to leave the watch face, so say so now rather than trusting a
        // window-state event that a heavy launch can delay past the restart
        // timer below.
        foregroundPackage = AWAY_FROM_WATCH_FACE_MARKER

        performAction(slot)
        holdScreenAfterCommand()

        val restartDelay = if (lastActionWasCall) {
            POST_CALL_RESTART_DELAY_MS
        } else {
            POST_MATCH_RESTART_DELAY_MS
        }
        handler.removeCallbacks(resumeAfterActionRunnable)
        handler.postDelayed(resumeAfterActionRunnable, restartDelay)
    }

    private fun performAction(slot: VoiceSlot) {
        try {
            when (slot.actionType) {
                SlotActionType.LAUNCH_APP -> launchTargetApp(slot)
                SlotActionType.CALL -> placeCall(slot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform action", e)
            // A CALL that never started will never produce an IDLE transition,
            // so don't leave the flag armed for some unrelated later call.
            if (slot.actionType == SlotActionType.CALL) lastActionWasCall = false
        }
    }

    /**
     * Opens the target app/Activity for a LAUNCH_APP slot.
     *
     * A plain startActivity() from a background process is normally blocked, but
     * a user-granted SYSTEM_ALERT_WINDOW is one of the documented exemptions.
     * MainActivity requests it automatically on first open. Without it we fall
     * back to a full-screen notification intent.
     */
    private fun launchTargetApp(slot: VoiceSlot) {
        if (slot.packageName.isBlank() || slot.activityName.isBlank()) {
            Log.e(TAG, "Slot has no package/activity configured")
            return
        }
        if (slot.intentAction.isBlank()) {
            Log.e(TAG, "Slot has no Intent Action configured")
            return
        }

        val targetIntent = Intent(slot.intentAction).apply {
            component = ComponentName(slot.packageName, slot.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(targetIntent)
                return
            } catch (e: Exception) {
                Log.e(TAG, "startActivity failed, falling back to notification", e)
            }
        }
        VoiceNotifications.postLaunchFallback(
            this, targetIntent, "Запуск приложения…", slot.id.hashCode()
        )
    }

    /**
     * Places a call directly (ACTION_CALL requires CALL_PHONE, unlike
     * ACTION_DIAL which just opens the dialer).
     */
    private fun placeCall(slot: VoiceSlot) {
        if (slot.phoneNumber.isBlank()) {
            Log.e(TAG, "Slot has no phone number configured")
            lastActionWasCall = false
            return
        }
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CALL_PHONE not granted — cannot place call")
            lastActionWasCall = false
            return
        }

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${slot.phoneNumber}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(callIntent)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to place call via startActivity", e)
            }
        }
        VoiceNotifications.postLaunchFallback(
            this, callIntent, "Звонок…", slot.id.hashCode()
        )
    }

    private fun postStatus(text: String) {
        VoiceNotifications.postStatus(this, text)
    }
}
