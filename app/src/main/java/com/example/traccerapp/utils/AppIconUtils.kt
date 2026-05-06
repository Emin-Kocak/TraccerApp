package com.example.traccerapp.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object AppIconUtils {

    /**
     * Kesinlikle takip edilmeyecek çekirdek sistem bileşenleri.
     */
    private val CORE_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.providers.settings",
        "com.android.providers.media",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.shell",
        "com.android.inputdevices",
        "com.android.providers.downloads",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.ext.services",
        "com.google.android.providers.media.module"
    )

    // Launcher'da görünen uygulamalar — performans için cache'lenir
    @Volatile
    private var launcherAppsCache: Set<String>? = null
    private var cacheTimestamp: Long = 0
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 dakika

    /**
     * Launcher'da görünen tüm uygulama paket adlarını döndürür.
     * 5 dakika boyunca cache'te tutulur.
     */
    private fun getLauncherApps(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = launcherAppsCache
        if (cached != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cached
        }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val result = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        launcherAppsCache = result
        cacheTimestamp = now
        return result
    }

    fun shouldTrack(context: Context, packageName: String): Boolean {
        // Çekirdek sistem bileşenlerini hariç tut
        if (CORE_SYSTEM_PACKAGES.contains(packageName)) return false

        // Kendi uygulamamızı hariç tut
        if (packageName == context.packageName) return false

        return try {
            // Birincil Filtre: Launcher'da görünüyorsa takip et
            // Bu Instagram, YouTube, Galaxy Store, WhatsApp vs. hepsini yakalar
            val launcherApps = getLauncherApps(context)
            if (packageName in launcherApps) return true

            // İkincil Filtre: Launcher'da olmayan ama kullanıcı tarafından yüklenmiş uygulamalar
            val pm = context.packageManager
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                val isUserApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                if (isUserApp) return true
            } catch (_: PackageManager.NameNotFoundException) {
                // Paket artık yüklü değil, takip etme
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cache'i temizler — uygulama listesi değiştiğinde çağrılabilir
     */
    fun invalidateCache() {
        launcherAppsCache = null
        cacheTimestamp = 0
    }
}

