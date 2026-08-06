package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.data.repo.UsbRepositoryImpl
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Per-device session wrapper managing connection, transport, repository, state, and concurrency mutex.
 *
 * @property deviceId Invariant physical hardware identifier (e.g. "bus_1_port_3").
 * @property discoveredDevice Discovered device descriptor containing physical topology and handles.
 * @property deviceManager UsbDeviceManager reference for handle cleanup and device queries.
 * @property connection Dedicated UsbConnection instance for this specific device.
 * @property repository Dedicated UsbRepository instance managing SDK communication for this device.
 */
class UsbSession(
    val deviceId: String,
    var discoveredDevice: DiscoveredUsbDevice,
    val deviceManager: UsbDeviceManager,
    val connection: UsbConnection = UsbConnection(),
    val repository: UsbRepository = UsbRepositoryImpl(deviceManager, connection, deviceId)
) {
    val sessionMutex = Mutex()

    /** Per-device transfer queue — processes items sequentially within this device */
    val transferQueue = TransferQueue(deviceId, sessionMutex, repository)

    private val _sessionState = MutableStateFlow(
        UsbSessionState(
            deviceId = deviceId,
            deviceName = discoveredDevice.deviceName,
            isAoaMode = discoveredDevice.isAoa
        )
    )
    val sessionState: StateFlow<UsbSessionState> = _sessionState.asStateFlow()

    /**
     * Updates the underlying [DiscoveredUsbDevice] handle and state metadata upon re-enumeration (e.g. MTP -> AOA).
     */
    fun updateDiscoveredDevice(newDevice: DiscoveredUsbDevice) {
        this.discoveredDevice = newDevice
        _sessionState.value = _sessionState.value.copy(
            deviceName = newDevice.deviceName,
            isAoaMode = newDevice.isAoa
        )
    }

    /**
     * Initiates connection and SDK handshake for this physical device session.
     */
    suspend fun connect(): Boolean {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Connecting)
        val success = try {
            repository.connect()
        } catch (e: Exception) {
            println("[UsbSession] Connection failed with exception for device $deviceId: ${e.message}")
            false
        }
        _sessionState.value = _sessionState.value.copy(
            status = if (success) DeviceSessionStatus.Ready else DeviceSessionStatus.Error("Connection or handshake failed"),
            isAoaMode = repository.isAoaMode
        )
        return success
    }

    /**
     * Disconnects the session, closes native endpoints, releases native device handle, and updates state.
     */
    fun disconnect() {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Disconnected)
        try {
            repository.disconnect()
        } catch (e: Exception) {
            println("[UsbSession] Error during repository disconnect for $deviceId: ${e.message}")
        }
        try {
            deviceManager.releaseDevice(discoveredDevice.device)
        } catch (e: Exception) {
            println("[UsbSession] Error releasing device handle for $deviceId: ${e.message}")
        }
    }

    fun updateState(transform: (UsbSessionState) -> UsbSessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    fun updateRemoteFiles(files: List<RemoteFile>) {
        _sessionState.value = _sessionState.value.copy(remoteFiles = files)
    }

    fun updateCurrentPath(path: String) {
        _sessionState.value = _sessionState.value.copy(currentPath = path)
    }

    fun updateProgress(progress: TransferProgress) {
        _sessionState.value = _sessionState.value.copy(progress = progress)
    }

    fun updateStatus(status: DeviceSessionStatus) {
        _sessionState.value = _sessionState.value.copy(status = status)
    }
}
