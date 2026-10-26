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
    val selectedDeviceId: String? = null,
    val connectedDevices: List<ClientDeviceSummary> = emptyList(),
    val detectedDevices: List<DetectedDeviceSummary> = emptyList(),
    val currentAppDetails: ClientAppDetails? = null,
    val error: String? = null
)

class ClientDeviceInfoViewModel(
    private val repository: IClientDeviceInfoRepository,
    private val sessionManager: UsbSessionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId

    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    // Current application details state flow
    private val _currentAppDetails = MutableStateFlow<ClientAppDetails?>(null)

    val uiState: StateFlow<ClientDeviceInfoUiState> = combine(
        sessionManager.sessionsState,
        _selectedDeviceId,
        _currentAppDetails,
        _isLoading,
        _error
    ) { sessions, selectedId, appDetails, loading, error ->
        // Separate sessions strictly into connected (Ready) vs detected (Disconnected, Connecting, Error)
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
                isConnected = true
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

        // Auto-select first CONNECTED device if none selected or previously selected device is no longer connected
        val effectiveSelectedId = when {
            selectedId != null && connectedSessions.containsKey(selectedId) -> selectedId
            connectedSessions.isNotEmpty() -> connectedSessions.keys.first()
            else -> null
        }

        if (effectiveSelectedId != _selectedDeviceId.value) {
            _selectedDeviceId.value = effectiveSelectedId
            if (effectiveSelectedId != null) {
                scope.launch {
                    loadAppDetails(effectiveSelectedId)
                }
            } else {
                _currentAppDetails.value = null
            }
        }

        ClientDeviceInfoUiState(
            isLoading = loading,
            selectedDeviceId = effectiveSelectedId,
            connectedDevices = connectedSummaries,
            detectedDevices = detectedSummaries,
            currentAppDetails = if (effectiveSelectedId != null) appDetails else null,
            error = error
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ClientDeviceInfoUiState()
    )

    init {
        // Observe sessions and initialize selection for connected devices only
        scope.launch {
            sessionManager.sessionsState.collect { sessions ->
                val connected = sessions.filter { it.value.status is DeviceSessionStatus.Ready }
                if (_selectedDeviceId.value == null && connected.isNotEmpty()) {
                    val first = connected.keys.first()
                    _selectedDeviceId.value = first
                    loadAppDetails(first)
                }
            }
        }
    }

    fun selectDevice(deviceId: String) {
        val session = sessionManager.getSession(deviceId)
        if (session == null || session.sessionState.value.status !is DeviceSessionStatus.Ready) {
            return
        }
        if (_selectedDeviceId.value == deviceId) return
        _selectedDeviceId.value = deviceId
        scope.launch {
            loadAppDetails(deviceId)
        }
    }

    fun refreshCurrentDevice() {
        val deviceId = _selectedDeviceId.value ?: return
        scope.launch {
            _isLoading.value = true
            _error.value = null
            val result = repository.queryDeviceInfo(deviceId)
            result.onSuccess {
                _currentAppDetails.value = it
            }.onFailure {
                _error.value = it.message ?: "Failed to query device info."
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
                    _selectedDeviceId.value = deviceId
                    loadAppDetails(deviceId)
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

    private suspend fun loadAppDetails(deviceId: String) {
        val cached = repository.getCachedDeviceInfo(deviceId)
        if (cached != null) {
            _currentAppDetails.value = cached
        }
        _isLoading.value = true
        _error.value = null
        val result = repository.queryDeviceInfo(deviceId)
        result.onSuccess {
            _currentAppDetails.value = it
        }.onFailure {
            if (_currentAppDetails.value == null) {
                _error.value = it.message ?: "Failed to load device info."
            }
        }
        _isLoading.value = false
    }
}
