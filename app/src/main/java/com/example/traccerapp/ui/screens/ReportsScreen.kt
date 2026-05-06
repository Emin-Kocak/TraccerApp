package com.example.traccerapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.data.FirestoreRepository
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.utils.AppInfoUtils
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    val context = LocalContext.current
    val firestoreRepo = remember { FirestoreRepository(context) }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Bugün", "Haftalık", "Aylık")

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Raporlar", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkBg,
                contentColor = PurplePrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = PurplePrimary
                    )
                },
                divider = { HorizontalDivider(color = DarkBorder) }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp) },
                        selectedContentColor = PurplePrimary,
                        unselectedContentColor = TextHint
                    )
                }
            }

            val logsFlow = remember(selectedTab) {
                when (indexToRange(selectedTab)) {
                    null -> firestoreRepo.getTodayLogs()
                    else -> {
                        val range = indexToRange(selectedTab)!!
                        firestoreRepo.getLogsForDateRange(range.first, range.second)
                    }
                }
            }
            
            val logs by logsFlow.collectAsState(initial = null)

            if (logs == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PurplePrimary)
                }
            } else {
                ReportContent(logs!!)
            }
        }
    }
}

@Composable
fun ReportContent(logs: List<UsageLog>) {
    val grouped = logs.groupBy { it.packageName }.mapValues { entry ->
        val total = entry.value.sumOf { it.durationMs }
        val appName = entry.value.firstOrNull()?.appName ?: entry.key
        Pair(appName, total)
    }.entries.sortedByDescending { it.value.second }

    if (grouped.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Veri bulunamadı", color = TextHint)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(grouped) { entry ->
                ReportAppRow(packageName = entry.key, appName = entry.value.first, durationMs = entry.value.second)
            }
        }
    }
}

@Composable
fun ReportAppRow(packageName: String, appName: String, durationMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RealAppIcon(packageName = packageName, appName = appName, size = 40.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(appName, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(packageName, color = TextHint, fontSize = 11.sp, maxLines = 1)
        }
        Text(AppInfoUtils.formatDuration(durationMs), color = PurpleLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

fun indexToRange(index: Int): Pair<String, String>? {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance()
    val end = sdf.format(cal.time)
    
    return when (index) {
        0 -> null // Today uses special call
        1 -> {
            cal.add(Calendar.DAY_OF_YEAR, -7)
            Pair(sdf.format(cal.time), end)
        }
        2 -> {
            cal.add(Calendar.MONTH, -1)
            Pair(sdf.format(cal.time), end)
        }
        else -> null
    }
}
