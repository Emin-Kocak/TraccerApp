package com.example.traccerapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import com.example.traccerapp.utils.AppCategory
import com.example.traccerapp.utils.AppCategoryUtils
import com.example.traccerapp.utils.AppInfoUtils
import com.example.traccerapp.utils.filterVisible
import java.util.*

private const val DASHBOARD_DAY_MS = 24 * 60 * 60 * 1000L

// ─── Navigasyon durumu ───────────────────────────────────────

private sealed class Screen {
    object Main : Screen()
    object Report : Screen()
    object Detail : Screen()
    object Settings : Screen()
}

// ─── Ana ekran ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    checkPermissions: () -> Boolean,
    requestPermission: () -> Unit,
    isAccessibilityEnabled: () -> Boolean = { true },
    requestAccessibility: () -> Unit = {},
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {}
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
        PermissionScreen { requestPermission(); hasPermission = checkPermissions() }
        return
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TraccerTopBar(
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onSettingsClick = { currentScreen = Screen.Settings }
            )
        },
        bottomBar = { BottomBar(selectedTab) { selectedTab = it } }
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
                        onNavigateToTakip = { selectedTab = 2 }
                    )
                    1 -> ReportsScreen()
                    2 -> HourlyTrackingTab()
                    3 -> BlockingSettingsScreen()
                    4 -> UsageAnalysisTab()
                }
            }
        }
    }
}


// ─── Ortak üst bar ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraccerTopBar(isDarkTheme: Boolean, onToggleTheme: () -> Unit, onSettingsClick: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                "Traccer",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
        actions = {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isDarkTheme) "Açık moda geç" else "Koyu moda geç",
                    tint = TextSecondary
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Ayarlar",
                    tint = TextSecondary
                )
            }
        }
    )
}

// ─── Bottom bar ──────────────────────────────────────────────

