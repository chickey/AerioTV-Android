package com.aeriotv.android.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.update.AppUpdateManager
import com.aeriotv.android.core.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that mediates between [AppUpdateManager] and the UI (Settings row
 * and UpdatePromptDialog). Exposes the manager's state and handles the
 * auto-reset to [AppUpdateManager.State.Idle] after non-critical feedback
 * (UpToDate, Error from a manual check) so the Settings row doesn't stay
 * stuck in a terminal state.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: AppUpdateManager,
) : ViewModel() {

    val state: StateFlow<AppUpdateManager.State> = manager.state

    /** Manual check from Settings. Resets to Idle after a brief feedback period. */
    fun checkNow() {
        viewModelScope.launch {
            manager.checkNow()
            // If the check ended without finding an update (UpToDate or Error),
            // auto-reset to Idle after a few seconds so the row returns to normal.
            val finalState = manager.state.value
            if (finalState is AppUpdateManager.State.UpToDate ||
                finalState is AppUpdateManager.State.Error
            ) {
                delay(5_000)
                if (manager.state.value == finalState) manager.dismiss()
            }
        }
    }

    fun startDownload(info: UpdateInfo) {
        viewModelScope.launch { manager.startDownload(info) }
    }

    fun install(apkFile: File) = manager.install(apkFile)

    fun dismiss() = manager.dismiss()
}
