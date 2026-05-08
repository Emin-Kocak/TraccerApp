package com.example.traccerapp

import android.app.Application
import android.util.Log
import androidx.work.*
import com.example.traccerapp.service.DailySyncWorker
import java.util.*
import java.util.concurrent.TimeUnit

class TraccerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleDailySync()
        scheduleStartupSync()
    }

    /**
     * Günde bir kez gece yarısından sonra çalışır.
     * AppAccessibilityService'in in-memory verilerini UsageStatsManager ile
     * reconcile eder (hatalı ölçümleri düzeltir).
     */
    private fun scheduleDailySync() {
        val now         = Calendar.getInstance()
        val nextMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)   // Gece yarısından 5 dk sonra
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)  // Bugün geçtiyse yarın
        }
        val initialDelayMs = nextMidnight.timeInMillis - now.timeInMillis

        val dailySyncRequest = PeriodicWorkRequestBuilder<DailySyncWorker>(
            repeatInterval = 1,
            repeatIntervalTimeUnit = TimeUnit.DAYS
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailySyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,  // Zaten varsa yeniden oluşturma
            dailySyncRequest
        )

        Log.d("TraccerApp", "📅 Daily sync zamanlandı — ilk çalışma: $initialDelayMs ms sonra")
    }

    /**
     * Uygulama her açıldığında tek seferlik hızlı senkronizasyon.
     * Cihaz yeniden başlatıldıktan sonra veya servis öldürüldükten sonra
     * veri kaybını telafi eder.
     */
    private fun scheduleStartupSync() {
        val startupSyncRequest = OneTimeWorkRequestBuilder<DailySyncWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS) // Servisler hazır olsun diye bekle
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            DailySyncWorker.STARTUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            startupSyncRequest
        )

        Log.d("TraccerApp", "🚀 Startup sync zamanlandı (30s sonra)")
    }
}
