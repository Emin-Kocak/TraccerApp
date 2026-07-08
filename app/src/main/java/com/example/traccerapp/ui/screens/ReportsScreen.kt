package com.example.traccerapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.PhoneSession
import com.example.traccerapp.data.UnlockEvent
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.utils.AppCategory
import com.example.traccerapp.utils.AppCategoryUtils
import com.example.traccerapp.utils.AppInfoUtils
import com.example.traccerapp.utils.DAY_MS
import com.example.traccerapp.utils.filterVisible
import com.example.traccerapp.utils.mondayOfWeek
import com.example.traccerapp.utils.startOfDay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

private const val HOUR_MS = 60 * 60 * 1000L
private const val PAST_WEEKS = 104
private const val FUTURE_WEEKS = 52
private const val COLLAPSED_APP_COUNT = 5
private const val COLLAPSED_ACTIVITY_COUNT = 10
private val WEEKDAY_LETTERS = listOf("P", "S", "Ç", "P", "C", "C", "P") // Pzt..Paz
private val ACTIVITY_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr"))

/** Seçili günün ham hareket kaydı: telefon açılışları ve pickup→hangup oturumları,
 *  kronolojik tek listede. Sıralama Kotlin tarafında (bkz. CLAUDE.md madde 5). */
private sealed class DayActivity(val timeMs: Long) {
    class Unlock(timeMs: Long) : DayActivity(timeMs)
    class Session(val startMs: Long, val endMs: Long) : DayActivity(startMs)
}

data class AppDayUsage(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val category: AppCategory
)

