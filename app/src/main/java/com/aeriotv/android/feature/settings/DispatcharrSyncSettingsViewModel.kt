package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DispatcharrSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DispatcharrSyncSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val sync: DispatcharrSyncManager,
) : ViewModel() {
    val lastPushAt: Flow<Long> = prefs.syncLastPushAt
    val lastPullAt: Flow<Long> = prefs.syncLastPullAt
    val status: StateFlow<DispatcharrSyncManager.Status> = sync.status

    fun pull() {
        viewModelScope.launch { sync.pull() }
    }

    fun push() {
        viewModelScope.launch { sync.push() }
    }

    fun syncNow() {
        viewModelScope.launch { sync.syncNow() }
    }
}
