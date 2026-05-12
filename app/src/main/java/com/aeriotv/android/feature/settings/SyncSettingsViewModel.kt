package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.SyncCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs SyncSettingsScreen. Bridges the DataStore-backed toggles and timestamps
 * to DriveSyncManager's signed-in/out state, and serializes the two pushes
 * that "Sync Now" actually performs (push then pull, in that order so local
 * changes win on conflict by virtue of being remote-stamped after).
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val sync: DriveSyncManager,
) : ViewModel() {

    val masterEnabled: Flow<Boolean> = prefs.syncMasterEnabled
    fun setMasterEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setSyncMasterEnabled(value) }
    }

    val accountEmail: Flow<String> = prefs.syncAccountEmail
    val lastPushAt: Flow<Long> = prefs.syncLastPushAt
    val lastPullAt: Flow<Long> = prefs.syncLastPullAt
    val driveStatus: StateFlow<DriveSyncManager.Status> = sync.status

    fun categoryEnabled(category: SyncCategory): Flow<Boolean> =
        prefs.syncCategoryEnabled(category)

    fun setCategoryEnabled(category: SyncCategory, value: Boolean) {
        viewModelScope.launch { prefs.setSyncCategoryEnabled(category, value) }
    }

    suspend fun connect() {
        sync.requestAuthorization()
    }

    fun signOut() {
        viewModelScope.launch {
            sync.signOut()
            prefs.setSyncAccountEmail("")
        }
    }

    suspend fun clearRemote(): Boolean {
        val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            ?: return false
        return runCatching { sync.clearRemote(token); true }.getOrDefault(false)
    }

    suspend fun syncNow(): Map<SyncCategory, Boolean> {
        val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            ?: return emptyMap()
        val enabled = SyncCategory.entries
            .filter { prefs.syncCategoryEnabled(it).first() }
            .toSet()
        val pushed = sync.pushAll(token, enabled)
        val pulled = sync.pullAll(token, enabled)
        return pushed.mapValues { (cat, ok) -> ok && (pulled[cat] != false) }
    }
}
