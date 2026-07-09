package com.example.usbtransferapp.presentation.viewmodel

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.usbtransferapp.data.UsbConnectionMode
import com.example.usbtransferapp.data.UsbRole
import com.example.usbtransferapp.data.UsbUiState
import com.example.usbtransferapp.data.usb.AoaConnectionManager
import com.example.usbtransferapp.data.usb.AoaHostSwitcher
import com.example.usbtransferapp.data.usb.DelegatingUsbConnection
import com.example.usbtransferapp.data.usb.HostCommandSender
import com.example.usbtransferapp.data.usb.UsbConnectionManager
import com.example.usbtransferapp.data.usb.UsbDataSource
import com.example.usbtransferapp.data.usb.UsbManagerWrapper
import com.example.usbtransferapp.data.usb.UsbPermissionBus
import com.example.usbtransferapp.data.usb.UsbPermissionEvent
import com.example.usbtransferapp.data.usb.UsbCommandProcessor
import com.example.usbtransferapp.domain.model.RemoteFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UsbTransferViewModel @Inject constructor(
    private val usbManagerWrapper: UsbManagerWrapper,
    private val hostManager: UsbConnectionManager,
    private val aoaManager: AoaConnectionManager,
    private val aoaHostSwitcher: AoaHostSwitcher,
    private val delegatingConnection: DelegatingUsbConnection,
    private val dataSource: UsbDataSource,
    private val commandProcessor: UsbCommandProcessor,
    private val hostCommandSender: HostCommandSender,
    private val listDirectoryUseCase: com.example.usbtransferapp.domain.usecases.ListDirectoryUseCase,
    private val sendFileUseCase: com.example.usbtransferapp.domain.usecases.SendFileUseCase,
    private val fetchFileUseCase: com.example.usbtransferapp.domain.usecases.FetchFileUseCase,
    private val deleteFileUseCase: com.example.usbtransferapp.domain.usecases.DeleteFileUseCase,
    private val renameFileUseCase: com.example.usbtransferapp.domain.usecases.RenameFileUseCase,
    private val preferencesManager: com.example.usbtransferapp.data.preferences.UsbPreferencesManager,
    val usbLogger: com.example.usbtransferapp.data.logging.UsbLogger
) : ViewModel() {

    companion object {
        private const val TAG = "UsbTransferVM"
    }

    private val _uiState = MutableStateFlow<UsbUiState>(UsbUiState.Idle)
    val uiState: StateFlow<UsbUiState> = _uiState

    private val _isCablePhysicallyConnected = MutableStateFlow(false)
    val isCablePhysicallyConnected: StateFlow<Boolean> = _isCablePhysicallyConnected

    private val _connectionMode = MutableStateFlow(preferencesManager.connectionMode)
    val connectionMode: StateFlow<UsbConnectionMode> = _connectionMode

    private val _usbRole = MutableStateFlow<UsbRole?>(
        if (preferencesManager.connectionMode == UsbConnectionMode.DESKTOP_TO_ANDROID) {
            UsbRole.Client("Desktop Host")
        } else {
            preferencesManager.usbRole
        }
    )
    val usbRole: StateFlow<UsbRole?> = _usbRole

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/sdcard")
    val currentRemotePath: StateFlow<String> = _currentRemotePath

    val logLines: StateFlow<List<String>> = usbLogger.logLines
    fun clearLogs() = usbLogger.clearLogs()
    fun getLogFilePath() = usbLogger.getLogFilePath()

    init {
        usbLogger.i(TAG, "ViewModel initialized. Mode: ${_connectionMode.value}, Role: ${_usbRole.value}")
        startCableMonitor()
    }

    private var currentDevice: UsbDevice? = null
    private var currentAccessory: UsbAccessory? = null
    private var commandJob: Job? = null
    private var connectJob: Job? = null
    private var cableMonitorJob: Job? = null

    private fun startCableMonitor() {
        cableMonitorJob?.cancel()
        cableMonitorJob = viewModelScope.launch {
            usbManagerWrapper.observeCableState().collect { isConnected ->
                usbLogger.i(TAG, "Physical cable connection state changed: $isConnected")
                val wasConnected = _isCablePhysicallyConnected.value
                _isCablePhysicallyConnected.value = isConnected

                if (!isConnected) {
                    if (wasConnected || currentDevice != null || currentAccessory != null || _uiState.value !is UsbUiState.NoDevice) {
                        usbLogger.i(TAG, "USB cable physically unplugged! Triggering disconnect and setting NoDevice state.")
                        disconnect(sendSignal = false)
                    } else {
                        _uiState.value = UsbUiState.NoDevice
                    }
                } else if (isConnected && !wasConnected && _uiState.value is UsbUiState.NoDevice) {
                    usbLogger.i(TAG, "USB cable physically plugged in! Re-detecting device...")
                    detectDevice()
                }
            }
        }
    }

    fun detectDevice() {
        usbLogger.d(TAG, "detectDevice: Scanning for USB devices and accessories...")
        val devices = usbManagerWrapper.getDevices()
        val accessories = usbManagerWrapper.getAccessories()
        
        val device = devices.firstOrNull()
        val accessory = accessories?.firstOrNull()

        if (device != null) {
            usbLogger.i(TAG, "detectDevice: Found USB Device - ${device.productName} (ID: ${device.deviceId}, VID=${String.format("%04X", device.vendorId)}, PID=${String.format("%04X", device.productId)})")
            currentDevice = device
            currentAccessory = null
            _uiState.value = UsbUiState.DeviceDetected(name = device.productName ?: "USB Device")
        } else if (accessory != null) {
            usbLogger.i(TAG, "detectDevice: Found USB Accessory - ${accessory.model} (Manufacturer: ${accessory.manufacturer})")
            currentAccessory = accessory
            currentDevice = null
            _uiState.value = UsbUiState.DeviceDetected(name = accessory.model ?: "USB Accessory")
        } else {
            usbLogger.w(TAG, "detectDevice: No USB devices or accessories detected.")
            _uiState.value = UsbUiState.NoDevice
        }
    }

    fun detectAndConnect() {
        if (connectJob?.isActive == true) {
            usbLogger.d(TAG, "detectAndConnect: A connection job is already active. Ignoring intent.")
            return
        }
        val state = _uiState.value
        if (state is UsbUiState.Connecting || state is UsbUiState.Transferring || 
            state is UsbUiState.Receiving || state is UsbUiState.Success || 
            state is UsbUiState.RequestingPermission ||
            (state is UsbUiState.DeviceDetected && state.name.contains("Waiting for AOA", ignoreCase = true))) {
            usbLogger.d(TAG, "detectAndConnect: Already connecting or connected (state: $state). Ignoring intent.")
            return
        }

        detectDevice()
        if (currentDevice != null || currentAccessory != null) {
            usbLogger.i(TAG, "detectAndConnect: Device found, auto-connecting...")
            requestPermissionAndConnect()
        }
    }

    fun requestPermissionAndConnect() {
        if (connectJob?.isActive == true) {
            usbLogger.d(TAG, "requestPermissionAndConnect: A connection job is already active. Ignoring redundant request.")
            return
        }
        val state = _uiState.value
        if (state is UsbUiState.Connecting || state is UsbUiState.Transferring || 
            state is UsbUiState.Receiving || state is UsbUiState.Success || 
            state is UsbUiState.RequestingPermission ||
            (state is UsbUiState.DeviceDetected && state.name.contains("Waiting for AOA", ignoreCase = true))) {
            usbLogger.d(TAG, "requestPermissionAndConnect: Already connecting or connected (state: $state). Ignoring redundant request.")
            return
        }

        connectJob = viewModelScope.launch {
            commandJob?.cancel()
            commandJob = null
            
            delegatingConnection.disconnect()
            aoaManager.disconnect()
            hostManager.disconnect()

            val device = currentDevice
            val accessory = currentAccessory

            if (device != null) {
                if (!usbManagerWrapper.hasPermission(device)) {
                    usbLogger.d(TAG, "requestPermissionAndConnect: Requesting permission for device ${device.deviceId}")
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(device)
                    UsbPermissionBus.flow.collect { event ->
                        if (event is UsbPermissionEvent.DeviceGranted && event.device.deviceId == device.deviceId) {
                            usbLogger.i(TAG, "requestPermissionAndConnect: Permission GRANTED for device ${device.deviceId}")
                            proceedWithDevice(event.device)
                            cancel()
                        }
                    }
                } else {
                    usbLogger.d(TAG, "requestPermissionAndConnect: Already have permission for device ${device.deviceId}")
                    proceedWithDevice(device)
                }
            } else if (accessory != null) {
                if (!usbManagerWrapper.hasPermission(accessory)) {
                    usbLogger.d(TAG, "requestPermissionAndConnect: Requesting permission for accessory ${accessory.model}")
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(accessory)
                    UsbPermissionBus.flow.collect { event ->
                        if (event is UsbPermissionEvent.AccessoryGranted && event.accessory == accessory) {
                            usbLogger.i(TAG, "requestPermissionAndConnect: Permission GRANTED for accessory ${accessory.model}")
                            proceedWithAccessory(event.accessory)
                            cancel()
                        }
                    }
                } else {
                    usbLogger.d(TAG, "requestPermissionAndConnect: Already have permission for accessory ${accessory.model}")
                    proceedWithAccessory(accessory)
                }
            } else {
                usbLogger.e(TAG, "requestPermissionAndConnect: Failed - No device or accessory selected.")
                _uiState.value = UsbUiState.NoDevice
            }
        }
    }

    fun selectConnectionMode(mode: UsbConnectionMode) {
        usbLogger.i(TAG, "selectConnectionMode: $mode")
        val previousMode = _connectionMode.value
        _connectionMode.value = mode
        preferencesManager.connectionMode = mode

        if (previousMode != mode) {
            usbLogger.i(TAG, "Connection mode switched ($previousMode -> $mode). Resetting connection states for new connection...")
            connectJob?.cancel()
            connectJob = null
            commandJob?.cancel()
            commandJob = null

            delegatingConnection.disconnect()
            aoaManager.disconnect()
            hostManager.disconnect()

            _remoteFiles.value = emptyList()
            _currentRemotePath.value = "/sdcard"

            detectDevice()
        }

        if (mode == UsbConnectionMode.DESKTOP_TO_ANDROID) {
            val clientRole = UsbRole.Client("Desktop Host")
            _usbRole.value = clientRole
            preferencesManager.usbRole = clientRole
        } else {
            _usbRole.value = preferencesManager.usbRole
        }
    }

    fun selectRoleAndConnect(role: UsbRole) {
        usbLogger.i(TAG, "selectRoleAndConnect: $role")
        val previousRole = _usbRole.value
        if (previousRole != role) {
            usbLogger.i(TAG, "Role switched ($previousRole -> $role). Resetting previous connection state...")
            connectJob?.cancel()
            connectJob = null
            commandJob?.cancel()
            commandJob = null

            delegatingConnection.disconnect()
            aoaManager.disconnect()
            hostManager.disconnect()

            _remoteFiles.value = emptyList()
            _currentRemotePath.value = "/sdcard"

            detectDevice()
        }
        _usbRole.value = role
        preferencesManager.usbRole = role
        requestPermissionAndConnect()
    }

    private suspend fun proceedWithDevice(device: UsbDevice) {
        usbLogger.d(TAG, "proceedWithDevice: Attempting to connect via Host Mode (Role: ${_usbRole.value}, Mode: ${_connectionMode.value})...")
        _uiState.value = UsbUiState.Connecting

        val isAlreadyAoa = device.productId == 0x2D00 || device.productId == 0x2D01

        if (_connectionMode.value == UsbConnectionMode.ANDROID_TO_ANDROID) {
            // In Android-to-Android mode, if this device sees a UsbDevice, it is the physical USB Host controller.
            // Ensure its role is aligned to Host so it initiates AOA switch and handshake.
            if (_usbRole.value !is UsbRole.Host) {
                usbLogger.i(TAG, "proceedWithDevice: Hardware USB Host detected. Aligning role to Host(connectedDeviceName=Android Client)")
                _usbRole.value = UsbRole.Host("Android Client")
                preferencesManager.usbRole = _usbRole.value
            }

            if (!isAlreadyAoa) {
                usbLogger.i(TAG, "proceedWithDevice: Remote Android device is in normal mode (${device.productId}). Switching remote device to AOA Accessory mode...")
                if (aoaHostSwitcher.switchToAoaMode(device)) {
                    usbLogger.i(TAG, "proceedWithDevice: AOA switch triggered. Waiting up to 10s for remote Android device to re-enumerate as Accessory (0x2D00/0x2D01)...")
                    _uiState.value = UsbUiState.DeviceDetected("Waiting for AOA mode...")
                    var aoaDevice: UsbDevice? = null
                    for (i in 0 until 40) {
                        delay(250)
                        val devices = usbManagerWrapper.getDevices()
                        aoaDevice = devices.firstOrNull { it.productId == 0x2D00 || it.productId == 0x2D01 }
                        if (aoaDevice != null) {
                            usbLogger.i(TAG, "proceedWithDevice: Found re-enumerated AOA device after ${(i + 1) * 250}ms!")
                            break
                        }
                    }
                    if (aoaDevice == null) {
                        usbLogger.e(TAG, "proceedWithDevice: Timed out waiting for AOA re-enumeration.")
                        _uiState.value = UsbUiState.Error("Timed out waiting for remote AOA device")
                        return
                    }
                    if (hostManager.connect(aoaDevice)) {
                        usbLogger.i(TAG, "proceedWithDevice: Host connection to AOA device successful.")
                        delegatingConnection.setDelegate(hostManager)
                        currentDevice = aoaDevice
                        startHostHandshakeAndCommands()
                    } else {
                        _uiState.value = UsbUiState.Error("Failed to open connection to AOA device")
                    }
                } else {
                    usbLogger.e(TAG, "proceedWithDevice: Failed to switch remote device to AOA mode.")
                    _uiState.value = UsbUiState.Error("Failed to switch remote device to AOA mode")
                }
            } else {
                if (hostManager.connect(device)) {
                    usbLogger.i(TAG, "proceedWithDevice: Host connection to already-in-AOA device successful.")
                    delegatingConnection.setDelegate(hostManager)
                    startHostHandshakeAndCommands()
                } else {
                    usbLogger.e(TAG, "proceedWithDevice: Failed to connect to AOA device.")
                    _uiState.value = UsbUiState.Error("Failed to connect to AOA device")
                }
            }
        } else {
            // DESKTOP_TO_ANDROID mode or generic handling
            if (isAlreadyAoa || _usbRole.value is UsbRole.Host) {
                if (!isAlreadyAoa && _usbRole.value is UsbRole.Host) {
                    usbLogger.i(TAG, "proceedWithDevice: Remote Android device is in normal mode. Switching remote device to AOA Accessory mode...")
                    if (aoaHostSwitcher.switchToAoaMode(device)) {
                        usbLogger.i(TAG, "proceedWithDevice: AOA switch triggered. Waiting up to 10s for remote Android device to re-enumerate as Accessory...")
                        _uiState.value = UsbUiState.DeviceDetected("Waiting for AOA mode...")
                        var aoaDevice: UsbDevice? = null
                        for (i in 0 until 40) {
                            delay(250)
                            val devices = usbManagerWrapper.getDevices()
                            aoaDevice = devices.firstOrNull { it.productId == 0x2D00 || it.productId == 0x2D01 }
                            if (aoaDevice != null) {
                                usbLogger.i(TAG, "proceedWithDevice: Found re-enumerated AOA device after ${(i + 1) * 250}ms!")
                                break
                            }
                        }
                        if (aoaDevice == null) {
                            usbLogger.e(TAG, "proceedWithDevice: Timed out waiting for AOA re-enumeration.")
                            _uiState.value = UsbUiState.Error("Timed out waiting for remote AOA device")
                            return
                        }
                        if (hostManager.connect(aoaDevice)) {
                            usbLogger.i(TAG, "proceedWithDevice: Host connection to AOA device successful.")
                            delegatingConnection.setDelegate(hostManager)
                            currentDevice = aoaDevice
                            startHostHandshakeAndCommands()
                        } else {
                            _uiState.value = UsbUiState.Error("Failed to open connection to AOA device")
                        }
                    } else {
                        usbLogger.e(TAG, "proceedWithDevice: Failed to switch remote device to AOA mode.")
                        _uiState.value = UsbUiState.Error("Failed to switch remote device to AOA mode")
                    }
                } else {
                    if (hostManager.connect(device)) {
                        usbLogger.i(TAG, "proceedWithDevice: Host connection successful.")
                        delegatingConnection.setDelegate(hostManager)
                        if (_usbRole.value is UsbRole.Host) {
                            startHostHandshakeAndCommands()
                        } else {
                            startHandshakeAndListen()
                        }
                    } else {
                        usbLogger.w(TAG, "proceedWithDevice: Host connection failed. Desktop may be negotiating AOA mode switch.")
                        _uiState.value = UsbUiState.DeviceDetected("Desktop Detected")
                    }
                }
            } else {
                if (hostManager.connect(device)) {
                    usbLogger.i(TAG, "proceedWithDevice: Host connection successful.")
                    delegatingConnection.setDelegate(hostManager)
                    startHandshakeAndListen()
                } else {
                    usbLogger.w(TAG, "proceedWithDevice: Host connection failed. Desktop may be negotiating AOA mode switch.")
                    _uiState.value = UsbUiState.DeviceDetected("Desktop Detected")
                }
            }
        }
    }

    private suspend fun proceedWithAccessory(accessory: UsbAccessory) {
        usbLogger.d(TAG, "proceedWithAccessory: Attempting to connect via AOA Mode (Accessory: ${accessory.model})...")
        _uiState.value = UsbUiState.Connecting
        if (aoaManager.connect(accessory)) {
            usbLogger.i(TAG, "proceedWithAccessory: AOA connection successful.")
            delegatingConnection.setDelegate(aoaManager)
            _usbRole.value = UsbRole.Client(connectedHostName = accessory.model ?: "USB Host")
            preferencesManager.usbRole = _usbRole.value
            startHandshakeAndListen()
        } else {
            usbLogger.e(TAG, "proceedWithAccessory: AOA connection failed.")
            _uiState.value = UsbUiState.Error("AOA connection failed")
        }
    }

    private suspend fun startHostHandshakeAndCommands() {
        usbLogger.i(TAG, "startHostHandshakeAndCommands: Initiating handshake as USB Host (Initiator)...")
        _uiState.value = UsbUiState.Transferring
        if (dataSource.performHandshakeAsInitiator()) {
            usbLogger.i(TAG, "startHostHandshakeAndCommands: Handshake SUCCESS. Waiting for client ready signal...")
            if (hostCommandSender.waitForClientReady()) {
                usbLogger.i(TAG, "startHostHandshakeAndCommands: Client ready! Host is connected and ready to send commands.")
                _uiState.value = UsbUiState.Success("Connected as Host (Ready)")
                fetchRemoteFiles("/sdcard")
            } else {
                usbLogger.e(TAG, "startHostHandshakeAndCommands: Client failed ready handshake.")
                _uiState.value = UsbUiState.Error("Client ready check failed")
            }
        } else {
            usbLogger.e(TAG, "startHostHandshakeAndCommands: Handshake FAILED as Initiator.")
            _uiState.value = UsbUiState.Error("Handshake failed")
        }
    }

    private suspend fun startHandshakeAndListen() {
        usbLogger.d(TAG, "startHandshakeAndListen: Initiating handshake (Responder)...")
        _uiState.value = UsbUiState.Transferring
        if (dataSource.performHandshake()) {
            usbLogger.i(TAG, "startHandshakeAndListen: Handshake SUCCESS. Secure channel established.")
            _uiState.value = UsbUiState.Success("Secure Channel Established")

            commandJob = viewModelScope.launch {
                usbLogger.d(TAG, "startHandshakeAndListen: Starting command listener...")
                delegatingConnection.clearBuffer()
                commandProcessor.startListening(
                    onReceiveStarted = { fileName ->
                        _uiState.value = UsbUiState.Receiving(fileName, 0f)
                    },
                    onReceiveProgress = { progress ->
                        val currentState = _uiState.value
                        if (currentState is UsbUiState.Receiving) {
                            _uiState.value = currentState.copy(progress = progress)
                        } else {
                            _uiState.value = UsbUiState.Receiving("File", progress)
                        }
                    },
                    onReceiveFinished = {
                        _uiState.value = UsbUiState.Success("Secure Channel Established")
                    },
                    onReceiveCancelled = {
                        _uiState.value = UsbUiState.Success("Transfer Cancelled")
                    },
                    onReceiveError = { errorMsg ->
                        usbLogger.e(TAG, "Transfer Error: $errorMsg")
                        _uiState.value = UsbUiState.Error("Transfer Error: $errorMsg")
                    },
                    onDisconnectReceived = {
                        usbLogger.i(TAG, "Disconnected command received from remote.")
                        _uiState.value = UsbUiState.Success("Disconnected by Remote")
                        disconnect(sendSignal = false)
                    }
                )

                if (_uiState.value !is UsbUiState.NoDevice && _uiState.value !is UsbUiState.Idle) {
                    usbLogger.i(TAG, "startHandshakeAndListen: Command listener terminated unexpectedly. Resetting connection.")
                    disconnect(sendSignal = false)
                }
            }
        } else {
            usbLogger.e(TAG, "startHandshakeAndListen: Handshake FAILED.")
            if (currentDevice != null) {
                usbLogger.i(TAG, "startHandshakeAndListen: Handshake failed in Host Mode. Waiting for AOA reconnection...")
                _uiState.value = UsbUiState.DeviceDetected("Waiting for AOA mode...")
            } else {
                _uiState.value = UsbUiState.Error("Handshake failed")
            }
        }
    }

    private var fetchRemoteJob: kotlinx.coroutines.Job? = null

    fun fetchRemoteFiles(path: String) {
        val normalizedPath = if (path == "/" || path.isEmpty() || path == "/sdcard") "/sdcard" else path.trimEnd('/')
        usbLogger.i(TAG, "fetchRemoteFiles: Requesting remote directory listing for '$normalizedPath'...")
        fetchRemoteJob?.cancel()
        fetchRemoteJob = viewModelScope.launch {
            _currentRemotePath.value = normalizedPath
            val list = listDirectoryUseCase(normalizedPath)
            usbLogger.i(TAG, "fetchRemoteFiles: Received ${list.size} items for remote path '$normalizedPath'")
            _remoteFiles.value = list
        }
    }

    fun sendRemoteFile(localFile: File, remotePath: String) {
        viewModelScope.launch {
            _uiState.value = UsbUiState.Transferring
            try {
                sendFileUseCase(localFile, remotePath).collect { prog ->
                    _uiState.value = UsbUiState.Receiving(localFile.name, prog / 100f)
                }
                _uiState.value = UsbUiState.Success("File Sent Successfully")
                fetchRemoteFiles(_currentRemotePath.value)
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to send file", e)
                _uiState.value = UsbUiState.Error("Failed to send file: ${e.message}")
            }
        }
    }

    fun fetchRemoteFile(remotePath: String, localSaveDir: File) {
        viewModelScope.launch {
            _uiState.value = UsbUiState.Transferring
            val fileName = remotePath.substringAfterLast('/')
            val targetFile = File(localSaveDir, fileName)
            try {
                fetchFileUseCase(remotePath, targetFile).collect { prog ->
                    _uiState.value = UsbUiState.Receiving(fileName, prog / 100f)
                }
                _uiState.value = UsbUiState.Success("File Received Successfully")
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to fetch file", e)
                _uiState.value = UsbUiState.Error("Failed to fetch file: ${e.message}")
            }
        }
    }

    fun deleteRemoteFile(remotePath: String) {
        viewModelScope.launch {
            if (deleteFileUseCase(remotePath)) {
                fetchRemoteFiles(_currentRemotePath.value)
            }
        }
    }

    fun renameRemoteFile(oldPath: String, newPath: String) {
        viewModelScope.launch {
            if (renameFileUseCase(oldPath, newPath)) {
                fetchRemoteFiles(_currentRemotePath.value)
            }
        }
    }

    fun disconnect(sendSignal: Boolean = true) {
        usbLogger.d(TAG, "disconnect: Resetting connection and state (sendSignal=$sendSignal, role=${_usbRole.value})")

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (sendSignal) {
                try {
                    if (_usbRole.value is UsbRole.Host) {
                        hostCommandSender.sendDisconnect()
                    } else {
                        dataSource.sendSecure(byteArrayOf(4.toByte()))
                    }
                    kotlinx.coroutines.delay(100) // Give it a moment to flush
                } catch (e: Exception) {
                    usbLogger.w(TAG, "disconnect: Failed to send disconnect command to remote", e)
                }
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                connectJob?.cancel()
                connectJob = null
                commandJob?.cancel()
                commandJob = null

                delegatingConnection.disconnect()
                aoaManager.disconnect()
                hostManager.disconnect()

                currentDevice = null
                currentAccessory = null

                if (_connectionMode.value == UsbConnectionMode.ANDROID_TO_ANDROID) {
                    _usbRole.value = null
                }

                if (!sendSignal || !_isCablePhysicallyConnected.value || !usbManagerWrapper.isUsbCablePhysicallyConnected()) {
                    usbLogger.i(TAG, "disconnect: Cable unplugged or remote disconnect. Setting state to NoDevice.")
                    _uiState.value = UsbUiState.NoDevice
                } else {
                    detectDevice()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbLogger.d(TAG, "onCleared: Cleaning up ViewModel and cancelling jobs.")
        disconnect()
    }
}
