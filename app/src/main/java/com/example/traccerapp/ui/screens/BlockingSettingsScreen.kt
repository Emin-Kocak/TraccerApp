package com.example.traccerapp.ui.screens

import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.AppLimit
import com.example.traccerapp.ui.theme.*
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    
    val allApps = remember { getInstalledApps(context) }
    val activeLimits by db.appUsageDao().getAllLimits().collectAsState(initial = emptyList())
    
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Limitler", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Active Limits Summary
            val active = activeLimits.filter { it.isTimeLimitEnabled || it.isScheduleEnabled }
            if (active.isNotEmpty()) {
                item {
                    Text("Aktif Limitler", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(active) { limit ->
                    ActiveLimitRow(limit) {
                        selectedApp = allApps.find { it.packageName == limit.packageName } ?: AppInfo(limit.appName, limit.packageName)
                        showBottomSheet = true
                    }
                }
            }

            item {
                Text("Tüm Uygulamalar", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }

            items(allApps) { app ->
                AppRow(app) {
                    selectedApp = app
                    showBottomSheet = true
                }
            }
        }

        if (showBottomSheet && selectedApp != null) {
            val existingLimit = activeLimits.find { it.packageName == selectedApp!!.packageName }
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                containerColor = DarkSurface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = TextHint) }
            ) {
                LimitSettingsContent(
                    app = selectedApp!!,
                    existingLimit = existingLimit,
                    onSave = { limit ->
                        scope.launch {
                            db.appUsageDao().insertOrUpdateLimit(limit)
                            showBottomSheet = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ActiveLimitRow(limit: AppLimit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RealAppIcon(packageName = limit.packageName, appName = limit.appName)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(limit.appName, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (limit.isTimeLimitEnabled) {
                    Icon(Icons.Default.Timer, null, tint = Color.Red, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${limit.dailyLimitMinutes}dk", color = Color.Red, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (limit.isScheduleEnabled) {
                    Icon(Icons.Default.Schedule, null, tint = PurpleLight, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Plan Aktif", color = PurpleLight, fontSize = 11.sp)
                }
            }
        }
        Icon(Icons.Default.Settings, null, tint = TextHint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun AppRow(app: AppInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RealAppIcon(packageName = app.packageName, appName = app.name)
        Spacer(modifier = Modifier.width(12.dp))
        Text(app.name, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.Add, null, tint = PurplePrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun LimitSettingsContent(
    app: AppInfo,
    existingLimit: AppLimit?,
    onSave: (AppLimit) -> Unit
) {
    var isTimeEnabled by remember { mutableStateOf(existingLimit?.isTimeLimitEnabled ?: false) }
    var dailyMinutes by remember { mutableIntStateOf(existingLimit?.dailyLimitMinutes ?: 30) }
    
    var isScheduleEnabled by remember { mutableStateOf(existingLimit?.isScheduleEnabled ?: false) }
    var blockedDays by remember { mutableStateOf(existingLimit?.blockedDays?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()) }
    
    var startHour by remember { mutableIntStateOf(existingLimit?.blockStartHour ?: 22) }
    var startMin by remember { mutableIntStateOf(existingLimit?.blockStartMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(existingLimit?.blockEndHour ?: 8) }
    var endMin by remember { mutableIntStateOf(existingLimit?.blockEndMinute ?: 0) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RealAppIcon(packageName = app.packageName, appName = app.name, size = 44.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Text(app.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section 1: Daily Time Limit
        SectionTitle("Günlük Süre Limiti", isTimeEnabled) { isTimeEnabled = it }
        if (isTimeEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { if (dailyMinutes > 5) dailyMinutes -= 5 }) {
                    Icon(Icons.Default.Remove, null, tint = PurpleLight)
                }
                Text("${dailyMinutes} Dakika", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { dailyMinutes += 5 }) {
                    Icon(Icons.Default.Add, null, tint = PurpleLight)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120).forEach { mins ->
                    FilterChip(
                        selected = dailyMinutes == mins,
                        onClick = { dailyMinutes = mins },
                        label = { Text("${if (mins >= 60) mins/60 else mins}${if (mins >= 60) "sa" else "dk"}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PurplePrimary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section 2: Schedule
        SectionTitle("Zaman Planı", isScheduleEnabled) { isScheduleEnabled = it }
        if (isScheduleEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val daysMap = mapOf("MON" to "Pzt", "TUE" to "Sal", "WED" to "Çar", "THU" to "Per", "FRI" to "Cum", "SAT" to "Cmt", "SUN" to "Paz")
                listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").forEach { day ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (day in blockedDays) PurplePrimary else DarkElevated)
                            .clickable {
                                blockedDays = if (day in blockedDays) blockedDays - day else blockedDays + day
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(daysMap[day]!!, color = if (day in blockedDays) Color.White else TextSecondary, fontSize = 10.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TimePickerButton(modifier = Modifier.weight(1f), label = "Başlangıç", hour = startHour, minute = startMin) { h, m ->
                    startHour = h; startMin = m
                }
                TimePickerButton(modifier = Modifier.weight(1f), label = "Bitiş", hour = endHour, minute = endMin) { h, m ->
                    endHour = h; endMin = m
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val limit = AppLimit(
                    packageName = app.packageName,
                    appName = app.name,
                    dailyLimitMinutes = dailyMinutes,
                    isTimeLimitEnabled = isTimeEnabled,
                    isScheduleEnabled = isScheduleEnabled,
                    blockedDays = blockedDays.joinToString(","),
                    blockStartHour = startHour,
                    blockStartMinute = startMin,
                    blockEndHour = endHour,
                    blockEndMinute = endMin
                )
                onSave(limit)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Kaydet", color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
fun SectionTitle(title: String, isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Switch(checked = isEnabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = PurpleLight))
    }
}

@Composable
fun TimePickerButton(modifier: Modifier, label: String, hour: Int, minute: Int, onTimeSelected: (Int, Int) -> Unit) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkElevated)
                .clickable {
                    TimePickerDialog(context, { _, h, m -> onTimeSelected(h, m) }, hour, minute, true).show()
                }
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(String.format("%02d:%02d", hour, minute), color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

data class AppInfo(val name: String, val packageName: String)

fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val ownPackage = context.packageName

    // Yöntem 1: Launcher'da görünen tüm uygulamaları al (en güvenilir)
    val launchableApps = mutableMapOf<String, AppInfo>()

    val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = pm.queryIntentActivities(launchIntent, 0)

    for (ri in resolveInfos) {
        val pkg = ri.activityInfo.packageName
        if (pkg == ownPackage) continue
        val label = ri.loadLabel(pm).toString()
        launchableApps[pkg] = AppInfo(label, pkg)
    }

    // Yöntem 2: Kullanıcı tarafından yüklenen ama launcher'da olmayan uygulamaları da ekle
    try {
        val allApps = pm.getInstalledApplications(0)
        for (appInfo in allApps) {
            val pkg = appInfo.packageName
            if (pkg == ownPackage) continue
            if (launchableApps.containsKey(pkg)) continue

            // Sistem uygulaması değilse veya güncellenmiş sistem uygulamasıysa ekle
            val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            if (isUserApp || isUpdatedSystem) {
                val label = com.example.traccerapp.utils.AppInfoUtils.getAppName(context, pkg)
                launchableApps[pkg] = AppInfo(label, pkg)
            }
        }
    } catch (_: Exception) { }

    return launchableApps.values.sortedBy { it.name.lowercase() }
}
