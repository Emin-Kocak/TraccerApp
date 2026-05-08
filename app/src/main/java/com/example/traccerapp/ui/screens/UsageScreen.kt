package com.example.traccerapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.ui.viewmodel.UsageViewModel
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.utils.AppInfoUtils
import com.example.traccerapp.utils.UsageStatsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(viewModel: UsageViewModel = viewModel()) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Başlangıç zamanını hesapla
    val todayStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    
    // DB'den doğrudan bugünün loglarını oku
    val logsFlow = remember(todayStart) {
        db.appUsageDao().getUsageLogsForDate(todayStart)
    }
    val logs by logsFlow.collectAsState(initial = null)
    
    LaunchedEffect(Unit) {
        viewModel.refreshUsageStats()
    }
    
    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Takip", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(PurplePrimary, Color(0xFF4F46E5))))
                        .padding(24.dp)
                ) {
                    Column {
                        Text("Canlı Takip", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Uygulama kullanımları saniye saniye izleniyor.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
            }

            item {
                Text("Bugünkü Kullanım", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            }

            if (logs == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PurplePrimary)
                    }
                }
            } else if (logs!!.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("Henüz veri yok", color = TextHint)
                    }
                }
            } else {
                items(logs!!.sortedByDescending { it.durationMs }) { log ->
                    UsageAppRow(log.packageName, log.appName, log.durationMs)
                }
            }
            
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun UsageAppRow(packageName: String, appName: String, durationMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RealAppIcon(packageName = packageName, appName = appName)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(appName, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(packageName, color = TextHint, fontSize = 11.sp)
        }
        Text(AppInfoUtils.formatDuration(durationMs), color = PurpleLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
