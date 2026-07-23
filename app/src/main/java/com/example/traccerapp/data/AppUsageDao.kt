package com.example.traccerapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {
    @Query("SELECT * FROM usage_logs WHERE date = :date")
    fun getUsageLogsForDate(date: Long): Flow<List<UsageLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageLog(log: UsageLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageLogs(logs: List<UsageLog>)

    @Query("SELECT * FROM app_limits")
    fun getAllLimits(): Flow<List<AppLimit>>

    @Query("SELECT * FROM usage_logs WHERE date BETWEEN :startDate AND :endDate")
    fun getUsageLogsBetween(startDate: Long, endDate: Long): Flow<List<UsageLog>>

    @Query("SELECT * FROM app_limits WHERE packageName = :packageName")
    suspend fun getLimitForApp(packageName: String): AppLimit?

    @Query("SELECT * FROM app_limits WHERE isTimeLimitEnabled = 1 OR isScheduleEnabled = 1 OR isSessionPromptEnabled = 1")
    fun getActiveLimits(): Flow<List<AppLimit>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateLimit(limit: AppLimit)

    @Delete
    suspend fun deleteLimit(limit: AppLimit)

    @Query("SELECT * FROM unlock_events WHERE timestampMs BETWEEN :startMs AND :endMs")
    fun getUnlockEventsBetween(startMs: Long, endMs: Long): Flow<List<UnlockEvent>>

    @Query("SELECT * FROM phone_sessions WHERE startMs BETWEEN :startMs AND :endMs ORDER BY startMs")
    fun getPhoneSessionsBetween(startMs: Long, endMs: Long): Flow<List<PhoneSession>>

    @Insert
    suspend fun insertUnlockEvents(events: List<UnlockEvent>)

    @Insert
    suspend fun insertPhoneSessions(sessions: List<PhoneSession>)

    @Query("DELETE FROM unlock_events WHERE timestampMs BETWEEN :startMs AND :endMs")
    suspend fun deleteUnlockEventsBetween(startMs: Long, endMs: Long)

    @Query("DELETE FROM phone_sessions WHERE startMs BETWEEN :startMs AND :endMs")
    suspend fun deletePhoneSessionsBetween(startMs: Long, endMs: Long)

    /**
     * OS'ten türetilen unlock/oturum verisiyle kapsama penceresini atomik değiştirir
     * (PhoneActivitySync'in tek yazma noktası). Pencere dışı eski satırlar donuk arşiv.
     */
    @Transaction
    suspend fun replacePhoneActivityWindow(
        windowStartMs: Long,
        windowEndMs: Long,
        sessions: List<PhoneSession>,
        unlocks: List<UnlockEvent>
    ) {
        deletePhoneSessionsBetween(windowStartMs, windowEndMs)
        deleteUnlockEventsBetween(windowStartMs, windowEndMs)
        insertPhoneSessions(sessions)
        insertUnlockEvents(unlocks)
    }

    /**
     * In-memory takipten gelen süreci DB'ye verimli şekilde yaz.
     * REPLACE stratejisi (packageName, date) unique index'i üzerinden çalışır.
     */
    @Transaction
    suspend fun upsertDurations(logs: List<UsageLog>) {
        insertUsageLogs(logs) // OnConflictStrategy.REPLACE ile var olanın üzerine yazar
    }
}
