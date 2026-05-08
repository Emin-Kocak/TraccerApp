package com.example.traccerapp.data

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("traccer_prefs", Context.MODE_PRIVATE)

    var dailyGoalHours: Int
        get() = prefs.getInt("daily_goal_hours", 6)
        set(value) { prefs.edit().putInt("daily_goal_hours", value).apply() }

    val dailyGoalMs: Long get() = dailyGoalHours * 3600 * 1000L
    val dailyGoalSeconds: Int get() = dailyGoalHours * 3600

    // Dashboard'da ve listelerde gizlenecek paket adları
    var hiddenPackages: Set<String>
        get() = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("hidden_packages", value).apply() }

    fun hidePackage(packageName: String) {
        hiddenPackages = hiddenPackages + packageName
    }

    fun showPackage(packageName: String) {
        hiddenPackages = hiddenPackages - packageName
    }

    fun isHidden(packageName: String): Boolean = packageName in hiddenPackages

    // Sistem uygulamalarını listede göster/gizle (sadece launcher dışı user apps için)
    var showNonLauncherApps: Boolean
        get() = prefs.getBoolean("show_non_launcher_apps", false)
        set(value) { prefs.edit().putBoolean("show_non_launcher_apps", value).apply() }

    // Minimum gösterilecek kullanım süresi (ms) - kısa açılışları filtreler
    var minimumUsageMs: Long
        get() = prefs.getLong("minimum_usage_ms", 0L)
        set(value) { prefs.edit().putLong("minimum_usage_ms", value).apply() }

    // Bildirim: günlük hedef aşıldığında uyar
    var goalNotificationsEnabled: Boolean
        get() = prefs.getBoolean("goal_notifications", true)
        set(value) { prefs.edit().putBoolean("goal_notifications", value).apply() }
}