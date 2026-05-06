package com.example.traccerapp.ui.screens

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.data.UserPreferences
import com.example.traccerapp.ui.components.RealAppIcon
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.utils.AppIconUtils
import com.example.traccerapp.utils.AppInfoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

// ── Veri modelleri ──────────────────────────────────────────

data class MergedSession(
    val startTime: String,
    val endTime: String,
    val appName: String,
    val packageName: String = "",
    val durationSeconds: Int,
    val color: Color,
    val initial: String,
    val isIdle: Boolean = false
)

data class HourBlock(
    val hour: Int,
    val sessions: List<MergedSession>
)

sealed class TimelineItem {
    data class EmptyRange(val startHour: Int, val endHour: Int) : TimelineItem()
    data class ActiveHour(val block: HourBlock) : TimelineItem()
}

// ── Yardımcı fonksiyonlar ───────────────────────────────────

fun colorForApp(appName: String): Color {
    return AppColors[Math.abs(appName.hashCode()) % AppColors.size]
}

fun formatHour(hour: Int): String = when {
    hour == 0 -> "12:00 AM"
    hour < 12 -> "$hour:00 AM"
    hour == 12 -> "12:00 PM"
    else -> "${hour - 12}:00 PM"
}

fun formatSeconds(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}s ${m}dk"
        m > 0 -> "${m}dk ${s}sn"
        else -> "${s}sn"
    }
}

fun formatTime(timeMs: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timeMs
    return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

data class RawEvent(val pkg: String, val appName: String, val startMs: Long, val endMs: Long)

suspend fun fetchTodayHourBlocks(context: Context): List<HourBlock> =
    withContext(Dispatchers.IO) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        val rawEvents = mutableListOf<RawEvent>()
        val openTimes = mutableMapOf<String, Long>()
        val events = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue

            // Sistem uygulamalarını ve takip edilmemesi gerekenleri filtrele
            if (!AppIconUtils.shouldTrack(context, pkg)) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> openTimes[pkg] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val openMs = openTimes[pkg] ?: continue
                    val durationMs = event.timeStamp - openMs
                    if (durationMs < 1000) { openTimes.remove(pkg); continue }

                    val appName = AppInfoUtils.getAppName(context, pkg)

                    rawEvents.add(RawEvent(pkg, appName, openMs, event.timeStamp))
                    openTimes.remove(pkg)
                }
            }
        }

        rawEvents.sortBy { it.startMs }

        val mergedAll = mutableListOf<MergedSession>()
        var i = 0
        while (i < rawEvents.size) {
            val current = rawEvents[i]
            if (mergedAll.isNotEmpty()) {
                val lastEndMs = rawEvents[i - 1].endMs
                val gapSeconds = ((current.startMs - lastEndMs) / 1000).toInt()
                if (gapSeconds >= 5) {
                    mergedAll.add(MergedSession(
                        startTime = formatTime(lastEndMs),
                        endTime = formatTime(current.startMs),
                        appName = "Boşta",
                        packageName = "",
                        durationSeconds = gapSeconds,
                        color = DarkBorder,
                        initial = "I",
                        isIdle = true
                    ))
                }
            }
            var mergedEnd = current.endMs
            var j = i + 1
            while (j < rawEvents.size &&
                rawEvents[j].pkg == current.pkg &&
                (rawEvents[j].startMs - mergedEnd) < 30_000L) {
                mergedEnd = rawEvents[j].endMs
                j++
            }
            val totalSec = ((mergedEnd - current.startMs) / 1000).toInt()
            mergedAll.add(MergedSession(
                startTime = formatTime(current.startMs),
                endTime = formatTime(mergedEnd),
                appName = current.appName,
                packageName = current.pkg,
                durationSeconds = totalSec,
                color = colorForApp(current.appName),
                initial = current.appName.firstOrNull()?.uppercase() ?: "?",
                isIdle = false
            ))
            i = j
        }

        val hourMap = mutableMapOf<Int, MutableList<MergedSession>>()
        for (h in 0..23) hourMap[h] = mutableListOf()
        for (session in mergedAll) {
            val hour = session.startTime.split(":")[0].toIntOrNull() ?: 0
            hourMap[hour]?.add(session)
        }

        (0..23).map { hour -> HourBlock(hour, hourMap[hour] ?: emptyList()) }
    }
