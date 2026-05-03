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
import com.example.traccerapp.data.UsageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*

class TrackingService : Service() {

    private val CHANNEL_ID = "TrackingServiceChannel"
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 1000L // Check every 1 second

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var db: AppDatabase

    private val trackingRunnable = object : Runnable {
        override fun run() {
            checkCurrentAppUsage()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        db = AppDatabase.getDatabase(this)
        createNotificationChannel()
        startForeground(1, createNotification())
        handler.post(trackingRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun checkCurrentAppUsage() {
        val endTime = System.currentTimeMillis()
        val startTime = getStartOfDay(endTime)

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        if (stats != null) {
            serviceScope.launch {
                val logs = mutableListOf<UsageLog>()
                for (usageStats in stats) {
                    if (usageStats.totalTimeInForeground > 0) {
                        logs.add(
                            UsageLog(
                                packageName = usageStats.packageName,
                                date = startTime,
                                durationMs = usageStats.totalTimeInForeground
                            )
                        )
                    }
                }
                if (logs.isNotEmpty()) {
                    db.appUsageDao().insertUsageLogs(logs)
                }
            }
        }
    }

    private fun getStartOfDay(time: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traccer is active")
            .setContentText("Monitoring your digital balance...")
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
