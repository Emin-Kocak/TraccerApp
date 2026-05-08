package com.example.traccerapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.ui.viewmodel.UsageViewModel
import com.example.traccerapp.utils.AppInfoUtils
import java.util.*

// ─── Navigasyon durumu ───────────────────────────────────────

private sealed class Screen {
    object Main : Screen()
    object Report : Screen()
    object Detail : Screen()
    object Settings : Screen()
}

// ─── Ana ekran ───────────────────────────────────────────────

@Composable
fun ProMainScreen(
    checkPermissions: () -> Boolean,
    requestPermission: () -> Unit,
    isAccessibilityEnabled: () -> Boolean = { true },
    requestAccessibility: () -> Unit = {}
) {
    var hasPermission by remember { mutableStateOf(checkPermissions()) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityEnabled()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }

    // Ekran her resume olduğunda accessibility durumunu yeniden kontrol et
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            hasAccessibility = isAccessibilityEnabled()
            hasPermission = checkPermissions()
        }
    }

    // Alt ekranlar
    when (currentScreen) {
        is Screen.Detail -> {
            UsageDetailScreen(onBack = { currentScreen = Screen.Report })
            return
        }
        is Screen.Report -> {
            UsageReportScreen(
                onNavigateToDetail = { currentScreen = Screen.Detail },
                onBack = { currentScreen = Screen.Main }
            )
            return
        }
        is Screen.Settings -> {
            AppSettingsScreen(onBack = { currentScreen = Screen.Main })
            return
        }
        else -> Unit
    }

    if (!hasPermission) {
        ProPermissionScreen { requestPermission(); hasPermission = checkPermissions() }
        return
    }

    Scaffold(
        containerColor = DarkBg,
        bottomBar = { ProBottomBar(selectedTab) { selectedTab = it } }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // ⚠️ Erişilebilirlik servisi aktif değilse uyarı göster
            if (!hasAccessibility) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF7C2D12))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Engelleme için Erişilebilirlik izni gerekli!",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = requestAccessibility) {
                        Text("Aç", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> DashboardTab(
                        onDetailClick = { currentScreen = Screen.Report },
                        onSettingsClick = { currentScreen = Screen.Settings }
                    )
                    1 -> ReportsScreen()
                    2 -> UsageScreen()
                    3 -> BlockingSettingsScreen()
                }
            }
        }
    }
}


// ─── Bottom bar ──────────────────────────────────────────────

