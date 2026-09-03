package com.nikolay.assistvoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Minimal foreground service whose only job is to keep this app's process
 * alive for however long an ADB update install takes (see
 * AdbUpdateInstaller) — specifically to survive the moment the system's own
 * "Allow debugging" confirmation screen takes over the display and pushes
 * MainActivity to the background. This firmware (Huawei EMUI) kills
 * backgrounded processes aggressively; without this, the background thread
 * AdbUpdateInstaller runs its work on can simply vanish mid-install while
 * the person is still looking at that system screen.
 *
 * Started right before each AdbUpdateInstaller attempt and stopped
 * unconditionally once that attempt's Result is ready — never runs outside
 * that window. Uses its own notification, channel and id rather than
 * VoiceNotifications' STATUS_NOTIFICATION_ID, which has a strict
 * single-owner handoff rule between MicForegroundService and
 * VoiceAccessibilityService that this has nothing to do with.
 */
class AdbInstallForegroundService : Service() {

    companion object {
        private const val TAG = "AdbInstallFgService"
        private const val CHANNEL_ID = "adb_install_channel"
        private const val NOTIFICATION_ID = 3

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AdbInstallForegroundService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService refused", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AdbInstallForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stopService failed", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Установка обновления", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentText("Устанавливаю обновление…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
        // Lifecycle is driven entirely by AdbUpdateInstaller — a sticky
        // restart would just resurrect this with no install behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
        super.onDestroy()
    }
}
