package com.example.traccerapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.utils.AppInfoUtils
import java.util.*

@Composable
fun UsageScreen(onStartService: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    
    val today = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val usageLogs by db.appUsageDao().getUsageLogsForDate(today).collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStartService
        ) {
            Text("Takip Servisini Başlat")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Bugünkü Kullanım", style = MaterialTheme.typography.titleLarge)
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(usageLogs.sortedByDescending { it.durationMs }) { log ->
                UsageDetailItem(log)
            }
        }
    }
}

@Composable
fun UsageDetailItem(log: UsageLog) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = AppInfoUtils.getAppIcon(context, log.packageName)
            if (icon != null) {
                Image(
                    bitmap = icon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(AppInfoUtils.getAppName(context, log.packageName), style = MaterialTheme.typography.bodyLarge)
                Text(AppInfoUtils.formatDuration(log.durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
