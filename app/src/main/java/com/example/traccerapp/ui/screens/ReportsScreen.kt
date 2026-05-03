package com.example.traccerapp.ui.screens

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
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.utils.AppInfoUtils
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReportsScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Günlük", "Haftalık", "Aylık")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        
        when (selectedTab) {
            0 -> DailyReport()
            1 -> WeeklyReport()
            2 -> MonthlyReport()
        }
    }
}

@Composable
fun DailyReport() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    var selectedDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    val startOfDay = remember(selectedDate) { getStartOfDay(selectedDate) }
    val usageLogs by db.appUsageDao().getUsageLogsForDate(startOfDay).collectAsState(initial = emptyList())

    ReportContent(usageLogs, "Bugün")
}

@Composable
fun WeeklyReport() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val startOfWeek = remember { 
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val endOfWeek = startOfWeek + (7 * 24 * 60 * 60 * 1000L)
    val usageLogs by db.appUsageDao().getUsageLogsBetween(startOfWeek, endOfWeek).collectAsState(initial = emptyList())

    ReportContent(usageLogs, "Bu Hafta")
}

@Composable
fun MonthlyReport() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val startOfMonth = remember { 
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val endOfMonth = Calendar.getInstance().apply {
        timeInMillis = startOfMonth
        add(Calendar.MONTH, 1)
    }.timeInMillis
    
    val usageLogs by db.appUsageDao().getUsageLogsBetween(startOfMonth, endOfMonth).collectAsState(initial = emptyList())

    ReportContent(usageLogs, "Bu Ay")
}

@Composable
fun ReportContent(logs: List<UsageLog>, title: String) {
    val context = LocalContext.current
    val aggregatedLogs = logs.groupBy { it.packageName }
        .map { (pkg, logs) -> pkg to logs.sumOf { it.durationMs } }
        .sortedByDescending { it.second }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (aggregatedLogs.isNotEmpty()) {
            val chartEntries = aggregatedLogs.take(5).mapIndexed { index, pair -> 
                pair.second.toFloat() / (1000 * 60) // in minutes
            }
            val chartEntryModel = entryModelOf(*chartEntries.toTypedArray())

            Chart(
                chart = columnChart(),
                model = chartEntryModel,
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(),
                modifier = Modifier.height(200.dp).fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn {
                items(aggregatedLogs) { (pkg, duration) ->
                    ReportItem(pkg, duration)
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Veri bulunamadı.")
            }
        }
    }
}

@Composable
fun ReportItem(packageName: String, duration: Long) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AppInfoUtils.getAppName(context, packageName))
            Text(AppInfoUtils.formatDuration(duration))
        }
    }
}

private fun getStartOfDay(time: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = time
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
