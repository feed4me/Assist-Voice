package com.nikolay.assistvoice

/**
 * Fired once a smart-home voice command's network result is a confirmed
 * success — never on failure/timeout, since a failed command's real
 * on/off state is unknown, so nothing here should assume it changed.
 *
 * VoiceAccessibilityService and every Activity in this app share one
 * process (see ModelHolder's doc for the same reasoning), and both run on
 * the main thread — the service's own network callbacks already hop back
 * to it (see YandexIotClient's doc) — so a plain in-memory callback is
 * enough here; no broadcast or cross-process mechanism is needed.
 *
 * Only one listener at a time on purpose: this exists purely so whichever
 * of SmartHomeDeviceListActivity/SmartHomeGroupListActivity is actually
 * on screen right now can patch just the one row that changed (see
 * DeviceRowAdapter/GroupRowAdapter.updateState()) instead of re-fetching
 * the whole list — a screen that isn't currently visible has no row on
 * screen to patch, so it has nothing to gain from registering while
 * merely backgrounded. Registered in onStart(), unregistered in onStop().
 */
object SmartHomeCommandEvents {

    fun interface Listener {
        fun onCommandSucceeded(deviceId: String, targetType: String, newValue: Boolean)
    }

    @Volatile
    private var listener: Listener? = null

    fun register(listener: Listener) {
        this.listener = listener
    }

    /** No-ops if [listener] isn't the currently registered one — e.g. a
     * newer screen already registered itself before this older one's
     * onStop() runs, during the brief overlap Android allows between the
     * two. Never clear a registration that isn't this caller's own. */
    fun unregister(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun notifySucceeded(deviceId: String, targetType: String, newValue: Boolean) {
        listener?.onCommandSucceeded(deviceId, targetType, newValue)
    }
}
