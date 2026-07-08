package com.example.traccerapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Timelapse
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
import com.example.traccerapp.data.AppDatabase
import com.example.traccerapp.data.PhoneSession
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.utils.AppInfoUtils
import com.example.traccerapp.utils.DAY_MS
import com.example.traccerapp.utils.filterVisible
import com.example.traccerapp.utils.mondayOfWeek
import com.example.traccerapp.utils.startOfDay
import com.example.traccerapp.utils.startOfMonth
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AnalysisPeriod(val label: String) {
    DAILY("Günlük"),
    WEEKLY("Haftalık"),
    MONTHLY("Aylık")
}

private data class DateRange(val start: Long, val endExclusive: Long)

/** Seçili döneme göre (güncel, önceki) tarih aralıklarını hesaplar. Adil kıyaslama için
 *  hafta/ay karşılaştırmaları her zaman "bugüne kadar geçen gün sayısı" ile eşleştirilir
 *  (örn. Salı günü: bu haftanın Pzt-Sal'ı geçen haftanın Pzt-Sal'ıyla kıyaslanır). */
private fun analysisRanges(period: AnalysisPeriod, today: Long): Pair<DateRange, DateRange> = when (period) {
    AnalysisPeriod.DAILY -> {
        DateRange(today, today + DAY_MS) to DateRange(today - DAY_MS, today)
    }
    AnalysisPeriod.WEEKLY -> {
        val weekStart = mondayOfWeek(today)
        val daysElapsed = ((today - weekStart) / DAY_MS).toInt() + 1
        val current = DateRange(weekStart, weekStart + daysElapsed * DAY_MS)
        val prevWeekStart = weekStart - 7 * DAY_MS
        val previous = DateRange(prevWeekStart, prevWeekStart + daysElapsed * DAY_MS)
        current to previous
    }
    AnalysisPeriod.MONTHLY -> {
        val monthStart = startOfMonth(today)
        val daysElapsed = ((today - monthStart) / DAY_MS).toInt() + 1
        val current = DateRange(monthStart, monthStart + daysElapsed * DAY_MS)
        val prevMonthCal = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, -1) }
        val prevMonthStart = prevMonthCal.timeInMillis
        val prevMonthDays = prevMonthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cappedDays = daysElapsed.coerceAtMost(prevMonthDays)
        val previous = DateRange(prevMonthStart, prevMonthStart + cappedDays * DAY_MS)
        current to previous
    }
}

/** current, previous'a göre yüzde değişim. previous == 0 ise kıyaslanacak temel yok → null. */
private fun percentChange(current: Long, previous: Long): Float? {
    if (previous == 0L) return null
    return ((current - previous).toFloat() / previous.toFloat()) * 100f
}

private data class SessionBucket(val label: String, val count: Int, val fraction: Float)

/** Bozuk satırları (endMs <= startMs) at, oturumu başladığı güne göre aralığa dahil et. */
private fun sessionsInRange(sessions: List<PhoneSession>, range: DateRange): List<PhoneSession> =
    sessions.filter { it.endMs > it.startMs && it.startMs >= range.start && it.startMs < range.endExclusive }

private fun medianDurationMs(sessions: List<PhoneSession>): Long {
    if (sessions.isEmpty()) return 0L
    val sorted = sessions.map { it.endMs - it.startMs }.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}

/** Oturum süresi dağılımı: <1dk / 1-5dk / 5-15dk / 15dk+. */
private fun durationBuckets(sessions: List<PhoneSession>): List<SessionBucket> {
    val oneMinMs = 60_000L
    val fiveMinMs = 5 * 60_000L
    val fifteenMinMs = 15 * 60_000L
    val counts = IntArray(4)
    sessions.forEach {
        val d = it.endMs - it.startMs
        val idx = when {
            d < oneMinMs -> 0
            d < fiveMinMs -> 1
            d < fifteenMinMs -> 2
            else -> 3
        }
        counts[idx]++
    }
    val total = sessions.size.coerceAtLeast(1)
    val labels = listOf("<1 dk", "1-5 dk", "5-15 dk", "15+ dk")
    return labels.indices.map { SessionBucket(labels[it], counts[it], counts[it].toFloat() / total) }
}

