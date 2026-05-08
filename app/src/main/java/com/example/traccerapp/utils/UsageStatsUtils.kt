package com.example.traccerapp.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.UsageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

object UsageStatsUtils {

    private const val TAG = "UsageStatsUtils"

    suspend fun fetchAndSaveUsageStats(context: Context): List<UsageLog> = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        
        Log.d(TAG, "===== fetchAndSaveUsageStats başlıyor =====")
        Log.d(TAG, "Zaman aralığı: $startOfDay - $now")
        
        val aggregationMap = mutableMapOf<String, Long>()
        
        // ─── 1. QueryEvents'ten detaylı event verileri al
        Log.d(TAG, "1. QueryEvents ile detaylı event'ler alınıyor...")
        try {
            val events = usageStatsManager.queryEvents(startOfDay, now)
            val openTimes = mutableMapOf<String, Long>()
            val event = UsageEvents.Event()
            var eventCount = 0
            
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                eventCount++
                
                // Sistem uygulamalarını filtrele
                if (!AppIconUtils.shouldTrack(context, pkg)) continue
                
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        openTimes[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val openMs = openTimes[pkg]
                        if (openMs != null) {
                            val durationMs = event.timeStamp - openMs
                            if (durationMs >= 1000L) {
                                aggregationMap[pkg] = (aggregationMap[pkg] ?: 0L) + durationMs
                            }
                            openTimes.remove(pkg)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Events işlendi: $eventCount toplam event, ${aggregationMap.size} trackable app")
            
            // Açık kalan uygulamalar
            val currentTime = System.currentTimeMillis()
            for ((pkg, openTimeMs) in openTimes) {
                val durationMs = currentTime - openTimeMs
                if (durationMs >= 1000L) {
                    aggregationMap[pkg] = (aggregationMap[pkg] ?: 0L) + durationMs
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "QueryEvents hatası", e)
        }
        
        // ─── 2. QueryUsageStats ile aggregate data al (başka kaynak olarak)
        Log.d(TAG, "2. QueryUsageStats ile aggregate data alınıyor...")
        try {
            val queryStart = startOfDay - (24 * 60 * 60 * 1000L)
            val statsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                queryStart,
                now
            )
            
            Log.d(TAG, "QueryUsageStats döndürülen: ${statsList?.size ?: 0} stats")
            
            if (statsList != null && statsList.isNotEmpty()) {
                for (usageStat in statsList) {
                    val packageName = usageStat.packageName
                    val duration = usageStat.totalTimeInForeground
                    
                    // Bugünün başından sonra kullanılan uygulamaları al
                    if (duration > 0 && usageStat.lastTimeUsed >= startOfDay && AppIconUtils.shouldTrack(context, packageName)) {
                        // Eğer queryEvents'ten veri yoksa, bu verileri de birleştir
                        if (packageName !in aggregationMap) {
                            aggregationMap[packageName] = duration
                            Log.d(TAG, "QueryUsageStats'tan eklendi: $packageName = $duration ms")
                        } else {
                            // queryEvents'te daha fazla veri varsa, onu kullan
                            Log.d(TAG, "Zaten queryEvents'te var: $packageName")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "QueryUsageStats hatası", e)
        }
        
        // ─── 3. Final logs oluştur
        Log.d(TAG, "3. Final UsageLog'lar oluşturuluyor...")
        val liveLogs = aggregationMap.mapNotNull { (packageName, totalDuration) ->
            if (totalDuration > 0L) {
                val appName = AppInfoUtils.getAppName(context, packageName)
                UsageLog(
                    packageName = packageName,
                    appName = appName,
                    date = startOfDay,
                    durationMs = totalDuration
                )
            } else null
        }
        
        Log.d(TAG, "SONUÇ: ${liveLogs.size} app ile veri hazır")
        liveLogs.forEach { log ->
            Log.d(TAG, "  - ${log.appName} (${log.packageName}): ${log.durationMs} ms")
        }
        
        // ─── 4. DB'ye kaydet
        Log.d(TAG, "4. DB'ye kaydediliyor...")
        if (liveLogs.isNotEmpty()) {
            try {
                val db = AppDatabase.getDatabase(context)
                db.appUsageDao().insertUsageLogs(liveLogs)
                Log.d(TAG, "✓ Başarıyla ${liveLogs.size} log kaydedildi")
            } catch (e: Exception) {
                Log.e(TAG, "✗ DB kayıt hatası", e)
            }
        } else {
            Log.w(TAG, "⚠ Kaydedilecek log yok!")
        }
        
        Log.d(TAG, "===== fetchAndSaveUsageStats bitti =====")
        return@withContext liveLogs.sortedByDescending { it.durationMs }
    }
}
