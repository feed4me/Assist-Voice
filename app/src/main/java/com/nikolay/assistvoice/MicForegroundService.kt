package com.nikolay.assistvoice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * A minimal foreground service whose only job is to hold the *process* in a
 * foreground state with the `microphone` service type while the app is
 * recording.
 *
 * Why this exists at all: RECORD_AUDIO is a "while-in-use" permission. An app
 * only actually gets microphone samples when it has a visible Activity or a
 * running foreground service of type microphone. An AccessibilityService gives
 * neither.
 *
 * The capture pipeline itself stays in VoiceAccessibilityService (which is the
 * kill-resistant host). While-in-use state is per-process, not per-service, so
 * simply having this service in the foreground is enough for the recorder over
 * there to get real audio.
 *
 * Known hardware limitation: on at least one static (non-animated) watch face
 * this alone is not enough — the ROM appears to gate while-in-use audio on
 * whether a surface is actively rendering, not just on the documented
 * foreground-service rules, and a static face never renders one. An animated
 * watch face does not have this problem. There is no in-app workaround: the
 * app cannot detect or change the watch face.
 *
 * This service owns the status notification (STATUS_NOTIFICATION_ID) for the
 * entire time it is running, and only it writes to that id during that window
 * — see VoiceNotifications' class doc for the full handoff rule. It hands
 * ownership back by cancelling the notification in onDestroy() rather than
 * detaching it, so the accessibility service's next status update is
 * guaranteed to actually show up instead of landing on a de-dup cache primed
 * with stale text.
 */
class MicForegroundService : Service() {

    companion object {
        private const val TAG = "MicForegroundService"
        private const val ACTION_START = "com.nikolay.assistvoice.action.MIC_START"
        private const val EXTRA_STATUS = "status"

        /** True once startForeground() has actually succeeded. */
        @Volatile
        var isForeground: Boolean = false
            private set

        /**
         * Invoked on the main thread right after the process reaches the
         * foreground state. VoiceAccessibilityService uses this to start
         * capture only once the mic can realistically deliver audio, instead
         * of racing the service start.
         */
        @Volatile
        var onForegroundReady: (() -> Unit)? = null

        fun start(context: Context, statusText: String) {
            val intent = Intent(context, MicForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STATUS, statusText)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // API 31+ can refuse a background FGS start. This app holds
                // SYSTEM_ALERT_WINDOW, which is one of the documented
                // exemptions, but ROMs vary — fall through and let the caller
                // try to capture anyway.
                Log.e(TAG, "startForegroundService refused", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MicForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stopService failed", e)
            }
        }

        /**
         * Updates the status text while this service owns the notification.
         * No-op if the service isn't currently in the foreground state — the
         * caller (VoiceAccessibilityService) should be posting through
         * VoiceNotifications.postStatus() itself in that case.
         */
        fun updateStatus(context: Context, statusText: String) {
            if (!isForeground) return
            VoiceNotifications.postStatusForce(context, statusText)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Слушает…"
        VoiceNotifications.ensureChannels(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                VoiceNotifications.STATUS_NOTIFICATION_ID,
                VoiceNotifications.buildStatus(this, statusText),
                type
            )
            isForeground = true
            onForegroundReady?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            isForeground = false
            stopSelf()
        }
        // Lifecycle is driven entirely by VoiceAccessibilityService (screen
        // on/off, enabled slots). A sticky restart would just resurrect the
        // service with no capture behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForeground = false
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
        // Explicit cancel + cache reset, not detach: ownership of the status
        // notification id is handed back to VoiceAccessibilityService here.
        VoiceNotifications.releaseOwnership(this)
        super.onDestroy()
    }
}
