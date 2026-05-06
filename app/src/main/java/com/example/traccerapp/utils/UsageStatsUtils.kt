package com.example.traccerapp.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.FirestoreRepository
import com.example.traccerapp.data.UsageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

object UsageStatsUtils {

    private const val TAG = "UsageStatsUtils"

    /**
     * Fetches current day's usage stats.
     * Uses an inclusive query window and sums up all fragments for accuracy.
     */
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
        
        // Query from 24h before start of day to ensure we catch buckets crossing the midnight boundary
        val queryStart = startOfDay - (24 * 60 * 60 * 1000L)

        val statsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            queryStart,
            now
        )

        if (statsList.isNullOrEmpty()) {
            Log.w(TAG, "No usage stats returned from system.")
            return@withContext emptyList<UsageLog>()
        }

        val aggregationMap = mutableMapOf<String, Long>()

        for (usageStat in statsList) {
            val packageName = usageStat.packageName
            val duration = usageStat.totalTimeInForeground

            // Only consider stats that were active today or are relevant
            if (duration > 0 && usageStat.lastTimeUsed >= startOfDay) {
                aggregationMap[packageName] = (aggregationMap[packageName] ?: 0L) + duration
            }
        }

        val liveLogs = aggregationMap.mapNotNull { (packageName, totalDuration) ->
            if (AppIconUtils.shouldTrack(context, packageName)) {
                val appName = AppInfoUtils.getAppName(context, packageName)
                UsageLog(
                    packageName = packageName,
                    appName = appName,
                    date = startOfDay,
                    durationMs = totalDuration
                )
            } else null
        }

        Log.d(TAG, "Processed ${liveLogs.size} trackable apps. Total raw records: ${statsList.size}")

        // Background saving
        if (liveLogs.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val firestoreRepo = FirestoreRepository(context)
                    
                    db.appUsageDao().insertUsageLogs(liveLogs)
                    liveLogs.forEach { firestoreRepo.saveUsageLog(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving usage logs in background", e)
                }
            }
        }

        return@withContext liveLogs.sortedByDescending { it.durationMs }
    }
}
