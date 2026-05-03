package com.example.traccerapp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import com.example.traccerapp.BlockingActivity
import com.example.traccerapp.data.AppDatabase
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
    
    private var currentPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val checkRunnable = object : Runnable {
        override fun run() {
            currentPackage?.let { pkg ->
                serviceScope.launch {
                    checkAndBlock(pkg)
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        handler.post(checkRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            if (packageName == this.packageName) {
                currentPackage = null
                return
            }
            
            currentPackage = packageName
            serviceScope.launch {
                checkAndBlock(packageName)
            }
        }
    }

    private suspend fun checkAndBlock(packageName: String) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Get total usage for today for this app
        val logs = db.appUsageDao().getUsageLogsForDate(today).first()
        val currentUsageMs = logs.find { it.packageName == packageName }?.durationMs ?: 0L
        
        // Get limit for this app
        val limits = db.appUsageDao().getAllLimits().first()
        val limit = limits.find { it.packageName == packageName }

        if (limit != null && limit.isEnabled) {
            val limitMs = limit.dailyLimitMinutes * 60 * 1000L
            if (currentUsageMs >= limitMs) {
                // Block app
                val intent = Intent(this, BlockingActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("packageName", packageName)
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkRunnable)
        serviceJob.cancel()
    }

    override fun onInterrupt() {}
}
