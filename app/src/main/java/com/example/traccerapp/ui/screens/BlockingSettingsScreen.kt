package com.example.traccerapp.ui.screens

import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.AppLimit
import com.example.traccerapp.utils.AppInfoUtils
import kotlinx.coroutines.launch

@Composable
fun BlockingSettingsScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    val activeLimits by db.appUsageDao().getAllLimits().collectAsState(initial = emptyList())
    var installedApps by remember { mutableStateOf(emptyList<ApplicationInfo>()) }

    LaunchedEffect(Unit) {
        installedApps = getInstalledApps(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Aktif Limitler", style = MaterialTheme.typography.titleLarge)
        LazyColumn(modifier = Modifier.weight(0.4f)) {
            items(activeLimits) { limit ->
                LimitItem(limit, onToggle = {
                    scope.launch { db.appUsageDao().insertOrUpdateLimit(limit.copy(isEnabled = !limit.isEnabled)) }
                }, onDelete = {
                    scope.launch { db.appUsageDao().deleteLimit(limit) }
                })
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Tüm Uygulamalar", style = MaterialTheme.typography.titleLarge)
        LazyColumn(modifier = Modifier.weight(0.6f)) {
            items(installedApps) { app ->
                AppListItem(app) { minutes ->
                    scope.launch {
                        val limit = AppLimit(
                            packageName = app.packageName,
                            appName = AppInfoUtils.getAppName(context, app.packageName),
                            dailyLimitMinutes = minutes,
                            isEnabled = true
                        )
                        db.appUsageDao().insertOrUpdateLimit(limit)
                    }
                }
            }
        }
    }
}

@Composable
fun LimitItem(limit: AppLimit, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(limit.appName, style = MaterialTheme.typography.bodyLarge)
                Text("${limit.dailyLimitMinutes} dakika", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = limit.isEnabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onDelete) {
                    Text("Sil") // In real app use icon
                }
            }
        }
    }
}

@Composable
fun AppListItem(app: ApplicationInfo, onSetLimit: (Int) -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(AppInfoUtils.getAppName(context, app.packageName))
            Button(onClick = {
                showTimePicker(context) { minutes -> onSetLimit(minutes) }
            }) {
                Text("Limit Koy")
            }
        }
    }
}

private fun getInstalledApps(context: Context): List<ApplicationInfo> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // Only non-system apps
}

private fun showTimePicker(context: Context, onTimeSelected: (Int) -> Unit) {
    TimePickerDialog(context, { _, hour, minute ->
        val totalMinutes = hour * 60 + minute
        if (totalMinutes > 0) onTimeSelected(totalMinutes)
    }, 0, 30, true).show()
}
