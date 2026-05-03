package com.example.traccerapp.ui.screens

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

val TraccerGreen = Color(0xFF4CAF50)
val TraccerCyan = Color(0xFF00BCD4)
val TraccerLightCyan = Color(0xFFE0F7FA)
val TraccerGray = Color.Gray

// Birleştirilmiş oturum — tek uygulama veya Idle Time
data class MergedSession(
    val startTime: String,   // "09:03"
    val endTime: String,     // "09:25"
    val appName: String,
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

fun colorForApp(appName: String): Color {
    val colors = listOf(
        Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFE91E63),
        Color(0xFFFF5722), Color(0xFF9C27B0), Color(0xFF00BCD4),
        Color(0xFFFF9800), Color(0xFF607D8B), Color(0xFF795548)
    )
    return colors[Math.abs(appName.hashCode()) % colors.size]
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
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

fun formatTime(timeMs: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timeMs
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    return String.format("%02d:%02d", h, m)
}

// Ham event'lerden birleştirilmiş oturumlar üret
data class RawEvent(val pkg: String, val appName: String, val startMs: Long, val endMs: Long)

suspend fun fetchTodayHourBlocks(context: Context): List<HourBlock> =
    withContext(Dispatchers.IO) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        // 1. Ham event'leri topla
        val rawEvents = mutableListOf<RawEvent>()
        val openTimes = mutableMapOf<String, Long>()
        val events = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg == context.packageName) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    openTimes[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val openMs = openTimes[pkg] ?: continue
                    val durationMs = event.timeStamp - openMs
                    if (durationMs < 1000) {
                        openTimes.remove(pkg)
                        continue
                    }
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        pkg.substringAfterLast(".")
                    }
                    rawEvents.add(RawEvent(pkg, appName, openMs, event.timeStamp))
                    openTimes.remove(pkg)
                }
            }
        }

        // Zamana göre sırala
        rawEvents.sortBy { it.startMs }

        // 2. Ardışık aynı uygulamaları birleştir + Idle Time ekle
        val mergedAll = mutableListOf<MergedSession>()

        var i = 0
        while (i < rawEvents.size) {
            val current = rawEvents[i]

            // Bir önceki oturumun bitişiyle bu oturumun başlangıcı arasında boşluk varsa Idle Time ekle
            if (mergedAll.isNotEmpty()) {
                val lastEndMs = rawEvents[i - 1].endMs
                val gapSeconds = ((current.startMs - lastEndMs) / 1000).toInt()
                if (gapSeconds >= 5) {
                    mergedAll.add(
                        MergedSession(
                            startTime = formatTime(lastEndMs),
                            endTime = formatTime(current.startMs),
                            appName = "Idle Time",
                            durationSeconds = gapSeconds,
                            color = TraccerGray,
                            initial = "I",
                            isIdle = true
                        )
                    )
                }
            }

            // Aynı uygulamanın ardışık oturumlarını birleştir
            var mergedEnd = current.endMs
            var j = i + 1
            while (j < rawEvents.size &&
                rawEvents[j].pkg == current.pkg &&
                (rawEvents[j].startMs - mergedEnd) < 30_000L // 30 saniye içinde başlıyorsa birleştir
            ) {
                mergedEnd = rawEvents[j].endMs
                j++
            }

            val totalSec = ((mergedEnd - current.startMs) / 1000).toInt()
            mergedAll.add(
                MergedSession(
                    startTime = formatTime(current.startMs),
                    endTime = formatTime(mergedEnd),
                    appName = current.appName,
                    durationSeconds = totalSec,
                    color = colorForApp(current.appName),
                    initial = current.appName.firstOrNull()?.uppercase() ?: "?",
                    isIdle = false
                )
            )
            i = j
        }

        // 3. Saatlere böl
        val hourMap = mutableMapOf<Int, MutableList<MergedSession>>()
        for (h in 0..23) hourMap[h] = mutableListOf()

        for (session in mergedAll) {
            val sessionCal = Calendar.getInstance()
            sessionCal.timeInMillis = 0
            // startTime string'ini parse et
            val parts = session.startTime.split(":")
            val hour = parts[0].toIntOrNull() ?: 0
            hourMap[hour]?.add(session)
        }

        (0..23).map { hour ->
            HourBlock(hour = hour, sessions = hourMap[hour] ?: emptyList())
        }
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

// ===================== ANA EKRAN =====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageReportScreen(onNavigateToDetail: () -> Unit) {
    val context = LocalContext.current
    var hourBlocks by remember { mutableStateOf<List<HourBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        hourBlocks = fetchTodayHourBlocks(context)
        isLoading = false
    }

    val totalSeconds = remember(hourBlocks) {
        hourBlocks.flatMap { it.sessions }
            .filter { !it.isIdle }
            .sumOf { it.durationSeconds }
    }

    val topApps = remember(hourBlocks) {
        hourBlocks.flatMap { it.sessions }
            .filter { !it.isIdle }
            .groupBy { it.appName }
            .mapValues { (_, s) -> s.sumOf { it.durationSeconds } }
            .entries.sortedByDescending { it.value }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage Report") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        containerColor = Color.White
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TraccerCyan)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize()
            ) {
                item {
                    SummaryCard(
                        totalSeconds = totalSeconds,
                        topApps = topApps,
                        onCardClick = onNavigateToDetail  // Karta tıklayınca ayrı ekrana git
                    )
                }
            }
        }
    }
}

