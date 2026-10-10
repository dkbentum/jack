package com.example.jack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps Jack's process alive 24/7 so [JackCore] (the ESP32 connection, alerts and battery
 * automation) keeps running when the screen is closed. If Android kills the process anyway,
 * START_STICKY makes the system restart this service, which restarts JackCore and reconnects.
 */
class Esp32BackgroundService : Service() {

    companion object {
        private const val TAG = "Esp32BgService"
        private const val CHANNEL_ID = "esp32_bg_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 25 * 60 * 60 * 1000L   // 25 h…
        private const val WAKE_LOCK_RENEW_MS = 24 * 60 * 60 * 1000L     // …renewed every 24 h

        /** True while the service is running (shown in Settings diagnostics). */
        @Volatile
        var isRunning = false
            private set

        fun startService(context: Context) {
            try {
                val intent = Intent(context, Esp32BackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start background service: ${e.localizedMessage}")
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, Esp32BackgroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        // Must be on screen within 5 s of startForegroundService().
        startInForeground(buildNotification(JackCore.statusText.value))

        // Start (or attach to) the always-on core. No-op if MainActivity already started it.
        JackCore.start(this)
        JackCore.addLog("🛡️ Background service running — ESP32 connection stays up when the screen is closed.")

        keepCpuAwake()

        // Mirror the live connection status in the notification.
        serviceScope.launch {
            JackCore.statusText.collect { text ->
                try {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
                } catch (_: Exception) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(buildNotification(JackCore.statusText.value))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        releaseWakeLock()
        // Note: JackCore keeps running while the app is open; only the 24/7 guarantee is lost.
        try { JackCore.addLog("⏸️ Background service stopped — connection may drop when the screen is closed.") } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } catch (e: Exception) {
                    // Android 14+ refuses "connectedDevice" until Bluetooth/USB permission is granted.
                    Log.w(TAG, "connectedDevice type refused (${e.localizedMessage}); using dataSync.")
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.localizedMessage}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jack 24/7 Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Jack connected to the ESP32 in the background 24/7"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jack is running 24/7")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Partial wake lock so BLE/USB keep working with the screen off; renewed so it never lapses. */
    private fun keepCpuAwake() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jack::Esp32BackgroundWakeLock"
            ).apply { setReferenceCounted(false) }

            serviceScope.launch {
                while (isActive) {
                    wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                    delay(WAKE_LOCK_RENEW_MS)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock failed: ${e.localizedMessage}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
