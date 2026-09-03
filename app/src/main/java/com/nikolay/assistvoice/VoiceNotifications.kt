package com.nikolay.assistvoice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Single owner of this app's notifications.
 *
 * IMPORTANT ownership rule: exactly one of {MicForegroundService,
 * VoiceAccessibilityService} may hold the status notification id at any
 * moment, and only that owner may write to it.
 *
 *   - While MicForegroundService is running, it owns id STATUS_NOTIFICATION_ID
 *     via startForeground(). Only it calls postStatus() during that window.
 *   - The instant it stops (onDestroy), it calls
 *     VoiceNotifications.releaseOwnership() — which cancels the notification
 *     outright rather than detaching it — and control passes back to
 *     VoiceAccessibilityService.
 *
 * Without this handoff, both sides posting through a shared de-duplication
 * cache can leave the bar notification frozen on stale text (a string one
 * side already "used" gets silently skipped when the other posts it) or
 * showing a stale timestamp (a detached notification's `when` never
 * refreshes). Cancelling on release, and gating who may post when, avoids
 * both.
 */
object VoiceNotifications {

    private const val TAG = "VoiceNotifications"

    const val STATUS_CHANNEL_ID = "voice_listener_channel"
    const val LAUNCH_CHANNEL_ID = "voice_launch_channel"
    const val STATUS_NOTIFICATION_ID = 1
    const val LAUNCH_NOTIFICATION_ID = 2

    private var channelsCreated = false
    private var lastStatusText: String? = null

    fun ensureChannels(context: Context) {
        if (channelsCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL_ID,
                    "Голосовой запуск ассистента",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
            // The full-screen-intent channel needs higher importance —
            // IMPORTANCE_MIN notifications are not allowed to carry a
            // full-screen intent on some Android versions.
            manager.createNotificationChannel(
                NotificationChannel(
                    LAUNCH_CHANNEL_ID,
                    "Запуск по голосовой команде",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        channelsCreated = true
    }

    /**
     * Always builds fresh (no cached Builder): NotificationCompat.Builder is
     * cheap, and setWhen() is set explicitly on every build so the bar always
     * shows the actual post time rather than a stale one.
     */
    fun buildStatus(context: Context, statusText: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            // No setContentTitle(): System UI already labels the notification
            // with the app's own name and icon next to this text, so a title
            // repeating "Assist Voice" here just showed the name twice.
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /**
     * Posts/updates the status notification. Only VoiceAccessibilityService
     * should call this while MicForegroundService isn't running — see the
     * class doc. No-ops when the text hasn't changed (still worth keeping: the
     * accessibility service posts several near-duplicate status strings during
     * a retry loop) or when POST_NOTIFICATIONS isn't granted.
     */
    fun postStatus(context: Context, statusText: String) {
        if (statusText == lastStatusText) return
        lastStatusText = statusText
        if (!canPost(context)) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(STATUS_NOTIFICATION_ID, buildStatus(context, statusText))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post status notification", e)
        }
    }

    /** Bypasses the de-dup cache — used by the foreground service, which owns
     * the id exclusively while running and must always show current text/time. */
    fun postStatusForce(context: Context, statusText: String) {
        lastStatusText = statusText
        if (!canPost(context)) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(STATUS_NOTIFICATION_ID, buildStatus(context, statusText))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post status notification", e)
        }
    }

    /**
     * Called by whichever side is giving up ownership of the status
     * notification id, so the next postStatus() from the other side isn't
     * swallowed by stale cached text and — for the foreground service — so the
     * notification doesn't linger as an orphaned, never-updated bar entry.
     */
    fun releaseOwnership(context: Context) {
        lastStatusText = null
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(STATUS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel status notification", e)
        }
    }

    /**
     * Full-screen-intent fallback used when the app can't start an activity
     * directly. `requestCode` must be unique per target, otherwise two slots
     * launching different components fight over the same PendingIntent record.
     */
    fun postLaunchFallback(
        context: Context,
        targetIntent: Intent,
        notificationText: String,
        requestCode: Int
    ) {
        ensureChannels(context)
        if (!canPost(context)) {
            Log.e(TAG, "POST_NOTIFICATIONS not granted — launch fallback unavailable")
            return
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, LAUNCH_CHANNEL_ID)
            .setContentTitle("Assist Voice")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(LAUNCH_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post full-screen launch notification", e)
        }
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
