package com.example.traccerapp

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.PhoneActivitySync
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.service.AppAccessibilityService
import com.example.traccerapp.service.TrackingService
import com.example.traccerapp.ui.screens.MainScreen
import com.example.traccerapp.ui.theme.TraccerAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (hasUsageStatsPermission()) {
            startTrackingService()
            // Unlock/oturum verisini OS'ten anında backfill et (startup worker'ın 30 sn
            // gecikmesini bekleme — Room Flow'ları yazınca ekranlar kendiliğinden tazelenir).
            lifecycleScope.launch(Dispatchers.IO) {
                PhoneActivitySync.sync(
                    this@MainActivity,
                    AppDatabase.getDatabase(this@MainActivity).appUsageDao()
                )
            }
        }

        requestBatteryOptimizationExemption()

        setContent {
            val context = LocalContext.current
            val prefs = remember { UserPreferences(context) }
            var isDarkTheme by remember { mutableStateOf(prefs.isDarkThemeEnabled) }

            TraccerAppTheme(isDarkTheme = isDarkTheme) {
                MainScreen(
                    checkPermissions = { hasUsageStatsPermission() },
                    requestPermission = { requestUsageStatsPermission() },
                    isAccessibilityEnabled = { isAccessibilityServiceEnabled() },
                    requestAccessibility = { requestAccessibilityPermission() },
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        prefs.isDarkThemeEnabled = isDarkTheme
                    }
                )
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // Ayar, bileşeni kısa formda da saklayabilir ("paket/.service.Sınıf") — düz string
        // karşılaştırması bunu kaçırıp servis açıkken "izin gerekli" uyarısı gösterirdi.
        val expected = ComponentName(this, AppAccessibilityService::class.java)
        return enabledServices.split(":").any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    private fun requestUsageStatsPermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun requestAccessibilityPermission() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }
        }
    }
}
