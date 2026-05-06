package com.example.traccerapp.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.traccerapp.MainActivity
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.FirestoreRepository
import com.example.traccerapp.utils.UsageStatsUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TrackingService : Service() {

    private val CHANNEL_ID = "TrackingServiceChannel"
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // ✅ FIX: 30 saniye interval'e düşür (canlı hissi için)
    private val checkInterval = 30000L // 30 seconds

    private lateinit var handler: Handler
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var db: AppDatabase
    private lateinit var firestoreRepo: FirestoreRepository

    private val trackingRunnable = object : Runnable {
        override fun run() {
            checkCurrentAppUsage()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        db = AppDatabase.getDatabase(this)
        firestoreRepo = FirestoreRepository(this)
        createNotificationChannel()
        startForeground(1, createNotification())
        handler.post(trackingRunnable) // Hemen başla
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun checkCurrentAppUsage() {
        serviceScope.launch {
            try {
                UsageStatsUtils.fetchAndSaveUsageStats(this@TrackingService)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(trackingRunnable)
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traccer Aktif")
            .setContentText("Uygulama kullanımı takip ediliyor...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Tracking Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}