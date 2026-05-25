package com.aeriotv.android.feature.settings

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.DriveSyncWorker
import com.aeriotv.android.core.sync.SyncCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs SyncSettingsScreen. Drives the two-step sign-in (Credential Manager
 * identity → Drive scope authorization) and serializes the push-then-pull
 * sync flow.
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val sync: DriveSyncManager,
) : ViewModel() {

    val masterEnabled: Flow<Boolean> = prefs.syncMasterEnabled
    fun setMasterEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setSyncMasterEnabled(value)
            if (value) DriveSyncWorker.enqueuePeriodic(context)
            else DriveSyncWorker.cancel(context)
        }
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

    suspend fun signInWithGoogle(activity: Activity): String? =
        sync.signInWithGoogle(activity)

    suspend fun requestDriveScope(): DriveSyncManager.RequestResult? =
        sync.requestDriveScope()

    fun acceptConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { sync.acceptConsentResult(data) }
    }

    /**
     * Silently restore the Drive session from the persisted token (or refresh
     * with no UI when it has lapsed) so the screen reflects signed-in and Sync
     * Now works without a manual re-login. Safe no-op when never signed in.
     */
    fun restoreSessionIfPossible() {
        viewModelScope.launch { sync.ensureSignedIn() }
    }

    fun signOut() {
        viewModelScope.launch {
            sync.signOut()
            DriveSyncWorker.cancel(context)
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

    /**
     * Pull-only counterpart to [syncNow], used by the Welcome onboarding
     * "Sign in with Google" pill. Right after the user authorizes the Drive
     * scope on a fresh device, we want to lift any playlists / watch progress
     * / reminders / prefs / credentials from their Drive AppData folder so
     * the device is fully usable without re-typing a server URL. Push is
     * deliberately skipped here -- a fresh install has nothing to push, and
     * a partial local state would otherwise overwrite the canonical remote
     * snapshot on a phantom write.
     *
     * Returns the count of categories that successfully pulled at least one
     * row. Callers can branch on `playlistsPulled` specifically (the first
     * value of the returned map keyed by [SyncCategory.Playlists]) to decide
     * whether to auto-advance the Welcome flow into the main app.
     */
    suspend fun restoreFromDrive(): Map<SyncCategory, Boolean> {
        val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            ?: return emptyMap()
        // Pull everything regardless of the user's per-category toggles --
        // first-launch restore wants the full snapshot. The toggles still
        // gate subsequent SyncWorker passes once the user is settled.
        return sync.pullAll(token, SyncCategory.entries.toSet())
    }
}