// ─── Ana Raporlar Ekranı ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val prefs = remember { UserPreferences(context) }

    val today = remember { startOfDay(System.currentTimeMillis()) }
    val todayWeekStart = remember(today) { mondayOfWeek(today) }
    val todayPageIndex = PAST_WEEKS

    val pagerState = rememberPagerState(initialPage = todayPageIndex) { PAST_WEEKS + FUTURE_WEEKS + 1 }
    val weekStart = remember(pagerState.currentPage) {
        todayWeekStart + (pagerState.currentPage - todayPageIndex) * 7 * DAY_MS
    }
    val weekEnd = remember(weekStart) { weekStart + 6 * DAY_MS }

    // Hafta değişince: yeni hafta bugünü içeriyorsa bugünü, değilse haftanın ilk gününü seç
    var selectedDate by remember(weekStart) {
        mutableLongStateOf(if (today in weekStart..weekEnd) today else weekStart)
    }

    val logsFlow = remember(weekStart) { db.appUsageDao().getUsageLogsBetween(weekStart, weekEnd) }
    val rawLogs by logsFlow.collectAsState(initial = null)
    // Hidden packages + minimum kullanım filtresi — Dashboard ile aynı ortak yardımcı (madde 25)
    val logs = remember(rawLogs, prefs.hiddenPackages, prefs.minimumUsageMs) {
        rawLogs?.filterVisible(prefs)
    }

    val unlockEventsFlow = remember(weekStart) {
        db.appUsageDao().getUnlockEventsBetween(weekStart, weekEnd + DAY_MS)
    }
    val unlockEvents by unlockEventsFlow.collectAsState(initial = null)
    val unlockCountsByDay: Map<Long, Int> = remember(unlockEvents) {
        (unlockEvents ?: emptyList())
            .groupBy { startOfDay(it.timestampMs) }
            .mapValues { (_, events) -> events.size }
    }

    val phoneSessionsFlow = remember(weekStart) {
        db.appUsageDao().getPhoneSessionsBetween(weekStart, weekEnd + DAY_MS)
    }
    val phoneSessions by phoneSessionsFlow.collectAsState(initial = null)

    // Seçili günün ham hareketleri: unlock + oturum, tek kronolojik liste (gece yarısını aşan
    // oturum başladığı güne sayılır — madde 21 ile aynı kural)
    val selectedDayActivities: List<DayActivity> = remember(unlockEvents, phoneSessions, selectedDate) {
        val unlocks = (unlockEvents ?: emptyList())
            .filter { startOfDay(it.timestampMs) == selectedDate }
            .map { DayActivity.Unlock(it.timestampMs) }
        val sessions = (phoneSessions ?: emptyList())
            .filter { it.endMs > it.startMs && startOfDay(it.startMs) == selectedDate }
            .map { DayActivity.Session(it.startMs, it.endMs) }
        (unlocks + sessions).sortedBy { it.timeMs }
    }

    Scaffold(
        containerColor = DarkBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    val pageWeekStart = todayWeekStart + (page - todayPageIndex) * 7 * DAY_MS
                    WeekDateRow(
                        weekStart = pageWeekStart,
                        selectedDate = selectedDate,
                        today = today,
                        onDateSelected = { selectedDate = it }
                    )
                }
            }

            item {
                if (logs == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PurplePrimary)
                    }
                } else {
                    WeeklyUsageChartCard(
                        weekStart = weekStart,
                        selectedDate = selectedDate,
                        today = today,
                        logs = logs!!,
                        unlockCountsByDay = unlockCountsByDay,
                        onDateSelected = { selectedDate = it },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            item {
                if (logs != null) {
                    DayActivityCard(
                        activities = selectedDayActivities,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ─── Haftalık Tarih Şeridi (kaydırınca 7 gün birden değişir) ──

@Composable
fun WeekDateRow(weekStart: Long, selectedDate: Long, today: Long, onDateSelected: (Long) -> Unit) {
    val days = remember(weekStart) { (0..6).map { weekStart + it * DAY_MS } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        days.forEach { day ->
            val isSelected = day == selectedDate
            val isFuture = day > today
            val dayOfMonth = remember(day) {
                Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_MONTH)
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) PurplePrimary else DarkSurface)
                    .then(if (isFuture) Modifier else Modifier.clickable { onDateSelected(day) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayOfMonth.toString(),
                    color = when {
                        isSelected -> Color.White
                        isFuture -> TextHint
                        else -> TextSecondary
                    },
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ─── Haftalık Grafik Kartı ────────────────────────────────────

@Composable
fun WeeklyUsageChartCard(
    weekStart: Long,
    selectedDate: Long,
    today: Long = Long.MAX_VALUE,
    logs: List<UsageLog>,
    unlockCountsByDay: Map<Long, Int> = emptyMap(),
    onDateSelected: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val categoryCache = remember { mutableMapOf<String, AppCategory>() }
    val weekDays = remember(weekStart) { (0..6).map { weekStart + it * DAY_MS } }

    val perDay: Map<Long, List<AppDayUsage>> = remember(logs) {
        logs.groupBy { it.date }.mapValues { (_, dayLogs) ->
            dayLogs.map { log ->
                val category = categoryCache.getOrPut(log.packageName) {
                    AppCategoryUtils.categorize(context, log.packageName)
                }
                AppDayUsage(log.packageName, log.appName, log.durationMs, category)
            }
        }
    }

    val dayTotals: Map<Long, Long> = remember(perDay, weekDays) {
        weekDays.associateWith { day -> perDay[day]?.sumOf { it.durationMs } ?: 0L }
    }

    val maxDayTotal = remember(dayTotals) { dayTotals.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L }
    val scaleMaxMs = remember(maxDayTotal) {
        if (maxDayTotal <= HOUR_MS) HOUR_MS
        else ceil(maxDayTotal / HOUR_MS.toDouble()).toLong() * HOUR_MS
    }

    // Haftanın tamamında en çok kullanılan uygulamalar her günün çubuğunda aynı sırayla yığılsın
    val globalAppOrder: List<String> = remember(perDay) {
        perDay.values.flatten()
            .groupBy { it.packageName }
            .mapValues { (_, v) -> v.sumOf { it.durationMs } }
            .entries.sortedByDescending { it.value }
            .map { it.key }
    }

    val selectedDayApps = remember(perDay, selectedDate) {
        (perDay[selectedDate] ?: emptyList()).sortedByDescending { it.durationMs }
    }
    val selectedDayTotal = dayTotals[selectedDate] ?: 0L

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .padding(20.dp)
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = AppInfoUtils.formatDuration(selectedDayTotal),
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Toplam ekran süresi", color = TextSecondary, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${unlockCountsByDay[selectedDate] ?: 0}",
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Telefon açılma", color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            WeeklyBarChart(
                weekDays = weekDays,
                perDay = perDay,
                appOrder = globalAppOrder,
                scaleMaxMs = scaleMaxMs,
                dayTotals = dayTotals,
                selectedDate = selectedDate,
                today = today,
                onDaySelected = onDateSelected
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (selectedDayApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Bu gün için veri yok", color = TextHint, fontSize = 13.sp)
                }
            } else {
                SelectedDayAppList(apps = selectedDayApps)
            }
        }
    }
}

// ─── Yığılmış Haftalık Bar Chart ──────────────────────────────

@Composable
fun WeeklyBarChart(
    weekDays: List<Long>,
    perDay: Map<Long, List<AppDayUsage>>,
    appOrder: List<String>,
    scaleMaxMs: Long,
    dayTotals: Map<Long, Long>,
    selectedDate: Long,
    today: Long = Long.MAX_VALUE,
    onDaySelected: (Long) -> Unit = {}
) {
    val barAreaHeight = 140.dp
    val labelColumnWidth = 40.dp

    Column {
        Row(modifier = Modifier.fillMaxWidth().height(barAreaHeight)) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Yardımcı yatay çizgiler
                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalDivider(color = DarkBorder.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(color = DarkBorder.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(color = DarkBorder.copy(alpha = 0.6f))
                }

                // Çubuklar
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    weekDays.forEach { day ->
                        val total = dayTotals[day] ?: 0L
                        val barHeight = barAreaHeight * (total.toFloat() / scaleMaxMs.toFloat()).coerceIn(0f, 1f)
                        val segments = perDay[day].orEmpty().sortedBy { appOrder.indexOf(it.packageName) }

                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(barAreaHeight)
                                .then(if (day > today) Modifier else Modifier.clickable { onDaySelected(day) }),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (total > 0) {
                                Column(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                ) {
                                    segments.forEach { seg ->
                                        val segHeight = barAreaHeight * (seg.durationMs.toFloat() / scaleMaxMs.toFloat())
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(segHeight)
                                                .background(categoryColor(seg.category))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sağ taraftaki ölçek etiketleri
            Column(
                modifier = Modifier.width(labelColumnWidth).fillMaxHeight().padding(start = 6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(AppInfoUtils.formatDuration(scaleMaxMs), color = TextHint, fontSize = 10.sp)
                Text(AppInfoUtils.formatDuration(scaleMaxMs / 2), color = TextHint, fontSize = 10.sp)
                Text("0", color = TextHint, fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Gün etiketleri
        Row(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                weekDays.forEachIndexed { index, day ->
                    val isSelected = day == selectedDate
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) PurplePrimary else Color.Transparent)
                            .then(if (day > today) Modifier else Modifier.clickable { onDaySelected(day) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = WEEKDAY_LETTERS[index],
                            color = if (isSelected) Color.White else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(labelColumnWidth))
        }
    }
}

// ─── Seçili Gün Uygulama Listesi ──────────────────────────────

@Composable
fun SelectedDayAppList(apps: List<AppDayUsage>) {
    var expanded by remember(apps) { mutableStateOf(false) }
    val visibleApps = if (expanded || apps.size <= COLLAPSED_APP_COUNT) apps else apps.take(COLLAPSED_APP_COUNT)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        visibleApps.forEach { app ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(categoryColor(app.category))
                )
                Spacer(modifier = Modifier.width(10.dp))
                RealAppIcon(packageName = app.packageName, appName = app.appName, size = 28.dp, cornerRadius = 8.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(app.appName, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(AppInfoUtils.formatDuration(app.durationMs), color = TextSecondary, fontSize = 13.sp)
            }
        }

        if (apps.size > COLLAPSED_APP_COUNT) {
            HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = if (expanded) "Daha Az Göster" else "Tümünü Göster",
                color = PurpleLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp)
            )
        }
    }
}

// ─── Seçili Gün Hareket Kaydı (unlock + oturum, kronolojik) ───

@Composable
private fun DayActivityCard(activities: List<DayActivity>, modifier: Modifier = Modifier) {
    var expanded by remember(activities) { mutableStateOf(false) }
    val visibleActivities =
        if (expanded || activities.size <= COLLAPSED_ACTIVITY_COUNT) activities
        else activities.take(COLLAPSED_ACTIVITY_COUNT)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .padding(20.dp)
    ) {
        Column {
            Text(
                text = "Gün Hareketleri",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Telefon açılışları ve kullanım oturumları",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(14.dp))

            if (activities.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Bu gün için hareket kaydı yok",
                        color = TextHint,
                        fontSize = 13.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visibleActivities.forEach { activity ->
                        when (activity) {
                            is DayActivity.Unlock -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = StatusGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Telefon açıldı", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(
                                    text = ACTIVITY_TIME_FORMAT.format(Date(activity.timeMs)),
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            is DayActivity.Session -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = PurpleLight,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Oturum • ${AppInfoUtils.formatDuration(activity.endMs - activity.startMs)}",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${ACTIVITY_TIME_FORMAT.format(Date(activity.startMs))} – ${ACTIVITY_TIME_FORMAT.format(Date(activity.endMs))}",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    if (activities.size > COLLAPSED_ACTIVITY_COUNT) {
                        HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = if (expanded) "Daha Az Göster" else "Tümünü Göster (${activities.size})",
                            color = PurpleLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