@Composable
fun ProBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(
        containerColor = DarkSurface,
        tonalElevation = 0.dp
    ) {
        listOf(
            Triple(Icons.Default.Dashboard, "Dashboard", 0),
            Triple(Icons.Default.BarChart, "Raporlar", 1),
            Triple(Icons.Default.AccessTime, "Takip", 2),
            Triple(Icons.Default.Shield, "Limitler", 3)
        ).forEach { (icon, label, index) ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = {
                    Box(contentAlignment = Alignment.Center) {
                        if (selectedTab == index) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(PurpleDim)
                            )
                        }
                        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
                    }
                },
                label = { Text(label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PurplePrimary,
                    unselectedIconColor = TextHint,
                    selectedTextColor = PurplePrimary,
                    unselectedTextColor = TextHint,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

// ─── Dashboard tab ───────────────────────────────────────────

@Composable
fun DashboardTab(
    onDetailClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: UsageViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
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

    // DB'den doğrudan bugünün loglarını oku (ReportsScreen gibi)
    val logsFlow = remember(todayStart) {
        db.appUsageDao().getUsageLogsForDate(todayStart)
    }
    val logsState by logsFlow.collectAsState(initial = null)
    val isLoading = logsState == null

    // Hidden packages'ı filtrele + minimum kullanım filtresi uygula
    val logs = remember(logsState, prefs.hiddenPackages, prefs.minimumUsageMs) {
        val hidden = prefs.hiddenPackages
        val minMs = prefs.minimumUsageMs
        (logsState ?: emptyList())
            .filter { it.packageName !in hidden }
            .filter { it.durationMs >= minMs }
    }

    var goalHours by remember { mutableIntStateOf(prefs.dailyGoalHours) }

    // Veriyi yenile - DB'ye kaydetmek için
    LaunchedEffect(Unit) {
        viewModel.refreshUsageStats()
    }

    val totalMs = logs.sumOf { it.durationMs }
    val topApp = logs.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Header — sağ üstte ayarlar ikonu
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Dashboard", style = MaterialTheme.typography.headlineLarge)
                Text("Bugünkü özet", color = TextSecondary, fontSize = 13.sp)
            }
            // Ayarlar ikonu
            IconButton(onClick = onSettingsClick) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Ayarlar",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val goalMs = goalHours * 3600 * 1000L
        val progress = (totalMs.toFloat() / goalMs).coerceIn(0f, 1f)

        // Ana kart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(PurpleDim, DarkElevated),
                        radius = 800f
                    )
                )
                .padding(24.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isLoading) StatusAmber else StatusGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLoading) "YÜKLENİYOR..." else "CANLI TAKİP",
                        color = if (isLoading) StatusAmber else StatusGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isLoading) "—" else AppInfoUtils.formatDuration(totalMs),
                    color = TextPrimary,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("toplam ekran süresi", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(20.dp))

                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Günlük Hedef", color = TextSecondary, fontSize = 11.sp)
                        Text("$goalHours saat", color = TextSecondary, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(DarkBorder)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(Brush.horizontalGradient(listOf(PurplePrimary, PurpleLight)))
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Metric kartlar
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProMetricCard(
                modifier = Modifier.weight(1f),
                label = "Uygulama Sayısı",
                value = if (isLoading) "—" else "${logs.size}",
                icon = Icons.Default.Apps,
                accent = StatusBlue
            )
            ProMetricCard(
                modifier = Modifier.weight(1f),
                label = "En Çok Kullanılan",
                value = topApp?.appName?.take(10) ?: "—",
                icon = Icons.Default.Star,
                accent = StatusAmber
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Uygulama listesi
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .padding(20.dp)
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Bugünkü Kullanım",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDetailClick) {
                        Text("Zaman Raporu", color = PurplePrimary, fontSize = 12.sp)
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = PurplePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = PurplePrimary, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Kullanım verisi alınıyor...", color = TextHint, fontSize = 13.sp)
                            }
                        }
                    }
                    logs.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = StatusRed,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Veri bulunamadı", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Ayarlar → Uygulamalar → Özel Erişim\n→ Kullanım Verilerine Erişim → Traccer → AÇ",
                                    color = TextHint,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    else -> {
                        val maxMs = logs.maxOfOrNull { it.durationMs } ?: 1L
                        logs.take(6).forEachIndexed { index, log ->
                            ProAppRow(
                                name = log.appName,
                                packageName = log.packageName,
                                duration = AppInfoUtils.formatDuration(log.durationMs),
                                progress = log.durationMs.toFloat() / maxMs,
                                color = AppColors[index % AppColors.size]
                            )
                            if (index < minOf(5, logs.size - 1)) {
                                HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Yardımcı composable'lar ─────────────────────────────────

@Composable
fun ProMetricCard(modifier: Modifier, label: String, value: String, icon: ImageVector, accent: Color) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
fun ProAppRow(name: String, packageName: String, duration: String, progress: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RealAppIcon(packageName = packageName, appName = name, size = 36.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(duration, color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DarkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }
    }
}

@Composable
fun ProPermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(DarkSurface)
                .padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PurpleDim),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = PurplePrimary, modifier = Modifier.size(36.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("İzin Gerekli", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Ekran süresi verilerini görmek için kullanım istatistiklerine erişim izni vermeniz gerekiyor.",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = PurplePrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("İzin Ver", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

// GoalSettingsDialog — kullanılıyorsa kalsın (artık ayarlar ekranından da erişilebilir)
@Composable
fun GoalSettingsDialog(currentGoal: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableIntStateOf(currentGoal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("Günlük Hedef", color = TextPrimary) },
        text = {
            Column {
                Text("Günlük ekran süresi hedefini belirle:", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { if (hours > 1) hours-- }) {
                        Icon(Icons.Default.Remove, null, tint = PurpleLight)
                    }
                    Text("$hours saat", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (hours < 16) hours++ }) {
                        Icon(Icons.Default.Add, null, tint = PurpleLight)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4, 6, 8).forEach { h ->
                        FilterChip(
                            selected = hours == h,
                            onClick = { hours = h },
                            label = { Text("${h}sa") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PurplePrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hours) }) {
                Text("Kaydet", color = PurplePrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal", color = TextSecondary)
            }
        }
    )
}