package com.example.traccerapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_logs",
    indices = [Index(value = ["packageName", "date"], unique = true)]
)
data class UsageLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val appName: String = "",
    val date: Long, // Start of day in MS
    val durationMs: Long
)

@Entity(tableName = "unlock_events")
data class UnlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long
)

/** Telefonu ele alıp bırakma arası — ACTION_USER_PRESENT'ten bir sonraki ACTION_SCREEN_OFF'a kadar. */
@Entity(tableName = "phone_sessions")
data class PhoneSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long
)

@Entity(tableName = "app_limits")
data class AppLimit(
    @PrimaryKey val packageName: String,
    val appName: String,
    // Daily time limit
    val dailyLimitMinutes: Int = 0,        // 0 = no limit
    val isTimeLimitEnabled: Boolean = false,
    // Schedule blocking
    val isScheduleEnabled: Boolean = false,
    val blockedDays: String = "",           // comma-separated: "MON,TUE,WED"
    val blockStartHour: Int = 22,           // 22:00
    val blockStartMinute: Int = 0,
    val blockEndHour: Int = 8,              // 08:00
    val blockEndMinute: Int = 0,
)
