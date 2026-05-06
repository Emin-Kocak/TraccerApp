package com.example.traccerapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.traccerapp.BlockingActivity
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.AppLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

class AppAccessibilityService : AccessibilityService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            if (packageName == this.packageName) return

            serviceScope.launch {
                val limit = db.appUsageDao().getLimitForApp(packageName) ?: return@launch
                checkAndBlock(limit)
            }
        }
    }

    private suspend fun checkAndBlock(limit: AppLimit) {
        // Check 1: daily time limit
        if (limit.isTimeLimitEnabled && limit.dailyLimitMinutes > 0) {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            val logs = db.appUsageDao().getUsageLogsForDate(today).first()
            val usedMs = logs.find { it.packageName == limit.packageName }?.durationMs ?: 0L
            val usedMinutes = (usedMs / 60000).toInt()
            
            if (usedMinutes >= limit.dailyLimitMinutes) {
                triggerBlock(limit.packageName, limit.appName, "Günlük limit doldu")
                return
            }
        }

        // Check 2: schedule blocking  
        if (limit.isScheduleEnabled && limit.blockedDays.isNotEmpty()) {
            val now = Calendar.getInstance()
            val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
            val currentDay = listOf("SUN","MON","TUE","WED","THU","FRI","SAT")[dayOfWeek - 1]
            
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val startMinutes = limit.blockStartHour * 60 + limit.blockStartMinute
            val endMinutes = limit.blockEndHour * 60 + limit.blockEndMinute
            val blockedDaysList = limit.blockedDays.split(",")
            
            if (currentDay in blockedDaysList) {
                val isBlocked = if (startMinutes <= endMinutes) {
                    currentMinutes in startMinutes..endMinutes
                } else {
                    currentMinutes >= startMinutes || currentMinutes <= endMinutes
                }
                
                if (isBlocked) {
                    triggerBlock(limit.packageName, limit.appName, "Zamanlama engeli aktif")
                }
            }
        }
    }

    private fun triggerBlock(packageName: String, appName: String, reason: String) {
        val intent = Intent(this, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("packageName", packageName)
            putExtra("appName", appName)
            putExtra("reason", reason)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onInterrupt() {}
}
