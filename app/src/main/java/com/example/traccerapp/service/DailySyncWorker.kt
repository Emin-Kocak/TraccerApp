package com.example.traccerapp.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.PhoneActivitySync
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

        /** OS event penceresi ~7 gün — PhoneActivitySync ile aynı kapsam. */
        private const val RECONCILE_DAYS = 7
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "▶ DailySyncWorker başladı")
        return@withContext try {
            val db = AppDatabase.getDatabase(appContext)
            val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val todayStart = getTodayStartMs()
            val now        = System.currentTimeMillis()
            val dayMs      = 24 * 60 * 60 * 1000L

            // ── 1-3. Son 7 günü gün gün reconcile et (madde 25) ────────────────
            // Eskiden yalnızca BUGÜN reconcile ediliyordu: servis dün kapalıysa dünün
            // verisi sonsuza dek eksik kalıyordu. OS ~7 gün event tuttuğu için
            // (PhoneActivitySync ile aynı pencere) geçmiş günler de doldurulabiliyor.
            for (dayOffset in RECONCILE_DAYS - 1 downTo 0) {
                val dayStart = todayStart - dayOffset * dayMs
                val dayEnd   = minOf(dayStart + dayMs, now)

                val aggregatedMs = buildAggregationMap(usageStatsManager, dayStart, dayEnd)
                if (aggregatedMs.isEmpty()) continue // OS penceresi dışı ya da veri yok

                val existingLogs = db.appUsageDao().getUsageLogsForDate(dayStart).first()
                    .associateBy { it.packageName }

                // Reconciliation: DB'deki > UsageStats ise DB'yi koru (daha doğru),
                //                 DB'deki < UsageStats ise UsageStats değerini yaz
                val reconciledLogs = aggregatedMs.map { (pkg, sysMs) ->
                    val dbMs    = existingLogs[pkg]?.durationMs ?: 0L
                    val finalMs = maxOf(dbMs, sysMs)
                    UsageLog(
                        packageName = pkg,
                        appName     = AppInfoUtils.getAppName(appContext, pkg),
                        date        = dayStart,
                        durationMs  = finalMs
                    )
                }

                if (reconciledLogs.isNotEmpty()) {
                    db.appUsageDao().upsertDurations(reconciledLogs)
                    Log.d(TAG, "✅ Gün -$dayOffset: ${reconciledLogs.size} kayıt reconcile edildi")
                }
            }

            // ── 4. Unlock/oturum verisini OS'ten senkronize et (tek yazar, madde 24) ──
            PhoneActivitySync.sync(appContext, db.appUsageDao())

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

            // Hâlâ açık görünen uygulamaları pencere sonuna kadar say
            // (bugün için "şimdi", geçmiş günler için o günün sonu — madde 25)
            openMap.forEach { (pkg, openMs) ->
                val elapsed = endMs - openMs
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
