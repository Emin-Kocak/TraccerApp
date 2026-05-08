package com.example.traccerapp.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─── Ana Ayarlar Ekranı ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }

    // State'ler — prefs'ten başlat, compose ile senkronize tut
    var dailyGoalHours by remember { mutableIntStateOf(prefs.dailyGoalHours) }
    var showNonLauncher by remember { mutableStateOf(prefs.showNonLauncherApps) }
    var goalNotifications by remember { mutableStateOf(prefs.goalNotificationsEnabled) }
    var minimumUsageMinutes by remember {
        mutableIntStateOf((prefs.minimumUsageMs / 60000).toInt())
    }

    // Hangi alt sekme açık
    var activeSection by remember { mutableStateOf<SettingsSection?>(null) }

    if (activeSection == SettingsSection.HIDDEN_APPS) {
        HiddenAppsScreen(prefs = prefs, onBack = { activeSection = null })
        return
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ── Bölüm: Genel ───────────────────────────────
            item {
                SettingsSectionHeader("Genel")
            }

            // Günlük hedef
            item {
                SettingsCard {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsIcon(Icons.Default.TrackChanges, StatusAmber)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Günlük Ekran Hedefi",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "$dailyGoalHours saat",
                                    color = PurpleLight,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = {
                                if (dailyGoalHours > 1) {
                                    dailyGoalHours--
                                    prefs.dailyGoalHours = dailyGoalHours
                                }
                            }) {
                                Icon(Icons.Default.Remove, null, tint = PurpleLight)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2, 4, 6, 8).forEach { h ->
                                    FilterChip(
                                        selected = dailyGoalHours == h,
                                        onClick = {
                                            dailyGoalHours = h
                                            prefs.dailyGoalHours = h
                                        },
                                        label = { Text("${h}sa", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PurplePrimary,
                                            selectedLabelColor = Color.White,
                                            containerColor = DarkElevated,
                                            labelColor = TextSecondary
                                        )
                                    )
                                }
                            }
                            IconButton(onClick = {
                                if (dailyGoalHours < 16) {
                                    dailyGoalHours++
                                    prefs.dailyGoalHours = dailyGoalHours
                                }
                            }) {
                                Icon(Icons.Default.Add, null, tint = PurpleLight)
                            }
                        }
                    }
                }
            }

            // Bildirim
            item {
                SettingsCard {
                    SettingsToggleRow(
                        icon = Icons.Default.Notifications,
                        iconColor = StatusBlue,
                        title = "Hedef Bildirimleri",
                        subtitle = "Günlük hedef aşıldığında bildirim gönder",
                        checked = goalNotifications,
                        onCheckedChange = {
                            goalNotifications = it
                            prefs.goalNotificationsEnabled = it
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ── Bölüm: Uygulama Listesi ────────────────────
            item {
                SettingsSectionHeader("Uygulama Listesi")
            }

            // Gizli uygulamalar
            item {
                SettingsCard {
                    SettingsNavRow(
                        icon = Icons.Default.VisibilityOff,
                        iconColor = Color(0xFFEF4444),
                        title = "Gizli Uygulamalar",
                        subtitle = "Dashboard ve raporlarda gösterilmeyecek uygulamalar",
                        badge = if (prefs.hiddenPackages.isEmpty()) null else "${prefs.hiddenPackages.size}",
                        onClick = { activeSection = SettingsSection.HIDDEN_APPS }
                    )
                }
            }

            // Launcher dışı uygulamaları göster
            item {
                SettingsCard {
                    SettingsToggleRow(
                        icon = Icons.Default.Apps,
                        iconColor = StatusGreen,
                        title = "Tüm Kullanıcı Uygulamaları",
                        subtitle = "Launcher'da görünmeyen ama yüklü olan uygulamaları da listele (Limitler ekranı)",
                        checked = showNonLauncher,
                        onCheckedChange = {
                            showNonLauncher = it
                            prefs.showNonLauncherApps = it
                        }
                    )
                }
            }

            // Minimum kullanım filtresi
            item {
                SettingsCard {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsIcon(Icons.Default.FilterList, StatusAmber)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Minimum Kullanım Filtresi",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (minimumUsageMinutes == 0) "Devre dışı"
                                    else "$minimumUsageMinutes dakikadan kısa kullanımları gizle",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 1, 2, 5).forEach { mins ->
                                FilterChip(
                                    selected = minimumUsageMinutes == mins,
                                    onClick = {
                                        minimumUsageMinutes = mins
                                        prefs.minimumUsageMs = mins * 60_000L
                                    },
                                    label = {
                                        Text(if (mins == 0) "Hepsi" else "${mins}dk", fontSize = 12.sp)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PurplePrimary,
                                        selectedLabelColor = Color.White,
                                        containerColor = DarkElevated,
                                        labelColor = TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ── Bölüm: Uygulama Hakkında ───────────────────
            item {
                SettingsSectionHeader("Hakkında")
            }

            item {
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AboutRow(label = "Uygulama", value = "Traccer")
                        HorizontalDivider(color = DarkBorder)
                        AboutRow(label = "Versiyon", value = "1.0.0")
                        HorizontalDivider(color = DarkBorder)
                        AboutRow(label = "Minimum SDK", value = "Android 12 (API 32)")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ─── Gizli Uygulamalar Alt Ekranı ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAppsScreen(prefs: UserPreferences, onBack: () -> Unit) {
    val context = LocalContext.current
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var hiddenPackages by remember { mutableStateOf(prefs.hiddenPackages) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            getInstalledApps(context, includeNonLauncher = false)
        }
    }

    val filtered = remember(allApps, searchQuery) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Gizli Uygulamalar", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                // Bilgi kartı
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PurpleDim)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = PurpleLight, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Gizlenen uygulamalar Dashboard ve Raporlar ekranlarında görünmez. Limitler etkilenmez.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Arama kutusu
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Uygulama ara...", color = TextHint, fontSize = 14.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PurplePrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = PurplePrimary
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = TextHint, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (allApps.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PurplePrimary, modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                items(filtered) { app ->
                    val isHidden = app.packageName in hiddenPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isHidden) {
                                    prefs.showPackage(app.packageName)
                                } else {
                                    prefs.hidePackage(app.packageName)
                                }
                                hiddenPackages = prefs.hiddenPackages
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RealAppIcon(
                            packageName = app.packageName,
                            appName = app.name,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.name,
                                color = if (isHidden) TextHint else TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (isHidden) {
                                Text(
                                    "Gizlendi",
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Checkbox(
                            checked = isHidden,
                            onCheckedChange = { checked ->
                                if (checked) prefs.hidePackage(app.packageName)
                                else prefs.showPackage(app.packageName)
                                hiddenPackages = prefs.hiddenPackages
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFEF4444),
                                checkmarkColor = Color.White,
                                uncheckedColor = TextHint
                            )
                        )
                    }
                    HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f))
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ─── Yardımcı composable'lar ─────────────────────────────────

enum class SettingsSection { HIDDEN_APPS }

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        title,
        color = TextHint,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
fun SettingsIcon(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIcon(icon, iconColor)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = PurpleLight, checkedTrackColor = PurplePrimary)
        )
    }
}

@Composable
fun SettingsNavRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIcon(icon, iconColor)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextHint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}