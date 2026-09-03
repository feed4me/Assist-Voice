package com.nikolay.assistvoice

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Owns the overlay window that shows the microphone indicator while listening.
 *
 * This window is not only cosmetic. On a static (non-animated) watch face this
 * ROM wakes the display by showing a screenshot held by the co-processor
 * without the Android watch-face service ever becoming visible — no real frame
 * gets composited, and Huawei's audio stack mutes recording in that state.
 * Adding a genuinely visible window forces the application processor to
 * composite and present a frame, which is what gets recording out of that
 * muted state; a fully transparent 1x1 window does not have that effect.
 *
 * If the person turns the indicator off in settings, an invisible 1x1 window
 * is shown instead, so this effect survives the icon being hidden.
 */
class MicIndicatorOverlay(private val context: Context) {

    companion object {
        private const val TAG = "MicIndicatorOverlay"
    }

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var indicator: MicIndicatorView? = null
    private var shownParams: OverlaySettings.Params? = null

    val isShowing: Boolean get() = view != null

    /**
     * Shows (or reconfigures) the overlay. Safe to call repeatedly — if the
     * settings changed since the window went up, it is rebuilt.
     *
     * Returns false when the overlay could not be shown, which on this ROM
     * means the SYSTEM_ALERT_WINDOW permission is missing.
     */
    fun show(params: OverlaySettings.Params, state: MicIndicatorView.State): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "No overlay permission — cannot show the indicator")
            return false
        }

        // A size or position change can't be applied to an existing window
        // without new LayoutParams, and switching between icon and 1x1 modes
        // swaps the view itself, so rebuild when the geometry moved. Colour is
        // explicitly not part of that test — it repaints in place, and tearing
        // the window down per swatch would make the palette flicker.
        if (view != null && shownParams?.sameGeometryAs(params) != true) hide()

        if (view == null) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.e(TAG, "No WindowManager")
                return false
            }
            val newView: View
            val sidePx: Int
            if (params.enabled) {
                val mic = MicIndicatorView(context)
                mic.setColors(params.colorInit, params.colorActive)
                mic.state = state
                indicator = mic
                newView = mic
                sidePx = dpToPx(params.sizeDp)
            } else {
                indicator = null
                newView = View(context)
                sidePx = 1
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val layout = WindowManager.LayoutParams(
                sidePx, sidePx, type,
                // Never take focus or touches: the watch face underneath has to
                // keep working exactly as before.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Gravity.CENTER makes x/y offsets from the middle of the
                // screen, which is exactly the coordinate system the settings
                // page presents. y grows downward.
                gravity = Gravity.CENTER
                x = if (params.enabled) dpToPx(params.offsetXDp) else 0
                y = if (params.enabled) dpToPx(params.offsetYDp) else 0
            }

            try {
                wm.addView(newView, layout)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay", e)
                indicator = null
                return false
            }
            windowManager = wm
            view = newView
            shownParams = params
            Log.i(TAG, "Overlay shown (icon=${params.enabled}, size=${params.sizeDp}dp)")
        }

        shownParams = params
        indicator?.setColors(params.colorInit, params.colorActive)
        indicator?.state = state
        return true
    }

    /** Updates the colour without touching the window. Cheap, no relayout. */
    fun setState(state: MicIndicatorView.State) {
        indicator?.state = state
    }

    fun hide() {
        val current = view ?: return
        view = null
        indicator = null
        shownParams = null
        try {
            windowManager?.removeView(current)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay", e)
        }
        windowManager = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