// ===================== ÖZET KART =====================

@Composable
fun SummaryCard(
    totalSeconds: Int,
    topApps: List<Map.Entry<String, Int>>,
    onCardClick: () -> Unit
) {
    val maxSeconds = 6 * 3600
    val sweepAngle = (270f * (totalSeconds.toFloat() / maxSeconds)).coerceIn(0f, 270f)
    val remainingSeconds = (maxSeconds - totalSeconds).coerceAtLeast(0)

    Card(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clickable { onCardClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                Canvas(modifier = Modifier.size(150.dp)) {
                    drawCircle(color = Color(0xFFEEEEEE), style = Stroke(width = 16.dp.toPx()))
                    drawArc(
                        color = TraccerCyan,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatSeconds(remainingSeconds), color = TraccerGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("LEFT", color = TraccerGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.Start) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = TraccerGreen) {}
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("USAGE TIME", color = TraccerGray, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatSeconds(totalSeconds), color = TraccerGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("/6H", color = TraccerGray, fontSize = 13.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.Start) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = TraccerCyan) {}
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SESSIONS", color = TraccerGray, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(topApps.size.toString(), color = TraccerCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(" uygulama", color = TraccerGray, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Screen time - Today (00:00–now).", color = TraccerGray, fontSize = 11.sp)

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.LightGray)

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (topApps.isNotEmpty()) {
                        val top = topApps.first()
                        AppIconLarge(top.key.firstOrNull()?.uppercase() ?: "?", colorForApp(top.key))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(top.key, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(formatSeconds(top.value), color = TraccerGray, fontSize = 10.sp)
                    } else {
                        Text("📦", fontSize = 28.sp)
                        Text("No Data", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text("MOST USED APP", color = TraccerGray, fontSize = 10.sp)
                }
                VerticalDivider(modifier = Modifier.height(70.dp).width(1.dp), color = Color.LightGray)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (topApps.size >= 2) {
                        val second = topApps[1]
                        AppIconLarge(second.key.firstOrNull()?.uppercase() ?: "?", colorForApp(second.key))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(second.key, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(formatSeconds(second.value), color = TraccerGray, fontSize = 10.sp)
                    } else {
                        Text("📦", fontSize = 28.sp)
                        Text("No Data", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text("2nd MOST USED", color = TraccerGray, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("This is based on today's usage.", color = TraccerGray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Detayları görmek için dokun →", color = TraccerCyan, fontSize = 11.sp)
        }
    }
}

// ===================== DETAY EKRANI =====================

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
        topBar = {
            TopAppBar(
                title = { Text("Günlük Detay") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        containerColor = Color.White
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TraccerCyan)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                items(timelineItems) { item ->
                    when (item) {
                        is TimelineItem.EmptyRange -> EmptyRangeRow(item)
                        is TimelineItem.ActiveHour -> ActiveHourRow(item.block)
                    }
                }
            }
        }
    }
}

// ===================== TIMELINE SATIRLAR =====================

@Composable
fun EmptyRangeRow(item: TimelineItem.EmptyRange) {
    val label = if (item.startHour == item.endHour)
        "${formatHour(item.startHour)} — No activity"
    else
        "${formatHour(item.startHour)} – ${formatHour(item.endHour)} — No activity"

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.width(2.dp).height(20.dp).background(Color.LightGray))
        Spacer(modifier = Modifier.width(60.dp))
    }
}

@Composable
fun ActiveHourRow(block: HourBlock) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrow"
    )
    val totalText = remember(block) {
        formatSeconds(block.sessions.filter { !it.isIdle }.sumOf { it.durationSeconds })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (expanded) TraccerLightCyan else Color.Transparent)
            .clickable { expanded = !expanded }
    ) {
        // Saat başlık satırı
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatHour(block.hour), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(80.dp))

            Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.width(2.dp).height(36.dp).background(TraccerCyan).align(Alignment.Center))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    block.sessions.filter { !it.isIdle }.take(3).forEach {
                        AppIconSmall(it.initial, it.color)
                    }
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TraccerCyan,
                        modifier = Modifier.size(14.dp).rotate(arrowRotation)
                    )
                }
            }

            Text(totalText, color = Color.Black, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }

        // Açılır oturum detayları
        if (expanded) {
            block.sessions.forEach { session ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Saat
                    Text(
                        text = session.startTime,
                        color = Color.Black,
                        fontSize = 12.sp,
                        modifier = Modifier.width(80.dp)
                    )

                    // İkon + dikey çizgi
                    Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.width(2.dp).height(52.dp).background(if (session.isIdle) Color.LightGray else TraccerCyan).align(Alignment.Center))
                        if (!session.isIdle) {
                            AppIconLarge(session.initial, session.color)
                        }
                    }

                    // Uygulama adı ve süre
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.appName,
                            color = Color.Black,  // Siyah yazı
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${session.startTime} – ${session.endTime}  •  ${formatSeconds(session.durationSeconds)}",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppIconSmall(initial: String, color: Color) {
    Surface(modifier = Modifier.size(20.dp).padding(1.dp), shape = CircleShape, color = color) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AppIconLarge(initial: String, color: Color) {
    Surface(modifier = Modifier.size(32.dp), shape = CircleShape, color = color) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}