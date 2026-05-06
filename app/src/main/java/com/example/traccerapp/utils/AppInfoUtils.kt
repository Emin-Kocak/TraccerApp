package com.example.traccerapp.utils

import android.content.Context
import android.content.pm.PackageManager

object AppInfoUtils {

    // Paket adından anlamlı isim çıkarırken atlanacak genel segmentler
    private val GENERIC_SEGMENTS = setOf(
        "com", "org", "net", "android", "app", "apps", "mobile",
        "google", "samsung", "huawei", "xiaomi", "oppo", "vivo",
        "example", "oneplus", "sec", "lge", "hmd", "motorola"
    )

    /**
     * Uygulamanın kullanıcı-dostu adını döndürür.
     * 1. Önce PackageManager ile resmi label'ı dener
     * 2. Başarısız olursa paket adının en anlamlı segmentini seçer
     */
    fun getAppName(context: Context, packageName: String): String {
        // Deneme 1: Normal flag ile
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(info).toString()
            // Bazı uygulamalar label olarak paket adını döndürüyor, kontrol et
            if (label.isNotBlank() && label != packageName) {
                return label
            }
        } catch (_: Exception) { }

        // Deneme 2: GET_META_DATA flag ile (bazı uygulamalar farklı davranıyor)
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val label = pm.getApplicationLabel(info).toString()
            if (label.isNotBlank() && label != packageName) {
                return label
            }
        } catch (_: Exception) { }

        // Fallback: Paket adından en anlamlı segmenti çıkar
        return extractReadableName(packageName)
    }

    /**
     * "com.instagram.android" → "Instagram"
     * "com.whatsapp" → "Whatsapp"
     * "com.google.android.youtube" → "Youtube"
     */
    private fun extractReadableName(packageName: String): String {
        val segments = packageName.split(".")
        // Genel olmayan en anlamlı segmenti bul (sondan başa doğru ara)
        val meaningful = segments
            .filter { it.lowercase() !in GENERIC_SEGMENTS && it.length > 1 }

        val bestSegment = meaningful.lastOrNull()
            ?: segments.lastOrNull()
            ?: packageName

        return bestSegment.replaceFirstChar { it.uppercase() }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}sa ${minutes}dk"
            minutes > 0 -> "${minutes}dk ${seconds}sn"
            else -> "${seconds}sn"
        }
    }
}
