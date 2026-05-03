package com.example.traccerapp

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.traccerapp.service.TrackingService
import com.example.traccerapp.ui.screens.BlockingSettingsScreen
import com.example.traccerapp.ui.screens.ReportsScreen
import com.example.traccerapp.ui.screens.UsageScreen
import com.example.traccerapp.ui.theme.TraccerAppTheme

import com.example.traccerapp.ui.screens.UsageReportScreen
import com.example.traccerapp.ui.screens.UsageDetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Auto-start service if permission is already granted
        if (hasUsageStatsPermission()) {
            startTrackingService()
        }

        setContent {
            TraccerAppTheme {
                MainContainer(
                    onStartService = { startTrackingService() },
                    checkPermissions = { hasUsageStatsPermission() },
                    requestPermission = { requestUsageStatsPermission() }
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

    private fun requestUsageStatsPermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContainer(
    onStartService: () -> Unit,
    checkPermissions: () -> Boolean,
    requestPermission: () -> Unit
) {
    var hasPermission by remember { checkPermissions(); mutableStateOf(checkPermissions()) }
    var selectedScreen by remember { mutableIntStateOf(0) }
    var showDetail by remember { mutableStateOf(false) }

    // Detay ekranı açıksa onu göster
    if (showDetail) {
        UsageDetailScreen(onBack = { showDetail = false })
        return
    }

    Scaffold(
        bottomBar = {
            if (hasPermission) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedScreen == 0,
                        onClick = { selectedScreen = 0 },
                        icon = { Icon(Icons.Default.Timeline, contentDescription = null) },
                        label = { Text("Takip") }
                    )
                    NavigationBarItem(
                        selected = selectedScreen == 1,
                        onClick = { selectedScreen = 1 },
                        icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                        label = { Text("Raporlar") }
                    )
                    NavigationBarItem(
                        selected = selectedScreen == 3,
                        onClick = { selectedScreen = 3 },
                        icon = { Icon(Icons.Default.Timeline, contentDescription = null) },
                        label = { Text("Detay") }
                    )
                    NavigationBarItem(
                        selected = selectedScreen == 2,
                        onClick = { selectedScreen = 2 },
                        icon = { Icon(Icons.Default.Block, contentDescription = null) },
                        label = { Text("Bloklama") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (!hasPermission) {
                PermissionRequestScreen(requestPermission)
            } else {
                when (selectedScreen) {
                    0 -> UsageScreen(onStartService)
                    1 -> ReportsScreen()
                    2 -> BlockingSettingsScreen()
                    3 -> UsageReportScreen(onNavigateToDetail = { showDetail = true })
                }
            }
        }
    }
}

@Composable
fun PermissionRequestScreen(requestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Uygulama kullanımı takibi için izin gerekiyor.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = requestPermission) {
            Text("Ayarlara Git")
        }
    }
}
