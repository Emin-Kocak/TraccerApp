package com.example.traccerapp.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.traccerapp.MainActivity

/**
 * Foreground Service — Yalnızca kalıcı bildirimi yönetir.
 *
 * Takip ve engelleme mantığı tamamen AppAccessibilityService'e taşındı.
 * Bu servis sadece Android'in "foreground service" zorunluluğunu karşılamak
 * ve sistemin uygulamayı arka planda öldürmesini önlemek için çalışır.
 */
class TrackingService : Service() {

    private val CHANNEL_ID       = "TrackingServiceChannel"
    private val NOTIFICATION_ID  = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traccer Aktif")
            .setContentText("Uygulama kullanımı izleniyor")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Traccer Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Traccer arka planda çalışıyor"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}