/** Basit eşiklerle "kullanım tarzı" özeti. Eşikler kasıtlı olarak bu tek fonksiyonda sabit tutuldu. */
private fun usageStyleSummary(count: Int, medianMs: Long): String {
    val medianMin = medianMs / 60_000
    return when {
        count == 0 -> "Bu dönemde oturum verisi yok."
        count >= 30 && medianMin < 2 -> "Sık sık kısa süreli bakıyorsun."
        count < 15 && medianMin >= 5 -> "Az ama uzun oturumlarla kullanıyorsun."
        else -> "Dengeli bir kullanım tarzın var."
    }
}

private val RANGE_LABEL_FORMAT = SimpleDateFormat("d MMM", Locale.forLanguageTag("tr"))
private fun formatRangeLabel(range: DateRange): String {
    val startLabel = RANGE_LABEL_FORMAT.format(Date(range.start))
    val endLabel = RANGE_LABEL_FORMAT.format(Date(range.endExclusive - DAY_MS))
    return if (range.start == range.endExclusive - DAY_MS) startLabel else "$startLabel – $endLabel"
}

@Composable
fun UsageAnalysisTab() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val prefs = remember { UserPreferences(context) }

    var selectedPeriod by remember { mutableStateOf(AnalysisPeriod.DAILY) }
    val today = remember { startOfDay(System.currentTimeMillis()) }

    // İki ay geriye kadar tek seferde çekilen geniş aralık — sekme değişince yeniden sorgu atmaz,
    // kıyaslama Kotlin tarafında hesaplanır (bkz. CLAUDE.md madde 5).
    val fetchStart = remember(today) { today - 65 * DAY_MS }

    val logsFlow = remember(fetchStart) { db.appUsageDao().getUsageLogsBetween(fetchStart, today + DAY_MS) }
    val rawLogs by logsFlow.collectAsState(initial = null)
    // Hidden packages + minimum kullanım filtresi — Dashboard/Raporlar ile aynı ortak yardımcı (madde 25)
    val logs = remember(rawLogs, prefs.hiddenPackages, prefs.minimumUsageMs) {
        rawLogs?.filterVisible(prefs)
    }

    val unlockEventsFlow = remember(fetchStart) { db.appUsageDao().getUnlockEventsBetween(fetchStart, today + DAY_MS) }
    val unlockEvents by unlockEventsFlow.collectAsState(initial = null)

    val phoneSessionsFlow = remember(fetchStart) { db.appUsageDao().getPhoneSessionsBetween(fetchStart, today + DAY_MS) }
    val phoneSessions by phoneSessionsFlow.collectAsState(initial = null)

    val (currentRange, previousRange) = remember(selectedPeriod, today) { analysisRanges(selectedPeriod, today) }

    val currentUsageMs = remember(logs, currentRange) {
        (logs ?: emptyList()).filter { it.date >= currentRange.start && it.date < currentRange.endExclusive }.sumOf { it.durationMs }
    }
    val previousUsageMs = remember(logs, previousRange) {
        (logs ?: emptyList()).filter { it.date >= previousRange.start && it.date < previousRange.endExclusive }.sumOf { it.durationMs }
    }
    val currentUnlocks = remember(unlockEvents, currentRange) {
        (unlockEvents ?: emptyList()).count { it.timestampMs >= currentRange.start && it.timestampMs < currentRange.endExclusive }
    }
    val previousUnlocks = remember(unlockEvents, previousRange) {
        (unlockEvents ?: emptyList()).count { it.timestampMs >= previousRange.start && it.timestampMs < previousRange.endExclusive }
    }

    val currentSessions = remember(phoneSessions, currentRange) {
        sessionsInRange(phoneSessions ?: emptyList(), currentRange)
    }
    val previousSessions = remember(phoneSessions, previousRange) {
        sessionsInRange(phoneSessions ?: emptyList(), previousRange)
    }
    val currentSessionCount = currentSessions.size
    val previousSessionCount = previousSessions.size
    val currentAvgSessionMs = remember(currentSessions) {
        if (currentSessions.isEmpty()) 0L else currentSessions.sumOf { it.endMs - it.startMs } / currentSessions.size
    }
    val previousAvgSessionMs = remember(previousSessions) {
        if (previousSessions.isEmpty()) 0L else previousSessions.sumOf { it.endMs - it.startMs } / previousSessions.size
    }
    val currentMedianSessionMs = remember(currentSessions) { medianDurationMs(currentSessions) }
    val currentSessionBuckets = remember(currentSessions) { durationBuckets(currentSessions) }

    val isLoading = logs == null || unlockEvents == null || phoneSessions == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Kullanım Analizi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        PeriodSelector(selected = selectedPeriod, onSelect = { selectedPeriod = it })

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${formatRangeLabel(currentRange)} • önceki: ${formatRangeLabel(previousRange)}",
            color = TextHint,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PurplePrimary)
            }
        } else {
            TrendCard(
                title = "Ekran Süresi",
                icon = Icons.Default.Timelapse,
                currentLabel = AppInfoUtils.formatDuration(currentUsageMs),
                previousLabel = AppInfoUtils.formatDuration(previousUsageMs),
                percentChange = percentChange(currentUsageMs, previousUsageMs)
            )

            Spacer(modifier = Modifier.height(12.dp))

            TrendCard(
                title = "Telefon Açılma Sıklığı",
                icon = Icons.Default.PhoneAndroid,
                currentLabel = "$currentUnlocks kez",
                previousLabel = "$previousUnlocks kez",
                percentChange = percentChange(currentUnlocks.toLong(), previousUnlocks.toLong())
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Oturum Davranışı", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = usageStyleSummary(currentSessionCount, currentMedianSessionMs),
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))

            TrendCard(
                title = "Açılış Sayısı (Oturum)",
                icon = Icons.Default.PhoneAndroid,
                currentLabel = "$currentSessionCount kez",
                previousLabel = "$previousSessionCount kez",
                percentChange = percentChange(currentSessionCount.toLong(), previousSessionCount.toLong())
            )

            Spacer(modifier = Modifier.height(12.dp))

            TrendCard(
                title = "Ortalama Oturum Süresi",
                icon = Icons.Default.Timelapse,
                currentLabel = AppInfoUtils.formatDuration(currentAvgSessionMs),
                previousLabel = AppInfoUtils.formatDuration(previousAvgSessionMs),
                percentChange = percentChange(currentAvgSessionMs, previousAvgSessionMs),
                secondaryLabel = "Medyan: ${AppInfoUtils.formatDuration(currentMedianSessionMs)}"
            )

            Spacer(modifier = Modifier.height(12.dp))

            SessionDurationDistribution(buckets = currentSessionBuckets, isEmpty = currentSessions.isEmpty())
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PeriodSelector(selected: AnalysisPeriod, onSelect: (AnalysisPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .padding(4.dp)
    ) {
        AnalysisPeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) PurplePrimary else Color.Transparent)
                    .clickable { onSelect(period) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = period.label,
                    color = if (isSelected) Color.White else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun TrendCard(
    title: String,
    icon: ImageVector,
    currentLabel: String,
    previousLabel: String,
    percentChange: Float?,
    secondaryLabel: String? = null
) {
    val isIncrease = (percentChange ?: 0f) > 0.5f
    val isDecrease = (percentChange ?: 0f) < -0.5f
    val deltaColor = when {
        percentChange == null -> TextHint
        isIncrease -> StatusRed
        isDecrease -> StatusGreen
        else -> TextSecondary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = PurpleLight, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(currentLabel, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (percentChange != null) {
                    Icon(
                        imageVector = if (isIncrease) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = deltaColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "%${abs(percentChange).roundToInt()}",
                        color = deltaColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("önceki döneme göre", color = TextHint, fontSize = 12.sp)
                } else {
                    Text("Önceki dönemde veri yok", color = TextHint, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Önceki: $previousLabel", color = TextHint, fontSize = 11.sp)
            if (secondaryLabel != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(secondaryLabel, color = TextHint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SessionDurationDistribution(buckets: List<SessionBucket>, isEmpty: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .padding(20.dp)
    ) {
        Column {
            Text("Oturum Süresi Dağılımı", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))
            if (isEmpty) {
                Text("Bu dönemde oturum verisi yok", color = TextHint, fontSize = 12.sp)
            } else {
                buckets.forEach { bucket ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = bucket.label,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(56.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(DarkBorder)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(bucket.fraction.coerceIn(0f, 1f))
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(PurplePrimary)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${bucket.count} (%${(bucket.fraction * 100).roundToInt()})",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(64.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
