package com.nikolay.assistvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A small self-dismissing popup card, centered on screen, reporting one
 * smart-home voice command's result (done / error / no response).
 *
 * Not a Toast or a notification: a plain Toast from this service is exactly
 * the kind of background window this ROM won't reliably composite while
 * sitting on the watch face (see MicIndicatorOverlay's doc for the same
 * quirk, there needing a visible window just to un-mute recording), and a
 * notification lands in the shade/as a banner rather than as a popup on
 * screen. This reuses the same real-window trick as the mic indicator
 * instead — see OverlayWindows — since a genuinely visible window is what
 * this ROM actually shows regardless of whether an Activity of ours is in
 * front.
 */
class SmartHomeResultOverlay(private val context: Context) {

    companion object {
        private const val TAG = "SmartHomeResultOverlay"
        private const val DISMISS_DELAY_MS = 2500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: View? = null
    private val dismissRunnable = Runnable { hide() }

    fun show(message: String) {
        // Replace whatever is currently showing rather than stacking windows.
        hide()

        val card = LayoutInflater.from(context)
            .inflate(R.layout.overlay_smart_home_result, null) as TextView
        card.text = message

        val wm = OverlayWindows.add(
            context, card,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER, x = 0, y = 0
        )
        if (wm == null) {
            Log.e(TAG, "Failed to add smart-home result popup")
            return
        }
        windowManager = wm
        view = card
        handler.postDelayed(dismissRunnable, DISMISS_DELAY_MS)
    }

    fun hide() {
        handler.removeCallbacks(dismissRunnable)
        val current = view ?: return
        view = null
        try {
            windowManager?.removeView(current)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove smart-home result popup", e)
        }
        windowManager = null
    }
}
