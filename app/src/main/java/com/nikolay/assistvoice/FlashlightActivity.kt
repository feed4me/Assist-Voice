package com.nikolay.assistvoice

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * The screen "flashlight": a plain white full-screen window at maximum
 * brightness, standing in for the system flashlight this ROM gives no app
 * access to. See VoicePhrases (PHRASE_FLASHLIGHT_ON/OFF) for the reserved
 * two-word voice commands that open and close it, and VoiceAccessibilityService
 * for how "выключи фонарик" is still recognized while this is on screen —
 * launching this Activity is deliberately in our own package, so it never
 * fires the foreground-package-changed handling that would otherwise tear
 * listening down when the person leaves the watch face.
 *
 * No layout resource on purpose: the requirement is not one element of UI,
 * so there is nothing here to declare beyond a single plain View.
 */
class FlashlightActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Registered before anything else so the window between this call and
        // the window actually going up — during which a stray "выключи
        // фонарик"/"включи фонарик" could otherwise find nothing listening —
        // is as small as it can be.
        FlashlightController.attach(close = { finish() })

        hideSystemBars()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            // Per-window brightness override; Android restores whatever
            // brightness applied before automatically once this window is
            // gone, so there is nothing to save/restore by hand here.
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }

        val white = View(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setOnClickListener { finish() }
        }
        setContentView(white)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onDestroy() {
        FlashlightController.detach()
        super.onDestroy()
    }
}
