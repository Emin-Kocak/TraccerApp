package com.example.traccerapp.data

import kotlinx.coroutines.flow.Flow

class AppUsageRepository(private val appUsageDao: AppUsageDao) {

    // --- KULLANIM SÜRELERİ (USAGE LOGS) ---

    fun getUsageLogsForDate(date: Long): Flow<List<UsageLog>> {
        return appUsageDao.getUsageLogsForDate(date)
    }

    suspend fun insertUsageLog(log: UsageLog) {
        appUsageDao.insertUsageLog(log)
    }

    suspend fun insertUsageLogs(logs: List<UsageLog>) {
        appUsageDao.insertUsageLogs(logs)
    }

    // --- UYGULAMA LİMİTLERİ (APP LIMITS) ---

    fun getAllLimits(): Flow<List<AppLimit>> {
        return appUsageDao.getAllLimits()
    }

    fun getActiveLimits(): Flow<List<AppLimit>> {
        return appUsageDao.getActiveLimits()
    }

    suspend fun getLimitForApp(packageName: String): AppLimit? {
        return appUsageDao.getLimitForApp(packageName)
    }

    suspend fun saveLimit(limit: AppLimit) {
        appUsageDao.insertOrUpdateLimit(limit)
    }

    suspend fun deleteLimit(limit: AppLimit) {
        appUsageDao.deleteLimit(limit)
    }
}

