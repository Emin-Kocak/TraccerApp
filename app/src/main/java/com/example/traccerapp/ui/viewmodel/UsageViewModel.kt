package com.example.traccerapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.traccerapp.data.UsageLog
import com.example.traccerapp.utils.UsageStatsUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UsageViewModel(application: Application) : AndroidViewModel(application) {

    private val _todayUsageLogs = MutableStateFlow<List<UsageLog>?>(null)
    val todayUsageLogs: StateFlow<List<UsageLog>?> = _todayUsageLogs.asStateFlow()

    fun refreshUsageStats() {
        viewModelScope.launch {
            val liveLogs = UsageStatsUtils.fetchAndSaveUsageStats(getApplication())
            _todayUsageLogs.value = liveLogs
        }
    }
}
