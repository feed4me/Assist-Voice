package com.nikolay.assistvoice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Controls the separate YandexMusicWatch app's player from the fixed,
 * non-slot voice commands in VoicePhrases (PHRASE_MUSIC_*) — the same kind
 * of always-on reserved command as the flashlight, dispatched from
 * VoiceAccessibilityService.onMusicCommand().
 *
 * YandexMusicWatch exposes its player as a standard Media3
 * MediaLibraryService (see its own MusicPlaybackService class doc, written
 * specifically to support being driven this way).
 *
 * "включи волну"/"включи любимую музыку"/"включи музыку" always target
 * YandexMusicWatch explicitly — the whole point of these commands is to
 * wake *this* app specifically. Controlling a connected phone's own
 * playback instead was investigated and found impossible: a phone's "now
 * playing" relayed onto this watch's system player is not a real Android
 * MediaSession at all (it doesn't appear in `dumpsys media_session`, and
 * neither AudioManager.dispatchMediaKeyEvent() nor a raw injected hardware
 * key event ever reaches it) — it's rendered by a separate, undocumented
 * Huawei cross-device mechanism with no accessible API.
 *
 * "выключи музыку"/"следующий трек"/"предыдущий трек"/"продолжи
 * воспроизведение" are the opposite: always generic, deliberately not
 * pinned to YandexMusicWatch, so they act on whatever currently holds
 * media-button focus — which in practice is YandexMusicWatch once it has
 * an active session (confirmed via `dumpsys media_session` and matching
 * tempAllowlistTargetPkgIfPossible log lines), but isn't hardcoded to be.
 *
 * Two ways of sending a media key event do the actual work:
 *
 * - sendMediaButton() — a real ACTION_MEDIA_BUTTON broadcast addressed to
 *   YandexMusicWatch specifically (explicit package), delivered to its
 *   exported MediaButtonReceiver. Used for PLAY: a MediaController
 *   connecting to a service that isn't already running has to ask that
 *   service to promote itself to a foreground service the first time it
 *   plays anything, and Android refuses that when it's triggered by an
 *   arbitrary background bind from another app. A genuine media-button
 *   broadcast is one of the documented exemptions from that restriction —
 *   it's exactly the path YandexMusicWatch's own MediaButtonReceiver/
 *   onPlaybackResumption is written to rely on for "resume from the lock
 *   screen when nothing is running" (see that class's doc). This path only
 *   works for PLAY: Media3's MediaButtonReceiver unconditionally ignores
 *   any other key delivered this way on API 26+ (logged as "Ignore key
 *   event that is not a `play` command..."), confirmed on-device — so it's
 *   never used for pause/next/previous/continue.
 * - sendGenericMediaButton() — AudioManager.dispatchMediaKeyEvent(), the
 *   same call a wired/Bluetooth headset button makes, addressed to no
 *   particular app. Used for pause/next/previous/continue, both because
 *   the explicit broadcast can't deliver non-PLAY keys and because these
 *   four are deliberately not pinned to YandexMusicWatch (see above).
 *
 * "включи волну"/"включи любимую музыку" additionally need a specific
 * section, which can't be expressed as a media button at all (there's no
 * key for "play this section"), so those follow the PLAY button with a
 * bound MediaController's setMediaItem() once the service has had a
 * moment to come up — see playSectionAfterWarmup.
 */
object YandexMusicController {

    private const val TAG = "YandexMusicController"
    private const val PACKAGE_NAME = "com.nikolay.yamusicwatch"
    private const val SERVICE_CLASS_NAME = "com.nikolay.yamusicwatch.playback.MusicPlaybackService"

    // Commands issued right after connecting are asynchronous binder calls;
    // releasing the controller the instant they're issued (rather than once
    // they've actually reached the session) can drop them. This just needs
    // to outlast that round trip, not the resulting playback.
    private const val RELEASE_DELAY_MS = 5_000L

    // How long to give the warm-up PLAY button event to actually get
    // YandexMusicWatch's process started and its player promoted to a
    // foreground service before the real section command follows — see
    // playSectionAfterWarmup. Not an exact science: just needs to be
    // comfortably longer than a cold process start + MediaSession setup.
    private const val WARMUP_DELAY_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** "включи волну" */
    fun playWave(context: Context) = playSectionAfterWarmup(context, "wave")

    /** "включи любимую музыку" */
    fun playLikes(context: Context) = playSectionAfterWarmup(context, "likes")

