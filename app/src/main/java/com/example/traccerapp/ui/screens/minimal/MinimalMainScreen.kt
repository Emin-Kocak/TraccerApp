package com.example.traccerapp.ui.screens.minimal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.ui.screens.*
import com.example.traccerapp.ui.theme.*

@Composable
fun MinimalMainScreen(
    onStartService: () -> Unit,
    checkPermissions: () -> Boolean,
    requestPermission: () -> Unit
) {
    var hasPermission by remember { mutableStateOf(checkPermissions()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDetail by remember { mutableStateOf(false) }

    if (showDetail) {
        UsageDetailScreen(onBack = { showDetail = false })
        return
    }

    if (!hasPermission) {
        MinimalPermissionScreen { requestPermission(); hasPermission = checkPermissions() }
        return
    }

    Scaffold(
        containerColor = DarkBg,
        bottomBar = {
            MinimalBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> MinimalUsageTab(onStartService)
                1 -> ReportsScreen()
                2 -> UsageReportScreen(onNavigateToDetail = { showDetail = true })
                3 -> BlockingSettingsScreen()
            }
        }
    }
}

@Composable
fun MinimalBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val items = listOf(
        Triple(Icons.Default.Timeline,  "Takip",   0),
        Triple(Icons.Default.BarChart,  "Raporlar",1),
        Triple(Icons.Default.AccessTime,"Detay",   2),
        Triple(Icons.Default.Block,     "Limit",   3)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { (icon, label, index) ->
                MinimalNavItem(
                    icon = icon,
                    label = label,
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) }
                )
            }
        }
    }
}

@Composable
fun MinimalNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PurpleDim else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) PurplePrimary else TextHint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (selected) PurplePrimary else TextHint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(4.dp))
        // Seçili nokta göstergesi
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) PurplePrimary else Color.Transparent)
        )
    }
}

@Composable
fun MinimalUsageTab(onStartService: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp)
    ) {
        // Başlık
        Text("Hoş Geldin", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Ekran Takibi", style = MaterialTheme.typography.displayMedium)

        Spacer(modifier = Modifier.height(32.dp))

        // Servis başlatma kartı
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(PurplePrimary, Color(0xFF4F46E5))
                    )
                )
                .padding(24.dp)
        ) {
            Column {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Takip Servisini Başlat", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Arka planda uygulama kullanımını izler", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onStartService,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                ) {
                    Text("Başlat")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Bilgi kartları
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MinimalInfoCard(modifier = Modifier.weight(1f), icon = Icons.Default.Visibility, label = "Takip", value = "Aktif", color = StatusGreen)
            MinimalInfoCard(modifier = Modifier.weight(1f), icon = Icons.Default.Timer, label = "Bugün", value = "—", color = PurpleLight)
        }
    }
}

@Composable
fun MinimalInfoCard(modifier: Modifier, icon: ImageVector, label: String, value: String, color: Color) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        Column {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
fun MinimalPermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = PurplePrimary, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("İzin Gerekli", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Uygulama kullanımını takip etmek için izin vermeniz gerekiyor.", color = TextSecondary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Ayarlara Git", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}