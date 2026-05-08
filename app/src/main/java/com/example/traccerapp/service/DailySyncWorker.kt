package com.example.traccerapp.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.utils.AppIconUtils
import com.example.traccerapp.utils.AppInfoUtils
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Günlük Senkronizasyon Worker (WorkManager)
 *
 * Rol: UsageStatsManager'dan geçmiş verileri çekip Room'daki kayıtları
 *      düzeltici olarak (reconciliation) günceller.
 *
 * Çalışma zamanı:
 *  - Uygulama ilk açıldığında (tek seferlik)
 *  - Her gece yarısından sonra (periyodik, 24s)
 *
 * NOT: Bu worker anlık takibin yerini almaz. AppAccessibilityService
 *      event-driven olarak gerçek zamanlı süre tutar. Bu worker yalnızca
 *      hatalı ölçümleri gece yarısı düzeltir ve yeni güne temiz başlar.
 */
class DailySyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG         = "DailySyncWorker"
        const val WORK_NAME   = "traccer_daily_sync"
        const val STARTUP_WORK_NAME = "traccer_startup_sync"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "▶ DailySyncWorker başladı")
        return@withContext try {
            val db = AppDatabase.getDatabase(appContext)
            val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val todayStart = getTodayStartMs()
            val now        = System.currentTimeMillis()

            // ── 1. UsageStatsManager'dan bugünün event verilerini çek ─────────
            val aggregatedMs = buildAggregationMap(usageStatsManager, todayStart, now)
            Log.d(TAG, "UsageStats: ${aggregatedMs.size} uygulama bulundu")

            // ── 2. Mevcut DB kayıtlarını al ───────────────────────────────────
            val existingLogs = db.appUsageDao().getUsageLogsForDate(todayStart).first()
                .associateBy { it.packageName }

            // ── 3. Reconciliation: DB'deki > UsageStats ise DB'yi koru (daha doğru)
            //                       DB'deki < UsageStats ise UsageStats değerini yaz
            val reconciledLogs = aggregatedMs.map { (pkg, sysMs) ->
                val dbMs      = existingLogs[pkg]?.durationMs ?: 0L
                val finalMs   = maxOf(dbMs, sysMs)  // Hangisi büyükse onu kullan
                val appName   = AppInfoUtils.getAppName(appContext, pkg)
                UsageLog(
                    packageName = pkg,
                    appName     = appName,
                    date        = todayStart,
                    durationMs  = finalMs
                )
            }

            if (reconciledLogs.isNotEmpty()) {
                db.appUsageDao().upsertDurations(reconciledLogs)
                Log.d(TAG, "✅ ${reconciledLogs.size} kayıt reconcile edildi")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ DailySyncWorker hatası", e)
            Result.retry()
        }
    }

    /**
     * UsageEvents üzerinden ACTIVITY_RESUMED / ACTIVITY_PAUSED çiftlerini eşleştirir
     * ve her paket için toplam ön plan süresini hesaplar.
     */
    private fun buildAggregationMap(
        usm: UsageStatsManager,
        startMs: Long,
        endMs: Long
    ): Map<String, Long> {
        val aggregation = mutableMapOf<String, Long>()
        return try {
            val events   = usm.queryEvents(startMs, endMs)
            val openMap  = mutableMapOf<String, Long>()
            val event    = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (!AppIconUtils.shouldTrack(appContext, pkg)) continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> openMap[pkg] = event.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED  -> {
                        val openMs = openMap.remove(pkg) ?: continue
                        val elapsed = event.timeStamp - openMs
                        if (elapsed >= 1_000L) {
                            aggregation[pkg] = (aggregation[pkg] ?: 0L) + elapsed
                        }
                    }
                }
            }

            // Hâlâ açık olan uygulamaları şu ana kadar say
            val now = System.currentTimeMillis()
            openMap.forEach { (pkg, openMs) ->
                val elapsed = now - openMs
                if (elapsed >= 1_000L) {
                    aggregation[pkg] = (aggregation[pkg] ?: 0L) + elapsed
                }
            }

            aggregation
        } catch (e: Exception) {
            Log.e(TAG, "UsageEvents sorgu hatası", e)
            aggregation
        }
    }

    private fun getTodayStartMs(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
