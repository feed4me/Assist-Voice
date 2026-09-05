package com.nikolay.assistvoice

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * specifically to support being driven this way) — the same protocol
 * Android Auto or a Bluetooth headset's play button uses to control any
 * third-party player, no permission or prior agreement between the two
 * apps needed beyond both being installed.
 *
 * Deliberately does NOT keep a MediaController held open between commands:
 * that would pin YandexMusicWatch's service alive indefinitely just because
 * this app is running, defeating its own on-demand lifecycle (see that
 * app's MusicPlaybackService doc — Media3 starts/stops it by itself,
 * nothing is meant to hold it open otherwise). Each command connects fresh,
 * fires, and lets go — the same one-shot shape as a headset button press.
 * A fresh connection also self-heals if YandexMusicWatch's process was
 * updated or restarted since the last command.
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

    private val mainHandler = Handler(Looper.getMainLooper())

    /** "включи волну" */
    fun playWave(context: Context) = withController(context) { it.playSection("wave") }

    /** "включи любимую музыку" */
    fun playLikes(context: Context) = withController(context) { it.playSection("likes") }

    /**
     * "включи музыку" — no section requested, so if YandexMusicWatch's
     * session already has a queue this just resumes it, and if it doesn't
     * (service wasn't running) Media3 calls that app's own
     * onPlaybackResumption(), which restores its last saved queue/position
     * or falls back to the wave. Either way this app doesn't need to know
     * which case applies.
     */
    fun resume(context: Context) = withController(context) { controller ->
        controller.prepare()
        controller.play()
    }

    /** "выключи музыку" */
    fun pause(context: Context) = withController(context) { it.pause() }

    /** "следующий трек" */
    fun next(context: Context) = withController(context) { it.seekToNext() }

    /** "предыдущий трек" */
    fun previous(context: Context) = withController(context) { it.seekToPrevious() }

    private fun MediaController.playSection(mediaId: String) {
        setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        prepare()
        play()
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
