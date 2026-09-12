package com.nikolay.assistvoice

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Shared logic for adding an overlay window from VoiceAccessibilityService —
 * used by both MicIndicatorOverlay and SmartHomeResultOverlay.
 *
 * Prefers TYPE_ACCESSIBILITY_OVERLAY: this window type is authorized through
 * the calling process hosting an active, bound AccessibilityService (which
 * VoiceAccessibilityService always is while any of this runs) rather than
 * through the SYSTEM_ALERT_WINDOW permission, so it needs no permission at
 * all and — unlike TYPE_APPLICATION_OVERLAY — doesn't trigger Android's
 * "displayed over other apps" warning notification, which isn't tracked for
 * this window type.
 *
 * Falls back to the old SYSTEM_ALERT_WINDOW-gated type if that somehow fails
 * on a given ROM, so a difference in how some OEM build treats accessibility
 * overlay windows can't silently break the mic indicator (which this app's
 * recording depends on — see MicIndicatorOverlay's doc) or the smart-home
 * result popup.
 */
object OverlayWindows {

    private const val TAG = "OverlayWindows"

    /** Adds [view] as an overlay window and returns the WindowManager used
     * to add it (so the caller removes it from that same instance later),
     * or null if neither window type could be added. */
    fun add(
        context: Context,
        view: View,
        widthPx: Int,
        heightPx: Int,
        gravity: Int,
        x: Int,
        y: Int
    ): WindowManager? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.e(TAG, "No WindowManager")
            return null
        }

        fun layoutFor(type: Int) = WindowManager.LayoutParams(
            widthPx, heightPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }

        try {
            wm.addView(view, layoutFor(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
            return wm
        } catch (e: Exception) {
            Log.e(TAG, "TYPE_ACCESSIBILITY_OVERLAY failed, falling back to SYSTEM_ALERT_WINDOW", e)
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "No overlay permission either — cannot show overlay")
            return null
        }
        val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return try {
            wm.addView(view, layoutFor(fallbackType))
            wm
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window (fallback type too)", e)
            null
        }
    }
}
