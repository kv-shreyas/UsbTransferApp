package com.example.securequicktransferapp.presentation.vm

import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientDeviceSummary
import com.example.securequicktransferapp.domain.model.deviceinfo.DetectedDeviceSummary
import com.example.securequicktransferapp.domain.repo.IClientDeviceInfoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ClientDeviceInfoUiState(
    val isLoading: Boolean = false,
    val connectedDevices: List<ClientDeviceSummary> = emptyList(),
    val detectedDevices: List<DetectedDeviceSummary> = emptyList(),
    val error: String? = null
)

class ClientDeviceInfoViewModel(
    private val repository: IClientDeviceInfoRepository,
    private val sessionManager: UsbSessionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _deviceDetails = MutableStateFlow<Map<String, ClientAppDetails>>(emptyMap())

    val uiState: StateFlow<ClientDeviceInfoUiState> = combine(
        sessionManager.sessionsState,
        _deviceDetails,
        _isLoading,
        _error
    ) { sessions, detailsMap, loading, error ->
        val connectedSessions = sessions.filter { it.value.status is DeviceSessionStatus.Ready }
        val detectedSessions = sessions.filter { it.value.status !is DeviceSessionStatus.Ready }

        val connectedSummaries = connectedSessions.map { (id, sessionState) ->
            val shortName = sessionState.deviceName
                .replace("Android Device (", "")
                .replace(")", "")
                .substringBefore(",")
                .trim()
                .ifBlank { "Client Device ($id)" }

            ClientDeviceSummary(
                deviceId = id,
                displayName = shortName,
                isConnected = true,
                appDetails = detailsMap[id] ?: repository.getCachedDeviceInfo(id)
            )
        }

        val detectedSummaries = detectedSessions.map { (id, sessionState) ->
            val shortName = sessionState.deviceName
                .replace("Android Device (", "")
                .replace(")", "")
                .substringBefore(",")
                .trim()
                .ifBlank { "Detected Device ($id)" }

            DetectedDeviceSummary(
                deviceId = id,
                displayName = shortName,
                isConnecting = sessionState.status is DeviceSessionStatus.Connecting
            )
        }

        ClientDeviceInfoUiState(
            isLoading = loading,
            connectedDevices = connectedSummaries,
            detectedDevices = detectedSummaries,
            error = error
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ClientDeviceInfoUiState()
    )

    init {
        // Observe sessions: automatically load app details for connected devices and clean up disconnected ones
        scope.launch {
            sessionManager.sessionsState.collect { sessions ->
                val connectedIds = sessions
                    .filter { it.value.status is DeviceSessionStatus.Ready }
                    .keys

                if (_deviceDetails.value.keys.any { it !in connectedIds }) {
                    _deviceDetails.value = _deviceDetails.value.filterKeys { it in connectedIds }
                }

                for (id in connectedIds) {
                    if (!_deviceDetails.value.containsKey(id)) {
                        launch {
                            loadDeviceDetails(id)
                        }
                    }
                }
            }
        }
    }

    fun refreshCurrentDevice(deviceId: String? = null) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            val targetIds = if (deviceId != null) {
                listOf(deviceId)
            } else {
                sessionManager.sessionsState.value
                    .filter { it.value.status is DeviceSessionStatus.Ready }
                    .keys.toList()
            }
            for (id in targetIds) {
                loadDeviceDetails(id)
            }
            _isLoading.value = false
        }
    }

    fun connectDevice(deviceId: String) {
        scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            _isLoading.value = true
            _error.value = null
            try {
                val success = session.connect()
                if (success) {
                    loadDeviceDetails(deviceId)
                } else {
                    _error.value = "Failed to establish session with $deviceId."
                }
            } catch (e: Exception) {
                _error.value = "Connection error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadDeviceDetails(deviceId: String) {
        val cached = repository.getCachedDeviceInfo(deviceId)
        if (cached != null) {
            _deviceDetails.value += (deviceId to cached)
        }
        val result = repository.queryDeviceInfo(deviceId)
        result.onSuccess { details ->
            _deviceDetails.value += (deviceId to details)
        }.onFailure { e ->
            if (!_deviceDetails.value.containsKey(deviceId)) {
                _error.value = "Device $deviceId: ${e.message ?: "Failed to query app details."}"
            }
        }
    }
}
