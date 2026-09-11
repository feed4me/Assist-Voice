package com.nikolay.assistvoice

/**
 * Bridges VoiceAccessibilityService (which owns recognition) and
 * FlashlightActivity (which owns the white full-screen window) — the same
 * kind of static hand-off MicForegroundService.onForegroundReady already uses
 * for two components that otherwise have no reference to each other.
 *
 * [isShowing] is read on the decoder thread (VoiceAccessibilityService.
 * handleHypothesis, on every hypothesis while "выключи фонарик" is being
 * matched) and written on the main thread only, hence @Volatile.
 */
object FlashlightController {

    @Volatile
    var isShowing = false
        private set

    private var closeAction: (() -> Unit)? = null

    /**
     * Called by FlashlightActivity as early as onCreate(), before its window
     * is even shown, so the gap in which a second "включи фонарик" or a
     * "выключи фонарик" spoken immediately after launch would find nothing
     * registered stays as small as possible.
     */
    fun attach(close: () -> Unit) {
        isShowing = true
        closeAction = close
    }

    /** Called by FlashlightActivity's onDestroy — the window is actually gone. */
    fun detach() {
        isShowing = false
        closeAction = null
    }

    /** "выключи фонарик" was recognized. No-op if nothing is showing. */
    fun requestClose() {
        closeAction?.invoke()
    }
}
