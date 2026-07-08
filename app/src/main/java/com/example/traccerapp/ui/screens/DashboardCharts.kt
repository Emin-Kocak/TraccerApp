package com.example.traccerapp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.traccerapp.data.PhoneSession
import com.example.traccerapp.ui.theme.*
import com.example.traccerapp.utils.AppCategory
import com.example.traccerapp.utils.AppInfoUtils

// ─── Kategoriye Göre Kullanım Çemberi (Dashboard) ─────────────

@Composable
fun CategoryDonutChart(
    categoryTotals: List<Pair<AppCategory, Long>>,
    modifier: Modifier = Modifier
) {
    val total = categoryTotals.sumOf { it.second }.coerceAtLeast(1L)

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(110.dp)) {
            val strokeWidth = size.minDimension * 0.24f
            var startAngle = -90f
            categoryTotals.forEach { (category, ms) ->
                val sweep = 360f * (ms.toFloat() / total.toFloat())
                if (sweep > 0f) {
                    drawArc(
                        color = categoryColor(category),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth),
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth)
                    )
                    startAngle += sweep
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            categoryTotals.forEach { (category, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(categoryColor(category))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(category.label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(AppInfoUtils.formatDuration(ms), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─── Saatlik X/Y Bar Grafiği (kullanım dakikası veya açılma sayısı) ──

private fun hourAxisLabel(hour: Int): String = when (hour) {
    0 -> "12AM"
    12 -> "12PM"
    in 1..11 -> "${hour}AM"
    else -> "${hour - 12}PM"
}

@Composable
fun HourlyBarChart(
    values: List<Int>,
    barColor: Color,
    valueLabel: (Int) -> String,
    modifier: Modifier = Modifier
) {
    var selectedHour by remember(values) { mutableStateOf<Int?>(null) }
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val barAreaHeight = 90.dp

    Column(modifier = modifier.fillMaxWidth()) {
        // Seçili saatin detayı (sabit yükseklik — seçim yokken de layout zıplamasın)
        Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            selectedHour?.let { hour ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatHour(hour), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(valueLabel(values[hour]), color = barColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(barAreaHeight),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { hour, value ->
                val isSelected = hour == selectedHour
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selectedHour = if (isSelected) null else hour },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val barHeight = barAreaHeight * (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(if (value > 0) barHeight else 2.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(
                                when {
                                    isSelected -> barColor
                                    value > 0 -> barColor.copy(alpha = 0.55f)
                                    else -> DarkBorder
                                }
                            )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { hour ->
                Text(hourAxisLabel(hour), color = TextHint, fontSize = 9.sp)
            }
        }
    }
}

// ─── Telefon Kullanım Oturumları (pickup → hangup) Zaman Çizelgesi ──

private const val DAY_MS_CHART = 24L * 60 * 60 * 1000L

@Composable
fun PhoneSessionTimeline(
    sessions: List<PhoneSession>,
    dayStart: Long,
    modifier: Modifier = Modifier
) {
    val trackColor = DarkBorder
    val trackHeight = 28.dp

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(color = trackColor, cornerRadius = radius)
            sessions.forEach { session ->
                val startFrac = ((session.startMs - dayStart).toFloat() / DAY_MS_CHART).coerceIn(0f, 1f)
                val endFrac = ((session.endMs - dayStart).toFloat() / DAY_MS_CHART).coerceIn(0f, 1f)
                val x = startFrac * size.width
                val w = ((endFrac - startFrac) * size.width).coerceAtLeast(3f)
                drawRoundRect(
                    color = PurplePrimary,
                    topLeft = Offset(x, 0f),
                    size = Size(w, size.height),
                    cornerRadius = radius
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { hour ->
                Text(hourAxisLabel(hour), color = TextHint, fontSize = 9.sp)
            }
        }
    }
}
