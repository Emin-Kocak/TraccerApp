package com.example.traccerapp.data

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("traccer_prefs", Context.MODE_PRIVATE)

    var dailyGoalHours: Int
        get() = prefs.getInt("daily_goal_hours", 6)
        set(value) { prefs.edit().putInt("daily_goal_hours", value).apply() }

    val dailyGoalMs: Long get() = dailyGoalHours * 3600 * 1000L
    val dailyGoalSeconds: Int get() = dailyGoalHours * 3600
}