fun buildTimelineItems(blocks: List<HourBlock>): List<TimelineItem> {
    val result = mutableListOf<TimelineItem>()
    var emptyStart: Int? = null
    for (block in blocks) {
        if (block.sessions.isEmpty()) {
            if (emptyStart == null) emptyStart = block.hour
        } else {
            if (emptyStart != null) {
                result.add(TimelineItem.EmptyRange(emptyStart, block.hour - 1))
                emptyStart = null
            }
            result.add(TimelineItem.ActiveHour(block))
        }
    }
    if (emptyStart != null) result.add(TimelineItem.EmptyRange(emptyStart, 23))
    return result
}

// ── Özet Ekranı ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageReportScreen(onNavigateToDetail: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    var hourBlocks by remember { mutableStateOf<List<HourBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        hourBlocks = fetchTodayHourBlocks(context)
        isLoading = false
    }

    val totalSeconds = remember(hourBlocks) {
        hourBlocks.flatMap { it.sessions }.filter { !it.isIdle }.sumOf { it.durationSeconds }
    }
    val topApps = remember(hourBlocks) {
        hourBlocks.flatMap { it.sessions }
            .filter { !it.isIdle }
            .groupBy { it.packageName }
            .mapValues { (_, s) -> 
                val first = s.first()
                Triple(first.appName, first.packageName, s.sumOf { it.durationSeconds })
            }
            .values.sortedByDescending { it.third }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Zaman Raporu", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = TextPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PurplePrimary, strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // Ana özet kartı
                item {
                    ProSummaryHeroCard(
                        totalSeconds = totalSeconds,
                        goalSeconds = prefs.dailyGoalSeconds,
                        onDetailClick = onNavigateToDetail
                    )
                }

                // En çok kullanılan 2 uygulama
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ProTopAppCard(
                            modifier = Modifier.weight(1f),
                            label = "EN ÇOK KULLANILAN",
                            entry = topApps.getOrNull(0)
                        )
                        ProTopAppCard(
                            modifier = Modifier.weight(1f),
                            label = "2. EN ÇOK",
                            entry = topApps.getOrNull(1)
                        )
                    }
                }

                // Uygulama sıralaması
                if (topApps.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(DarkSurface)
                                .padding(20.dp)
                        ) {
                            Column {
                                Text("Uygulama Sıralaması", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                val maxSec = topApps.maxOfOrNull { it.third } ?: 1
                                topApps.take(6).forEachIndexed { index, entry ->
                                    ProRankRow(
                                        rank = index + 1,
                                        appName = entry.first,
                                        packageName = entry.second,
                                        durationSeconds = entry.third,
                                        progress = entry.third.toFloat() / maxSec,
                                        color = AppColors[index % AppColors.size]
                                    )
                                    if (index < minOf(5, topApps.size - 1)) {
                                        HorizontalDivider(
                                            color = DarkBorder,
                                            modifier = Modifier.padding(vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun ProSummaryHeroCard(totalSeconds: Int, goalSeconds: Int, onDetailClick: () -> Unit) {
    val maxSeconds = goalSeconds
    val progress = (totalSeconds.toFloat() / maxSeconds).coerceIn(0f, 1f)
    val remainingSeconds = (maxSeconds - totalSeconds).coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(PurpleDim, DarkElevated),
                    radius = 900f
                )
            )
            .clickable { onDetailClick() }
            .padding(24.dp)
    ) {
        Column {
            // Üst etiket
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(PurplePrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "BUGÜN · ${formatSeconds(totalSeconds)} kullanıldı",
                    color = PurpleLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Kalan süre büyük yazı
            Text(
                text = formatSeconds(remainingSeconds),
                color = TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp
            )
            Text("kaldı (${maxSeconds / 3600} saatlik hedef)", color = TextSecondary, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(20.dp))

            // Progress bar
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DarkBg)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(listOf(PurplePrimary, PurpleLight))
                            )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0", color = TextHint, fontSize = 10.sp)
                    Text("${maxSeconds / 3600} saat", color = TextHint, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detay butonu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PurplePrimary.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Timeline, contentDescription = null, tint = PurpleLight, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saatlik detayları gör", color = PurpleLight, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = PurpleLight, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun ProTopAppCard(modifier: Modifier, label: String, entry: Triple<String, String, Int>?) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        Column {
            Text(label, color = TextHint, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            if (entry != null) {
                RealAppIcon(packageName = entry.second, appName = entry.first, size = 40.dp, cornerRadius = 12.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(entry.first, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(formatSeconds(entry.third), color = PurpleLight, fontSize = 12.sp)
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = TextHint, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Veri yok", color = TextHint, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ProRankRow(rank: Int, appName: String, packageName: String, durationSeconds: Int, progress: Float, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$rank",
            color = TextHint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.width(12.dp))
        RealAppIcon(packageName = packageName, appName = appName, size = 36.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(appName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(formatSeconds(durationSeconds), color = TextSecondary, fontSize = 12.sp)
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

// ── Detay Ekranı ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hourBlocks by remember { mutableStateOf<List<HourBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        hourBlocks = fetchTodayHourBlocks(context)
        isLoading = false
    }

    val timelineItems = remember(hourBlocks) { buildTimelineItems(hourBlocks) }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("Saatlik Detay", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = TextPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PurplePrimary, strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(timelineItems) { item ->
                    when (item) {
                        is TimelineItem.EmptyRange -> ProEmptyRangeRow(item)
                        is TimelineItem.ActiveHour -> ProActiveHourRow(item.block)
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun ProEmptyRangeRow(item: TimelineItem.EmptyRange) {
    val label = if (item.startHour == item.endHour)
        formatHour(item.startHour)
    else
        "${formatHour(item.startHour)} – ${formatHour(item.endHour)}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DarkBorder)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = TextHint, fontSize = 12.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text("Kullanım yok", color = TextHint, fontSize = 11.sp)
    }
}

@Composable
fun ProActiveHourRow(block: HourBlock) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")

    val activeSeconds = remember(block) {
        block.sessions.filter { !it.isIdle }.sumOf { it.durationSeconds }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
    ) {
        // Saat başlık satırı
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sol: mor şerit + saat
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(listOf(PurplePrimary, PurpleLight))
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(formatHour(block.hour), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${block.sessions.size} oturum", color = TextSecondary, fontSize = 11.sp)
            }

            // Uygulama ikonları
            Row(verticalAlignment = Alignment.CenterVertically) {
                block.sessions.filter { !it.isIdle }.take(3).forEach { session ->
                    RealAppIcon(packageName = session.packageName, appName = session.appName, size = 28.dp, cornerRadius = 8.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (block.sessions.filter { !it.isIdle }.size > 3) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+${block.sessions.filter { !it.isIdle }.size - 3}", color = TextSecondary, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            Text(formatSeconds(activeSeconds), color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(18.dp).rotate(arrowRotation)
            )
        }

        // Açılır detay
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkElevated)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                block.sessions.forEach { session ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Sol şerit (idle için gri, normal için mor)
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(44.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (session.isIdle) DarkBorder else session.color)
                        )
                        Spacer(modifier = Modifier.width(12.dp))

                        if (session.isIdle) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = TextHint, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            RealAppIcon(packageName = session.packageName, appName = session.appName, size = 36.dp, cornerRadius = 10.dp)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Uygulama adı ve süre
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                session.appName,
                                color = if (session.isIdle) TextHint else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${session.startTime} – ${session.endTime}",
                                color = TextHint,
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            formatSeconds(session.durationSeconds),
                            color = if (session.isIdle) TextHint else PurpleLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
