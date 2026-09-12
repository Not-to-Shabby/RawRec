package dev.rawrec.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.rawrec.app.util.AppLog

/**
 * Foreground service protecting active RAW recordings against process freezing
 * and Linux cgroup demotion on Android 14+ (API 34/35) and Xiaomi HyperOS.
 *
 * Enforces foregroundServiceType="camera" to ensure continuous background DMA
 * stream delivery and holds a partial wake lock to prevent CPU sleep during long takes.
 */
class CaptureForegroundService : Service() {

    companion object {
        private const val TAG = "RawRec-FGS"
        private const val CHANNEL_ID = "rawrec_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 60 minutes safety

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, CaptureForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
                AppLog.i(TAG, "CaptureForegroundService started")
            }.onFailure { AppLog.e(TAG, "Failed to start CaptureForegroundService", it) }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, CaptureForegroundService::class.java)
                context.stopService(intent)
                AppLog.i(TAG, "CaptureForegroundService stopped")
            }.onFailure { AppLog.e(TAG, "Failed to stop CaptureForegroundService", it) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RAW Capture Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for active RAW recording sessions"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RawRec Cinema Master")
            .setContentText("RAW sensor video capture in progress")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawRec::CaptureWakeLock").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            AppLog.i(TAG, "Partial wake lock acquired")
        }.onFailure { AppLog.w(TAG, "Failed to acquire wake lock: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            AppLog.i(TAG, "Partial wake lock released")
        }.onFailure { AppLog.w(TAG, "Failed to release wake lock: ${it.message}") }
    }
}
