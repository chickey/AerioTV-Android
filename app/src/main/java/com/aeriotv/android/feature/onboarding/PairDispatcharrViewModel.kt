package com.aeriotv.android.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.sync.DispatcharrDiscoveryClient
import com.aeriotv.android.core.sync.DispatcharrDiscoveryResult
import com.aeriotv.android.core.sync.DispatcharrPairingClient
import com.aeriotv.android.core.sync.DispatcharrPairingStartRequest
import com.aeriotv.android.core.sync.DispatcharrPairingStartResponse
import com.aeriotv.android.core.sync.DispatcharrPairingStatusResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PairDispatcharrViewModel @Inject constructor(
    private val discoveryClient: DispatcharrDiscoveryClient,
    private val pairingClient: DispatcharrPairingClient,
) : ViewModel() {
    private val _state = MutableStateFlow<PairDispatcharrState>(PairDispatcharrState.Idle)
    val state: StateFlow<PairDispatcharrState> = _state.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingFlowState>(PairingFlowState.Idle)
    val pairingState: StateFlow<PairingFlowState> = _pairingState.asStateFlow()

    private var activePairingBaseUrl: String? = null

    fun discover() {
        if (_state.value is PairDispatcharrState.Scanning) return
        activePairingBaseUrl = null
        _pairingState.value = PairingFlowState.Idle
        _state.value = PairDispatcharrState.Scanning
        viewModelScope.launch {
            val result = discoveryClient.discover()
            _state.value = if (result != null) {
                PairDispatcharrState.Found(result)
            } else {
                PairDispatcharrState.NotFound
            }
        }
    }

    fun startPairing(result: DispatcharrDiscoveryResult) {
        if (activePairingBaseUrl == result.baseUrl && _pairingState.value !is PairingFlowState.Failed) return
        activePairingBaseUrl = result.baseUrl
        _pairingState.value = PairingFlowState.Starting
        viewModelScope.launch {
            try {
                val started = pairingClient.startPairing(
                    baseUrl = result.baseUrl,
                    request = DispatcharrPairingStartRequest(
                        deviceName = android.os.Build.MODEL ?: "Fire TV",
                        appVersion = BuildConfig.VERSION_NAME,
                        capabilities = listOf("live_tv", "dvr", "sync", "multiview"),
                    ),
                )
                _pairingState.value = PairingFlowState.Waiting(started)
                pollUntilApproved(result.baseUrl, started)
            } catch (t: Throwable) {
                _pairingState.value = PairingFlowState.Failed(t.message ?: "Could not start pairing.")
            }
        }
    }

    private suspend fun pollUntilApproved(
        baseUrl: String,
        started: DispatcharrPairingStartResponse,
    ) {
        while (true) {
            delay((started.pollIntervalSeconds.coerceAtLeast(1) * 1_000L))
            val status = try {
                pairingClient.pairingStatus(baseUrl, started.pairingId)
            } catch (t: Throwable) {
                _pairingState.value = PairingFlowState.Failed(t.message ?: "Could not read pairing status.")
                return
            }
            when (status.status) {
                "approved" -> {
                    _pairingState.value = PairingFlowState.Approved(status)
                    return
                }
                "denied", "expired", "revoked" -> {
                    _pairingState.value = PairingFlowState.Failed("Pairing ${status.status}. Scan again to retry.")
                    return
                }
                else -> _pairingState.value = PairingFlowState.Waiting(started)
            }
        }
    }
}

sealed interface PairDispatcharrState {
    data object Idle : PairDispatcharrState
    data object Scanning : PairDispatcharrState
    data object NotFound : PairDispatcharrState
    data class Found(val result: DispatcharrDiscoveryResult) : PairDispatcharrState
}

sealed interface PairingFlowState {
    data object Idle : PairingFlowState
    data object Starting : PairingFlowState
    data class Waiting(val pairing: DispatcharrPairingStartResponse) : PairingFlowState
    data class Approved(val approved: DispatcharrPairingStatusResponse) : PairingFlowState
    data class Failed(val message: String) : PairingFlowState
}