    /**
     * "включи музыку" — resumes whatever was last playing (or starts the
     * wave if nothing was). Sent as a PLAY media-button event rather than a
     * bound MediaController for the cold-start reason in the class doc;
     * this is also literally the trigger path YandexMusicWatch's
     * onPlaybackResumption() is written to expect (see its doc) — it
     * resumes the last saved queue/position, or falls back to the wave if
     * there was none.
     */
    fun resume(context: Context) = sendMediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** "выключи музыку" */
    fun pause(context: Context) = sendGenericMediaButton(context, KeyEvent.KEYCODE_MEDIA_PAUSE)

    /**
     * "продолжи воспроизведение" — always generic, never targets
     * YandexMusicWatch specifically. Resumes whatever currently holds
     * media-button focus, same as pause/next/previous below.
     */
    fun continuePlayback(context: Context) = sendGenericMediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** "следующий трек" */
    fun next(context: Context) = sendGenericMediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT)

    /** "предыдущий трек" */
    fun previous(context: Context) = sendGenericMediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /**
     * Sends a PLAY button event first — using the same background-start
     * exemption as sendMediaButton()'s other callers — to get
     * YandexMusicWatch's service running and in the foreground, then
     * follows up with the actual section request over a bound
     * MediaController once it's had a moment to come up. If the service
     * was already running this just means a harmless extra "play" (a
     * no-op if it was already playing) before the section switch a moment
     * later.
     */
    private fun playSectionAfterWarmup(context: Context, mediaId: String) {
        sendMediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        mainHandler.postDelayed({
            withController(context) { it.playSection(mediaId) }
        }, WARMUP_DELAY_MS)
    }

    private fun MediaController.playSection(mediaId: String) {
        setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        prepare()
        play()
    }

    /**
     * Sends [keyCode] as a real ACTION_MEDIA_BUTTON broadcast targeted at
     * YandexMusicWatch specifically (an explicit package, not a
     * system-wide broadcast) — delivered to its exported
     * androidx.media3.session.MediaButtonReceiver, which forwards it to
     * whichever MediaSession is current, exactly as a Bluetooth headset's
     * buttons would. Both DOWN and UP are sent, matching a real key press,
     * since a session's default key handling can treat a lone event as an
     * incomplete press for keys like PLAY_PAUSE. Only ever called with
     * KEYCODE_MEDIA_PLAY — see class doc for why.
     */
    private fun sendMediaButton(context: Context, keyCode: Int) {
        val appContext = context.applicationContext
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(PACKAGE_NAME)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))
            }
            try {
                appContext.sendBroadcast(intent)
            } catch (e: Exception) {
                // Most likely YandexMusicWatch isn't installed — nothing
                // more to do than log it, same as a slot's startActivity
                // failure.
                Log.e(TAG, "Media button broadcast failed", e)
            }
        }
    }

    /**
     * Sends [keyCode] via AudioManager.dispatchMediaKeyEvent() — the same
     * call a wired/Bluetooth headset button makes. See class doc for why
     * this reliably reaches YandexMusicWatch in practice.
     */
    private fun sendGenericMediaButton(context: Context, keyCode: Int) {
        val audioManager =
            context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            try {
                audioManager.dispatchMediaKeyEvent(KeyEvent(action, keyCode))
            } catch (e: Exception) {
                Log.e(TAG, "dispatchMediaKeyEvent failed", e)
            }
        }
    }

    private fun withController(context: Context, action: (MediaController) -> Unit) {
        val appContext = context.applicationContext
        val future: ListenableFuture<MediaController> = try {
            val token = SessionToken(appContext, ComponentName(PACKAGE_NAME, SERVICE_CLASS_NAME))
            MediaController.Builder(appContext, token).buildAsync()
        } catch (e: Exception) {
            // SessionToken's constructor resolves the component synchronously
            // and throws if YandexMusicWatch isn't installed at all — same
            // "nothing more to do than log it" handling as a connected
            // future that later fails to resolve.
            Log.e(TAG, "Could not reach YandexMusicWatch's player", e)
            return
        }
        future.addListener({
            try {
                action(future.get())
            } catch (e: Exception) {
                // Most likely YandexMusicWatch isn't installed, or its
                // service component changed — nothing more to do than log
                // it, same as a slot's startActivity failure.
                Log.e(TAG, "Command failed", e)
            } finally {
                mainHandler.postDelayed({ MediaController.releaseFuture(future) }, RELEASE_DELAY_MS)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }
}
