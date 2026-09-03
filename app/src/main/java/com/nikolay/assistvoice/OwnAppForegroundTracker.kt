package com.nikolay.assistvoice

/**
 * Tracks whether any Activity of this app (MainActivity, SlotEditActivity,
 * PickerActivity) is currently started/visible.
 *
 * Why this exists: VoiceAccessibilityService needs to tell "the on-screen
 * keyboard opened while our own Settings screen is in front" apart from
 * "the on-screen keyboard opened inside some other app" — see
 * onAccessibilityEvent's isInputMethodWindow() branch. Window-state events
 * from our own package are always ignored there by design (otherwise just
 * opening Settings would read as leaving the watch face), which means the
 * accessibility-event stream alone has no way to distinguish those two
 * cases. This is a second, independent, in-process signal — a plain flag
 * flipped by each Activity's own onStart/onStop — nothing to do with
 * accessibility events or any other app.
 *
 * Only ignoring the keyboard's own window-state event while THIS is true
 * matters: for the keyboard opening inside some other app, the ignore does
 * not apply and that event is handled exactly as it always was (treated as
 * "not the watch face", listening stays off) — no behaviour change there.
 *
 * A counter rather than a plain boolean because one Activity's onStart can
 * run before the previous one's onStop during a same-app transition (e.g.
 * MainActivity -> SlotEditActivity) — isForeground only needs to say "at
 * least one of our Activities is visible right now". A brief window around
 * such a transition where the count is momentarily wrong (old one already
 * stopped, new one not yet started) is possible in principle but has no
 * real consequence here: it would just mean a keyboard opened in that exact
 * instant is treated the same as one in another app, which is harmless,
 * not the bug this exists to fix.
 */
object OwnAppForegroundTracker {
    @Volatile
    private var startedCount = 0

    val isForeground: Boolean
        get() = startedCount > 0

    fun onActivityStarted() {
        startedCount++
    }

    fun onActivityStopped() {
        if (startedCount > 0) startedCount--
    }
}