@Composable
fun BottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(
        containerColor = DarkSurface,
        tonalElevation = 0.dp
    ) {
        listOf(
            Triple(Icons.Default.Dashboard, "Dashboard", 0),
            Triple(Icons.Default.BarChart, "Raporlar", 1),
            Triple(Icons.Default.AccessTime, "Takip", 2),
            Triple(Icons.Default.Shield, "Limitler", 3),
            Triple(Icons.AutoMirrored.Filled.TrendingUp, "Analiz", 4)
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
    onNavigateToTakip: () -> Unit,
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

    // Hidden packages + minimum kullanım filtresi (ortak yardımcı, madde 25)
    val logs = remember(logsState, prefs.hiddenPackages, prefs.minimumUsageMs) {
        (logsState ?: emptyList())
            .filterVisible(prefs)
            .sortedByDescending { it.durationMs }
    }

    // Veriyi yenile - DB'ye kaydetmek için
    LaunchedEffect(Unit) {
        viewModel.refreshUsageStats()
    }

    val totalMs = logs.sumOf { it.durationMs }
    val topApp = logs.firstOrNull()

    // Telefonun bugün kaç kere açıldığı (unlock event sayısı)
    val unlockEventsFlow = remember(todayStart) {
        db.appUsageDao().getUnlockEventsBetween(todayStart, todayStart + DASHBOARD_DAY_MS)
    }
    val unlockEvents by unlockEventsFlow.collectAsState(initial = null)
    val unlockCount = unlockEvents?.size ?: 0

    // Bugünkü telefon kullanım oturumları (pickup → hangup)
    val phoneSessionsFlow = remember(todayStart) {
        db.appUsageDao().getPhoneSessionsBetween(todayStart, todayStart + DASHBOARD_DAY_MS)
    }
    val phoneSessions by phoneSessionsFlow.collectAsState(initial = null)
    val phoneSessionsTotalMs = remember(phoneSessions) { (phoneSessions ?: emptyList()).sumOf { it.endMs - it.startMs } }

    // Kategoriye göre bugünkü kullanım (çember gösterge için)
    val categoryCache = remember { mutableMapOf<String, AppCategory>() }
    val categoryTotals: List<Pair<AppCategory, Long>> = remember(logs) {
        logs.groupBy { log -> categoryCache.getOrPut(log.packageName) { AppCategoryUtils.categorize(context, log.packageName) } }
            .map { (category, group) -> category to group.sumOf { it.durationMs } }
            .sortedByDescending { it.second }
    }

    // Saatlik kullanım dakikası (UsageStatsManager event'lerinden — DB'de saat kırılımı yok)
    val idleColor = DarkBorder
    var hourBlocks by remember { mutableStateOf<List<HourBlock>>(emptyList()) }
    LaunchedEffect(Unit) {
        hourBlocks = fetchTodayHourBlocks(context, idleColor)
    }
    val hourlyUsageMinutes: List<Int> = remember(hourBlocks) {
        if (hourBlocks.isEmpty()) List(24) { 0 }
        else hourBlocks.map { block -> (block.sessions.filter { !it.isIdle }.sumOf { it.durationSeconds }) / 60 }
    }
    val hourlyUnlockCounts: List<Int> = remember(unlockEvents) {
        val counts = IntArray(24)
        (unlockEvents ?: emptyList()).forEach { event ->
            val hour = Calendar.getInstance().apply { timeInMillis = event.timestampMs }.get(Calendar.HOUR_OF_DAY)
            counts[hour] = counts[hour] + 1
        }
        counts.toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Ana kart (Zaman Raporu ile aynı hero kart tasarımı)
        SummaryHeroCard(
            totalSeconds = (totalMs / 1000L).toInt(),
            goalSeconds = prefs.dailyGoalSeconds,
            onDetailClick = onNavigateToTakip
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Metric kartlar
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "En Çok Kullanılan",
                value = topApp?.appName?.take(10) ?: "—",
                icon = Icons.Default.Star,
                accent = StatusAmber
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "Telefon Açılma",
                value = if (isLoading) "—" else "$unlockCount",
                icon = Icons.Default.LockOpen,
                accent = StatusBlue
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Kategoriye göre kullanım çemberi
        if (categoryTotals.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkSurface)
                    .padding(16.dp)
            ) {
                Column {
                    Text("Kategoriye Göre Kullanım", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    CategoryDonutChart(categoryTotals = categoryTotals)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Saatlik kullanım grafiği (dakika)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            Column {
                Text("Saatlik Kullanım (dakika)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                HourlyBarChart(values = hourlyUsageMinutes, barColor = PurplePrimary, valueLabel = { "$it dk" })
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Saatlik telefon açılma grafiği
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            Column {
                Text("Saatlik Telefon Açılma", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                HourlyBarChart(values = hourlyUnlockCounts, barColor = StatusBlue, valueLabel = { "$it kez" })
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Telefon kullanım oturumları (elden ele: pickup → hangup)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Kullanım Oturumları", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (phoneSessions == null) "—" else "${phoneSessions?.size ?: 0} oturum · ${AppInfoUtils.formatDuration(phoneSessionsTotalMs)}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                PhoneSessionTimeline(sessions = phoneSessions ?: emptyList(), dayStart = todayStart)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Uygulama listesi
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkSurface)
                .padding(14.dp)
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
                Spacer(modifier = Modifier.height(8.dp))

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
                        val maxMs = (logs.maxOfOrNull { it.durationMs } ?: 1L).coerceAtLeast(1L)
                        logs.take(6).forEachIndexed { index, log ->
                            AppRow(
                                name = log.appName,
                                packageName = log.packageName,
                                duration = AppInfoUtils.formatDuration(log.durationMs),
                                progress = log.durationMs.toFloat() / maxMs,
                                color = AppColors[index % AppColors.size]
                            )
                            if (index < minOf(5, logs.size - 1)) {
                                HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 4.dp))
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
fun MetricCard(modifier: Modifier, label: String, value: String, icon: ImageVector, accent: Color) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface)
            .padding(12.dp)
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
fun AppRow(name: String, packageName: String, duration: String, progress: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RealAppIcon(packageName = packageName, appName = name, size = 36.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(8.dp))
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
fun PermissionScreen(onRequest: () -> Unit) {
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
