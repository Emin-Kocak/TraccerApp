package com.example.traccerapp.utils

import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.data.UserPreferences

/**
 * Dashboard/Raporlar/Analiz ekranlarının hepsinde aynı görünürlük kuralını uygular:
 * kullanıcının gizlediği paketler + minimum kullanım süresi filtresi altındaki kayıtlar
 * çıkarılır. Eskiden yalnızca Dashboard bu filtreyi uyguluyordu — aynı günün toplamı
 * ekrana göre farklı görünüyordu (bkz. CLAUDE.md madde 25).
 */
fun List<UsageLog>.filterVisible(prefs: UserPreferences): List<UsageLog> {
    val hidden = prefs.hiddenPackages
    val minMs = prefs.minimumUsageMs
    return filter { it.packageName !in hidden && it.durationMs >= minMs }
}
