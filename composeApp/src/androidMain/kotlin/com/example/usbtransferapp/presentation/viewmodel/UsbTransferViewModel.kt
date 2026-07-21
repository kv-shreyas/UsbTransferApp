package com.example.usbtransferapp.presentation.viewmodel

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.usbtransferapp.domain.constants.Constants
import com.example.usbtransferapp.domain.model.RemoteFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
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
    private val fetchDirectoryUseCase: com.example.usbtransferapp.domain.usecases.FetchDirectoryUseCase,
    private val deleteFileUseCase: com.example.usbtransferapp.domain.usecases.DeleteFileUseCase,
    private val renameFileUseCase: com.example.usbtransferapp.domain.usecases.RenameFileUseCase,
    private val createFolderUseCase: com.example.usbtransferapp.domain.usecases.CreateFolderUseCase,
    private val cancelTransferUseCase: com.example.usbtransferapp.domain.usecases.CancelTransferUseCase,
    private val preferencesManager: com.example.usbtransferapp.data.preferences.UsbPreferencesManager,
    private val clientServiceController: com.example.usbtransferapp.data.usb.ClientServiceController,
    val usbLogger: com.example.usbtransferapp.data.logging.UsbLogger
) : ViewModel() {

    companion object {
        private const val TAG = "UsbTransferVM"
    }

    private val _uiState = MutableStateFlow<UsbUiState>(UsbUiState.Idle)
    val uiState: StateFlow<UsbUiState> = _uiState

    private val _isCablePhysicallyConnected = MutableStateFlow(false)
    val isCablePhysicallyConnected: StateFlow<Boolean> = _isCablePhysicallyConnected

    private val _usbRole = MutableStateFlow<UsbRole?>(preferencesManager.usbRole)
    val usbRole: StateFlow<UsbRole?> = _usbRole

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/sdcard")
    val currentRemotePath: StateFlow<String> = _currentRemotePath

    private val _isRemoteLoading = MutableStateFlow(false)
    val isRemoteLoading: StateFlow<Boolean> = _isRemoteLoading

    data class TransferProgress(
        val isVisible: Boolean = false,
        val filename: String = "",
        val percentage: Int = 0,
        val speed: String = "0 B/s",
        val transferred: String = "0 B",
        val total: String = "0 B",
        val eta: String = "Unknown",
        val elapsed: String = "0s",
        val currentFileIndex: Int = 0,
        val totalFiles: Int = 0,
        val statusMessage: String = "",
        val isComplete: Boolean = false,
        val batchElapsed: String = "0s",
        val queue: List<String> = emptyList()
    )

    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState
    private var receiveStartTime = 0L
    private var receiveFileSize = 0L

    private val directoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<RemoteFile>>>()

    val logLines: StateFlow<List<String>> = usbLogger.logLines
    fun clearLogs() = usbLogger.clearLogs()
    fun getLogFilePath() = usbLogger.getLogFilePath()

    init {
        usbLogger.i(TAG, "ViewModel initialized. Role: ${_usbRole.value}")
        if (clientServiceController.isServiceRunning.value) {
            val savedRole = preferencesManager.usbRole
            if (savedRole is UsbRole.Client) {
                _usbRole.value = savedRole
            }
        }
        startCableMonitor()
        viewModelScope.launch {
            clientServiceController.clientUiState.collect { state ->
                if (_usbRole.value is UsbRole.Client && clientServiceController.isServiceRunning.value) {
                    _uiState.value = state
                }
            }
        }
        viewModelScope.launch {
            clientServiceController.clientTransferProgress.collect { progress ->
                if (_usbRole.value is UsbRole.Client && clientServiceController.isServiceRunning.value) {
                    _progressState.value = progress
                }
            }
        }
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
                val wasConnected = _isCablePhysicallyConnected.value
                if (wasConnected != isConnected) {
                    usbLogger.i(TAG, "Physical cable connection state changed: $isConnected")
                    _isCablePhysicallyConnected.value = isConnected
                }

                if (!isConnected) {
                    if (wasConnected || currentDevice != null || currentAccessory != null || _uiState.value !is UsbUiState.NoDevice) {
                        usbLogger.i(TAG, "USB cable physically unplugged! Triggering disconnect and setting NoDevice state.")
                        disconnect(sendSignal = false)
                    } else {
                        _uiState.value = UsbUiState.NoDevice
                    }
                } else {
                    // Whenever cable is connected or a state broadcast fires, if no device/accessory is active or we are in NoDevice, detect and auto-connect immediately.
                    if (!isBusyOrConnected() && (_uiState.value is UsbUiState.NoDevice || (currentDevice == null && currentAccessory == null))) {
                        if (!wasConnected) {
                            usbLogger.i(TAG, "USB cable physically plugged in! Re-detecting device...")
                        }
                        detectAndConnect()
                    }
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

    private fun isBusyOrConnected(): Boolean {
        val state = _uiState.value
        val isBusyState = state is UsbUiState.Connecting || state is UsbUiState.Transferring || 
            state is UsbUiState.Receiving || state is UsbUiState.Success || 
            state is UsbUiState.RequestingPermission ||
            (state is UsbUiState.DeviceDetected && (
                state.name.contains("Waiting", ignoreCase = true) ||
                state.name.contains("Checking", ignoreCase = true) ||
                state.name.contains("Switching", ignoreCase = true) ||
                state.name.contains("Establishing", ignoreCase = true) ||
                state.name.contains("⏳", ignoreCase = true) ||
                state.name.contains("Connecting", ignoreCase = true)
            ))
        return isBusyState || connectJob?.isActive == true || commandJob?.isActive == true
    }

    fun detectAndConnect() {
        if (_uiState.value is UsbUiState.RequestingPermission) {
            val dev = currentDevice
            val acc = currentAccessory
            val hasPerm = (acc != null && usbManagerWrapper.hasPermission(acc)) ||
                          (dev != null && usbManagerWrapper.hasPermission(dev))
            if (hasPerm) {
                usbLogger.i(TAG, "detectAndConnect: Received intent/broadcast while in RequestingPermission and permission is now GRANTED! Proceeding immediately...")
            } else {
                usbLogger.d(TAG, "detectAndConnect: Actively requesting permission (state: ${_uiState.value}). Ignoring redundant check.")
                return
            }
        } else if (isBusyOrConnected()) {
            usbLogger.d(TAG, "detectAndConnect: Already actively connecting or checking (state: ${_uiState.value}, connectJob=${connectJob?.isActive}). Ignoring redundant intent/broadcast.")
            return
        }

        val wasError = _uiState.value is UsbUiState.Error
        detectDevice()
        val device = currentDevice
        val accessory = currentAccessory
        val isAlreadyAoa = AoaHostSwitcher.isAoaDevice(device)

        if (wasError) {
            usbLogger.d(TAG, "detectAndConnect: In Error state after previous connection attempt. Waiting for user interaction or physical re-plug.")
            return
        }

        // Auto-connect IMMEDIATELY if:
        // 1) It's a UsbAccessory (remote Host already switched us to AOA, so we connect as Client)
        // 2) Or it's a UsbDevice already in AOA mode (remote device already switched and waiting for Host to open endpoints)
        if (accessory != null) {
            usbLogger.i(TAG, "detectAndConnect: UsbAccessory detected (${accessory.model}). Remote device switched us to AOA mode. Auto-connecting as Client...")
            _usbRole.value = UsbRole.Client(connectedHostName = accessory.model ?: "USB Host")
            preferencesManager.usbRole = _usbRole.value
            requestPermissionAndConnect()
        } else if (isAlreadyAoa && device != null) {
            usbLogger.i(TAG, "detectAndConnect: AOA UsbDevice detected (${device.productName}). Remote device is in AOA mode waiting for Host. Auto-connecting as Host...")
            _usbRole.value = UsbRole.Host(connectedDeviceName = device.productName ?: "AOA Device")
            preferencesManager.usbRole = _usbRole.value
            requestPermissionAndConnect()
        } else if (device != null) {
            // Normal MTP device plugged in. NEVER auto-blast AOA switch without user interaction to prevent dual-switch deadlocks.
            usbLogger.i(TAG, "detectAndConnect: Normal USB Device detected (${device.productName}). Waiting for user to select role / tap 'Initialize Connection' before triggering AOA switch.")
            _uiState.value = UsbUiState.DeviceDetected(name = device.productName ?: "Android Device")
        } else {
            usbLogger.d(TAG, "detectAndConnect: No USB device or accessory found.")
        }
    }

    fun requestPermissionAndConnect() {
        val dev = currentDevice
        val acc = currentAccessory
        val hasPerm = (acc != null && usbManagerWrapper.hasPermission(acc)) ||
                      (dev != null && usbManagerWrapper.hasPermission(dev))
        if (isBusyOrConnected() && !(_uiState.value is UsbUiState.RequestingPermission && hasPerm)) {
            usbLogger.d(TAG, "requestPermissionAndConnect: Already actively connecting or checking (state: ${_uiState.value}, connectJob=${connectJob?.isActive}). Ignoring redundant request.")
            return
        }

        _uiState.value = UsbUiState.Connecting
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
                    val granted = withTimeoutOrNull(60_000) {
                        var isGranted = false
                        while (isActive) {
                            if (usbManagerWrapper.hasPermission(device)) {
                                isGranted = true
                                break
                            }
                            val event = withTimeoutOrNull(250) { UsbPermissionBus.flow.firstOrNull() }
                            if (event is UsbPermissionEvent.DeviceGranted && event.device.deviceId == device.deviceId) {
                                isGranted = true
                                break
                            } else if (event is UsbPermissionEvent.DeviceDenied && event.device.deviceId == device.deviceId) {
                                isGranted = false
                                break
                            }
                        }
                        isGranted
                    }
                    if (granted == true || usbManagerWrapper.hasPermission(device)) {
                        usbLogger.i(TAG, "requestPermissionAndConnect: Permission GRANTED for device ${device.deviceId}")
                        proceedWithDevice(device)
                    } else {
                        usbLogger.w(TAG, "requestPermissionAndConnect: Permission denied or timed out for device ${device.deviceId}")
                        _uiState.value = UsbUiState.Error("USB permission denied or timed out")
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
                    val granted = withTimeoutOrNull(60_000) {
                        var isGranted = false
                        while (isActive) {
                            if (usbManagerWrapper.hasPermission(accessory)) {
                                isGranted = true
                                break
                            }
                            val event = withTimeoutOrNull(250) { UsbPermissionBus.flow.firstOrNull() }
                            if (event is UsbPermissionEvent.AccessoryGranted && event.accessory == accessory) {
                                isGranted = true
                                break
                            } else if (event is UsbPermissionEvent.AccessoryDenied && event.accessory == accessory) {
                                isGranted = false
                                break
                            }
                        }
                        isGranted
                    }
                    if (granted == true || usbManagerWrapper.hasPermission(accessory)) {
                        usbLogger.i(TAG, "requestPermissionAndConnect: Permission GRANTED for accessory ${accessory.model}")
                        proceedWithAccessory(accessory)
                    } else {
                        usbLogger.w(TAG, "requestPermissionAndConnect: Permission denied or timed out for accessory ${accessory.model}")
                        _uiState.value = UsbUiState.Error("USB permission denied or timed out")
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

    /**
     * Called when a UsbDevice is detected. Since this Android device sees a UsbDevice,
     * it is the physical USB host controller. The role is aligned to Host and the remote
     * device is switched to AOA mode if needed.
     */
    private suspend fun proceedWithDevice(device: UsbDevice) {
        usbLogger.d(TAG, "proceedWithDevice: Attempting to connect via Host Mode (Role: ${_usbRole.value})...")
        _uiState.value = UsbUiState.Connecting

        val isAlreadyAoa = AoaHostSwitcher.isAoaDevice(device)

        // When this device detects a UsbDevice, it is the physical USB host controller.
        // Align role to Host so it initiates AOA switch and handshake.
        if (_usbRole.value !is UsbRole.Host) {
            usbLogger.i(TAG, "proceedWithDevice: Hardware USB Host detected. Aligning role to Host(connectedDeviceName=${device.productName ?: "Remote Device"})")
            _usbRole.value = UsbRole.Host(device.productName ?: "Remote Device")
            preferencesManager.usbRole = _usbRole.value
        }

        if (!isAlreadyAoa) {
            usbLogger.i(TAG, "proceedWithDevice: Checking if remote device supports Android Accessory (AOA) mode...")
            _uiState.value = UsbUiState.DeviceDetected("Checking remote device AOA compatibility...")
            if (!aoaHostSwitcher.isAoaSupported(device)) {
                usbLogger.e(TAG, "proceedWithDevice: Remote device does not support AOA mode protocol.")
                _uiState.value = UsbUiState.Error("Incompatible Device: The connected USB device does not support Android Accessory Mode.")
                return
            }

            usbLogger.i(TAG, "proceedWithDevice: Remote device is in normal mode (VID=${String.format("%04X", device.vendorId)}, PID=${String.format("%04X", device.productId)}). Switching remote device to AOA Accessory mode...")
            _uiState.value = UsbUiState.DeviceDetected("Switching remote device to AOA mode...")
            if (aoaHostSwitcher.switchToAoaMode(device)) {
                usbLogger.i(TAG, "proceedWithDevice: AOA switch triggered. Waiting up to 20s for remote device to re-enumerate as Accessory (0x18D1:0x2D00..0x2D05)...")
                _uiState.value = UsbUiState.DeviceDetected("Waiting for remote AOA mode re-enumeration...")
                var aoaDevice: UsbDevice? = null
                for (i in 0 until 80) {
                    delay(250)
                    val devices = usbManagerWrapper.getDevices()
                    aoaDevice = devices.firstOrNull { AoaHostSwitcher.isAoaDevice(it) }
                    if (aoaDevice != null) {
                        usbLogger.i(TAG, "proceedWithDevice: Found re-enumerated AOA device (${aoaDevice.productName}, PID=${String.format("%04X", aoaDevice.productId)}) after ${(i + 1) * 250}ms!")
                        break
                    }
                    if ((i + 1) % 8 == 0) {
                        usbLogger.d(TAG, "proceedWithDevice: Still waiting for AOA device (${(i + 1) * 250}ms/20000ms)... visible USB devices: ${devices.map { "${it.productName ?: "Dev"} (${String.format("%04X", it.vendorId)}:${String.format("%04X", it.productId)})" }}")
                    }
                }
                if (aoaDevice == null) {
                    usbLogger.e(TAG, "proceedWithDevice: Timed out waiting for AOA re-enumeration.")
                    _uiState.value = UsbUiState.Error("Timed out waiting for remote device AOA re-enumeration")
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
            // Device is already in AOA mode, connect directly
            if (hostManager.connect(device)) {
                usbLogger.i(TAG, "proceedWithDevice: Host connection to already-in-AOA device successful.")
                delegatingConnection.setDelegate(hostManager)
                startHostHandshakeAndCommands()
            } else {
                usbLogger.e(TAG, "proceedWithDevice: Failed to connect to AOA device.")
                _uiState.value = UsbUiState.Error("Failed to connect to AOA device")
            }
        }
    }

    private suspend fun proceedWithAccessory(accessory: UsbAccessory) {
        usbLogger.d(TAG, "proceedWithAccessory: Starting client foreground service for accessory: ${accessory.model}...")
        _uiState.value = UsbUiState.Connecting
        _usbRole.value = UsbRole.Client(connectedHostName = accessory.model ?: "USB Host")
        preferencesManager.usbRole = _usbRole.value
        clientServiceController.startService(accessory)
    }

    private suspend fun startHostHandshakeAndCommands() {
        usbLogger.i(TAG, "startHostHandshakeAndCommands: Initiating handshake as USB Host (Initiator)...")
        _uiState.value = UsbUiState.DeviceDetected("Establishing secure channel...")
        if (dataSource.performHandshakeAsInitiator()) {
            usbLogger.i(TAG, "startHostHandshakeAndCommands: Handshake SUCCESS. Waiting for client ready signal...")
            _uiState.value = UsbUiState.DeviceDetected("⏳ Checking remote Client status... Please ensure 'Client' mode & OS permission are granted on the other device.")
            if (hostCommandSender.waitForClientReady()) {
                usbLogger.i(TAG, "startHostHandshakeAndCommands: Client ready! Host is connected and ready to send commands.")
                _uiState.value = UsbUiState.Success("Connected as Host (Ready)")
                fetchRemoteFiles("/sdcard")
            } else {
                usbLogger.e(TAG, "startHostHandshakeAndCommands: Client failed ready handshake.")
                _uiState.value = UsbUiState.Error("Timed out waiting for remote device to enter Client mode.")
            }
        } else {
            usbLogger.e(TAG, "startHostHandshakeAndCommands: Handshake FAILED as Initiator.")
            _uiState.value = UsbUiState.Error("Cryptographic handshake failed or timed out.")
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
                    onReceiveStarted = { fileName, fileSize ->
                        receiveStartTime = System.currentTimeMillis()
                        receiveFileSize = fileSize
                        val formattedSize = if (fileSize > 0) formatSize(fileSize) else "Unknown"
                        _uiState.value = UsbUiState.Receiving(fileName, 0f)
                        _progressState.value = _progressState.value.copy(
                            isVisible = true,
                            isComplete = false,
                            filename = fileName,
                            statusMessage = "Receiving $fileName...",
                            percentage = 0,
                            speed = "Receiving...",
                            transferred = "0 B",
                            total = formattedSize,
                            eta = "Calculating...",
                            elapsed = "0s",
                            currentFileIndex = 1,
                            totalFiles = 1
                        )
                    },
                    onReceiveProgress = { progress ->
                        val currentState = _uiState.value
                        val currentFileName = if (currentState is UsbUiState.Receiving) currentState.fileName else _progressState.value.filename.ifEmpty { "File" }
                        _uiState.value = UsbUiState.Receiving(currentFileName, progress)
                        val elapsedSec = (System.currentTimeMillis() - receiveStartTime) / 1000L
                        val transferredBytes = (receiveFileSize * progress).toLong()
                        val speedSec = if (elapsedSec > 0) formatSize(transferredBytes / elapsedSec) + "/s" else "Calculating..."
                        val etaStr = if (elapsedSec > 0 && progress > 0f) {
                            val remainingBytes = receiveFileSize - transferredBytes
                            val speedBps = transferredBytes / elapsedSec
                            if (speedBps > 0) formatTime(remainingBytes / speedBps) else "Calculating..."
                        } else "Calculating..."
                        _progressState.value = _progressState.value.copy(
                            percentage = (progress * 100).toInt(),
                            statusMessage = "Receiving $currentFileName... (${(progress * 100).toInt()}%)",
                            transferred = formatSize(transferredBytes),
                            speed = speedSec,
                            eta = etaStr,
                            elapsed = formatTime(elapsedSec)
                        )
                    },
                    onReceiveFinished = {
                        val currentFileName = _progressState.value.filename.ifEmpty { "File" }
                        val elapsedSec = (System.currentTimeMillis() - receiveStartTime) / 1000L
                        val finalSizeStr = if (receiveFileSize > 0) formatSize(receiveFileSize) else "Done"
                        val finalSpeedStr = if (elapsedSec > 0 && receiveFileSize > 0) "${formatSize(receiveFileSize / elapsedSec)}/s" else "Done"
                        usbLogger.i(TAG, "Receive finished for $currentFileName ($finalSizeStr)")
                        _progressState.value = _progressState.value.copy(
                            isComplete = true,
                            percentage = 100,
                            filename = currentFileName,
                            total = finalSizeStr,
                            transferred = finalSizeStr,
                            speed = finalSpeedStr,
                            eta = "Done",
                            elapsed = formatTime(elapsedSec),
                            statusMessage = "Receive Complete - Successfully received $currentFileName"
                        )
                        _uiState.value = UsbUiState.Success("File Received Successfully ($currentFileName)")
                    },
                    onReceiveCancelled = {
                        _progressState.value = _progressState.value.copy(
                            isComplete = true,
                            statusMessage = "Transfer Cancelled ❌"
                        )
                        _uiState.value = UsbUiState.Success("Transfer Cancelled")
                    },
                    onReceiveError = { errorMsg ->
                        usbLogger.e(TAG, "Transfer Error: $errorMsg")
                        _progressState.value = _progressState.value.copy(
                            isComplete = true,
                            statusMessage = "Error: $errorMsg"
                        )
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

    fun fetchRemoteFiles(path: String, forceRefresh: Boolean = false) {
        val normalizedPath = if (path == "/" || path.isEmpty() || path == "/sdcard") "/sdcard" else path.trimEnd('/')
        if (!forceRefresh) {
            val cached = directoryCache[normalizedPath]
            if (cached != null && System.currentTimeMillis() - cached.first < 30_000L) {
                usbLogger.d(TAG, "fetchRemoteFiles: Serving '$normalizedPath' instantly from cache (${cached.second.size} items)")
                _currentRemotePath.value = normalizedPath
                _remoteFiles.value = cached.second
                _isRemoteLoading.value = false
                return
            }
        }

        usbLogger.i(TAG, "fetchRemoteFiles: Requesting remote directory listing for '$normalizedPath'...")
        fetchRemoteJob?.cancel()
        fetchRemoteJob = viewModelScope.launch {
            _currentRemotePath.value = normalizedPath
            _isRemoteLoading.value = true
            _remoteFiles.value = emptyList()
            try {
                val list = listDirectoryUseCase(normalizedPath)
                val sorted = list.sortedWith(compareBy<RemoteFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
                directoryCache[normalizedPath] = Pair(System.currentTimeMillis(), sorted)
                usbLogger.i(TAG, "fetchRemoteFiles: Received ${sorted.size} items for remote path '$normalizedPath'")
                _remoteFiles.value = sorted
            } catch (e: Exception) {
                usbLogger.e(TAG, "fetchRemoteFiles: Error fetching listing for '$normalizedPath'", e)
            } finally {
                _isRemoteLoading.value = false
            }
        }
    }

    fun cancelTransfer() {
        commandJob?.cancel()
        commandJob = null
        fetchRemoteJob?.cancel()
        fetchRemoteJob = null
        _progressState.value = _progressState.value.copy(statusMessage = "Transfer Cancelled ❌", isComplete = true)
        _uiState.value = UsbUiState.Success("Transfer Cancelled")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                cancelTransferUseCase()
            } catch (e: Exception) {
                usbLogger.w(TAG, "cancelTransfer error: ${e.message}")
            }
        }
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        val currentPath = _currentRemotePath.value
        viewModelScope.launch {
            _isRemoteLoading.value = true
            val remotePath = "$currentPath/$folderName".replace("//", "/")
            try {
                val success = createFolderUseCase(remotePath)
                if (success) {
                    usbLogger.i(TAG, "Successfully created remote folder: $folderName")
                    fetchRemoteFiles(currentPath, forceRefresh = true)
                } else {
                    usbLogger.e(TAG, "Failed to create remote folder: $folderName")
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error creating remote folder: ${e.message}")
            } finally {
                _isRemoteLoading.value = false
            }
        }
    }

    fun dismissProgress() {
        _progressState.value = TransferProgress()
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    fun formatTime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun navigateUp() {
        val current = _currentRemotePath.value
        if (current == "/" || current == "/sdcard") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        val nextPath = if (parent.isEmpty()) "/" else parent
        fetchRemoteFiles(nextPath)
    }

    fun navigateTo(file: RemoteFile) {
        if (file.isDirectory) {
            fetchRemoteFiles(file.path)
        }
    }

    fun sendRemoteUris(context: android.content.Context, uris: List<android.net.Uri>, remotePath: String) {
        val total = uris.size
        if (total == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val batchStartTime = System.currentTimeMillis()
            val queueNames = mutableListOf<String>()
            val resolvedList = mutableListOf<Pair<android.net.Uri, String>>()
            for (uri in uris) {
                var fileName = "upload_${System.currentTimeMillis()}.dat"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                }
                queueNames.add(fileName)
                resolvedList.add(Pair(uri, fileName))
            }

            _progressState.value = _progressState.value.copy(
                isVisible = true,
                totalFiles = total,
                isComplete = false,
                queue = queueNames
            )

            for ((index, item) in resolvedList.withIndex()) {
                if (!coroutineContext.isActive || _progressState.value.statusMessage.contains("Cancelled")) break
                val (uri, fileName) = item
                _progressState.value = _progressState.value.copy(currentFileIndex = index + 1)
                
                val tempFile = File(context.cacheDir, fileName)
                var copiedSuccessfully = false
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    copiedSuccessfully = tempFile.exists() && tempFile.length() >= 0
                } catch (e: Exception) {
                    usbLogger.e(TAG, "Failed to copy uri $uri to cache", e)
                }

                if (copiedSuccessfully) {
                    sendSingleFileWithProgress(tempFile, remotePath, batchStartTime, index + 1, total)
                }
                if (tempFile.exists()) tempFile.delete()
            }

            if (coroutineContext.isActive && !_progressState.value.statusMessage.contains("Cancelled")) {
                val completedFileNames = queueNames.joinToString(", ")
                _progressState.value = _progressState.value.copy(
                    isComplete = true,
                    filename = completedFileNames,
                    statusMessage = "Upload Complete - Successfully sent: $completedFileNames"
                )
                _uiState.value = UsbUiState.Success("Upload Complete ($completedFileNames)")
                directoryCache.remove(remotePath)
                fetchRemoteFiles(remotePath, forceRefresh = true)
            }
        }
    }

    private suspend fun sendSingleFileWithProgress(file: File, destinationPath: String, batchStartTime: Long = System.currentTimeMillis(), currentFileIndex: Int = 1, totalFiles: Int = 1) {
        usbLogger.i(TAG, "Sending file: ${file.name} (size: ${file.length()} bytes) to $destinationPath")
        _uiState.value = UsbUiState.Transferring

        val startTime = System.currentTimeMillis()
        val fileSize = file.length()
        
        _progressState.value = _progressState.value.copy(
            isVisible = true,
            filename = file.name,
            total = formatSize(fileSize),
            percentage = 0,
            speed = "0 B/s",
            transferred = "0 B",
            eta = "Calculating...",
            elapsed = "0s",
            statusMessage = "Sending ${file.name}...",
            currentFileIndex = currentFileIndex,
            totalFiles = totalFiles
        )

        try {
            sendFileUseCase(file, destinationPath).collect { progress ->
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - startTime) / 1000L
                val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                val transferredBytes = (fileSize * progress) / 100

                val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                val speed = formatSize(speedBytesPerSec) + "/s"

                val eta = if (speedBytesPerSec > 0) {
                    val remainingBytes = fileSize - transferredBytes
                    val remainingSeconds = remainingBytes / speedBytesPerSec
                    formatTime(remainingSeconds)
                } else "Calculating..."

                _progressState.value = _progressState.value.copy(
                    percentage = progress,
                    speed = speed,
                    transferred = formatSize(transferredBytes),
                    eta = eta,
                    elapsed = formatTime(elapsedSeconds),
                    batchElapsed = formatTime(batchElapsedSeconds)
                )
                _uiState.value = UsbUiState.Receiving(file.name, progress / 100f)
            }
            usbLogger.i(TAG, "File sent successfully: ${file.name}")
            if (totalFiles == 1 && !_progressState.value.statusMessage.contains("Cancelled")) {
                val totalElapsedSeconds = (System.currentTimeMillis() - startTime) / 1000L
                val finalSpeed = if (totalElapsedSeconds > 0) "${formatSize(fileSize / totalElapsedSeconds)}/s" else "Done"
                _progressState.value = _progressState.value.copy(
                    isComplete = true,
                    percentage = 100,
                    filename = file.name,
                    speed = finalSpeed,
                    transferred = formatSize(fileSize),
                    total = formatSize(fileSize),
                    eta = "Done",
                    elapsed = formatTime(totalElapsedSeconds),
                    statusMessage = "Upload Complete - Successfully sent ${file.name}"
                )
                _uiState.value = UsbUiState.Success("File Sent Successfully (${file.name})")
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                usbLogger.e(TAG, "Error sending file ${file.name}: ${e.message}")
                _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                _uiState.value = UsbUiState.Error("Failed to send ${file.name}: ${e.message}")
            }
        }
    }

    fun sendRemoteFile(localFile: File, remotePath: String) {
        viewModelScope.launch {
            sendSingleFileWithProgress(localFile, remotePath)
            if (_uiState.value !is UsbUiState.Error) {
                _uiState.value = UsbUiState.Success("File Sent Successfully")
                directoryCache.remove(remotePath)
                fetchRemoteFiles(remotePath, forceRefresh = true)
            }
        }
    }

    fun fetchRemoteFilesBatch(remoteFiles: List<RemoteFile>, localSaveDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val total = remoteFiles.size
            if (total == 0) return@launch
            val batchStartTime = System.currentTimeMillis()
            val queueNames = remoteFiles.map { it.name }
            _progressState.value = _progressState.value.copy(
                isVisible = true,
                totalFiles = total,
                isComplete = false,
                queue = queueNames
            )
            for ((index, remoteFile) in remoteFiles.withIndex()) {
                if (!coroutineContext.isActive || _progressState.value.statusMessage.contains("Cancelled")) break
                _progressState.value = _progressState.value.copy(currentFileIndex = index + 1)
                if (remoteFile.isDirectory) {
                    fetchRemoteDirectoryWithProgress(remoteFile, localSaveDir, batchStartTime, index + 1, total)
                } else {
                    fetchRemoteFileWithProgress(remoteFile, localSaveDir, batchStartTime, index + 1, total)
                }
            }
            if (coroutineContext.isActive && !_progressState.value.statusMessage.contains("Cancelled")) {
                val completedNames = queueNames.joinToString(", ")
                val totalElapsedSec = (System.currentTimeMillis() - batchStartTime) / 1000L
                _progressState.value = _progressState.value.copy(
                    isComplete = true,
                    percentage = 100,
                    filename = completedNames,
                    eta = "Done",
                    elapsed = formatTime(totalElapsedSec),
                    statusMessage = "Fetch Complete - Successfully downloaded: $completedNames"
                )
                _uiState.value = UsbUiState.Success("Fetch Complete ($completedNames)")
            }
        }
    }

    fun fetchRemoteFile(remotePath: String, localSaveDir: File) {
        viewModelScope.launch {
            val fileName = remotePath.substringAfterLast('/')
            val existingFile = _remoteFiles.value.find { it.path == remotePath }
            val targetRemoteFile = existingFile ?: RemoteFile(fileName, false, 0, remotePath)
            fetchRemoteFileWithProgress(targetRemoteFile, localSaveDir)
            if (_uiState.value !is UsbUiState.Error) {
                _uiState.value = UsbUiState.Success("File Received Successfully")
            }
        }
    }

    fun fetchRemoteDirectory(remotePath: String, localSaveDir: File) {
        viewModelScope.launch {
            val dirName = remotePath.substringAfterLast('/')
            val existingDir = _remoteFiles.value.find { it.path == remotePath }
            val targetRemoteDir = existingDir ?: RemoteFile(dirName, true, 0, remotePath)
            fetchRemoteDirectoryWithProgress(targetRemoteDir, localSaveDir)
            if (_uiState.value !is UsbUiState.Error) {
                _uiState.value = UsbUiState.Success("Directory Received Successfully ($dirName.zip)")
            }
        }
    }

    private suspend fun fetchRemoteFileWithProgress(remoteFile: RemoteFile, localSaveDir: File, batchStartTime: Long = System.currentTimeMillis(), currentFileIndex: Int = 1, totalFiles: Int = 1) {
        if (!localSaveDir.exists()) localSaveDir.mkdirs()
        val targetFile = File(localSaveDir, remoteFile.name)
        usbLogger.i(TAG, "Fetching file: ${remoteFile.name} (size: ${remoteFile.size} bytes)")
        _uiState.value = UsbUiState.Transferring

        val startTime = System.currentTimeMillis()
        val fileSize = remoteFile.size

        _progressState.value = _progressState.value.copy(
            isVisible = true,
            filename = remoteFile.name,
            total = formatSize(fileSize),
            percentage = 0,
            speed = "0 B/s",
            transferred = "0 B",
            eta = "Calculating...",
            elapsed = "0s",
            statusMessage = "Fetching ${remoteFile.name}...",
            currentFileIndex = currentFileIndex,
            totalFiles = totalFiles
        )

        try {
            fetchFileUseCase(remoteFile.path, targetFile).collect { progress ->
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - startTime) / 1000L
                val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                val transferredBytes = if (fileSize > 0) (fileSize * progress) / 100 else if (targetFile.exists()) targetFile.length() else 0L
                val currentTotalSize = if (fileSize > 0) fileSize else transferredBytes

                val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                val speed = formatSize(speedBytesPerSec) + "/s"

                val eta = if (speedBytesPerSec > 0 && currentTotalSize > transferredBytes) {
                    val remainingBytes = currentTotalSize - transferredBytes
                    val remainingSeconds = remainingBytes / speedBytesPerSec
                    formatTime(remainingSeconds)
                } else "Calculating..."

                _progressState.value = _progressState.value.copy(
                    percentage = progress,
                    speed = speed,
                    transferred = formatSize(transferredBytes),
                    total = formatSize(currentTotalSize),
                    eta = eta,
                    elapsed = formatTime(elapsedSeconds),
                    batchElapsed = formatTime(batchElapsedSeconds)
                )
                _uiState.value = UsbUiState.Receiving(remoteFile.name, progress / 100f)
            }
            usbLogger.i(TAG, "Fetched to ${targetFile.absolutePath}")
            if (totalFiles == 1 && !_progressState.value.statusMessage.contains("Cancelled")) {
                val finalFileSize = if (fileSize > 0) fileSize else if (targetFile.exists()) targetFile.length() else 0L
                val totalElapsedSeconds = (System.currentTimeMillis() - startTime) / 1000L
                val finalSpeed = if (totalElapsedSeconds > 0 && finalFileSize > 0) "${formatSize(finalFileSize / totalElapsedSeconds)}/s" else "Done"
                val finalSizeStr = if (finalFileSize > 0) formatSize(finalFileSize) else "Done"
                _progressState.value = _progressState.value.copy(
                    isComplete = true,
                    percentage = 100,
                    filename = remoteFile.name,
                    speed = finalSpeed,
                    transferred = finalSizeStr,
                    total = finalSizeStr,
                    eta = "Done",
                    elapsed = formatTime(totalElapsedSeconds),
                    statusMessage = "Fetch Complete - Saved to ${targetFile.parentFile?.name ?: "Download"}/${targetFile.name}"
                )
                _uiState.value = UsbUiState.Success("File Received Successfully (${remoteFile.name})")
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                usbLogger.e(TAG, "Error fetching ${remoteFile.name}: ${e.message}")
                _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                _uiState.value = UsbUiState.Error("Failed to fetch ${remoteFile.name}: ${e.message}")
            }
        }
    }

    private suspend fun fetchRemoteDirectoryWithProgress(remoteFile: RemoteFile, localSaveDir: File, batchStartTime: Long = System.currentTimeMillis(), currentFileIndex: Int = 1, totalFiles: Int = 1) {
        if (!localSaveDir.exists()) localSaveDir.mkdirs()
        val targetZip = File(localSaveDir, "${remoteFile.name}.zip")
        usbLogger.i(TAG, "Fetching directory: ${remoteFile.name} as ZIP")
        _uiState.value = UsbUiState.Transferring

        val startTime = System.currentTimeMillis()

        _progressState.value = _progressState.value.copy(
            isVisible = true,
            filename = "${remoteFile.name}.zip",
            total = "ZIP Stream",
            percentage = 0,
            speed = "0 B/s",
            transferred = "0 B",
            eta = "Processing...",
            elapsed = "0s",
            statusMessage = "Downloading ${remoteFile.name}.zip...",
            currentFileIndex = currentFileIndex,
            totalFiles = totalFiles
        )

        try {
            fetchDirectoryUseCase(remoteFile.path, targetZip).collect { progress ->
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - startTime) / 1000L
                val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                val transferredBytes = targetZip.length()

                val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                val speed = formatSize(speedBytesPerSec) + "/s"

                _progressState.value = _progressState.value.copy(
                    percentage = progress,
                    speed = speed,
                    transferred = formatSize(transferredBytes),
                    total = formatSize(transferredBytes),
                    elapsed = formatTime(elapsedSeconds),
                    batchElapsed = formatTime(batchElapsedSeconds)
                )
                _uiState.value = UsbUiState.Receiving("${remoteFile.name}.zip", progress / 100f)
            }
            usbLogger.i(TAG, "Fetched directory to ${targetZip.absolutePath}")
            if (totalFiles == 1 && !_progressState.value.statusMessage.contains("Cancelled")) {
                val totalElapsedSeconds = (System.currentTimeMillis() - startTime) / 1000L
                val finalSize = targetZip.length()
                val finalSpeed = if (totalElapsedSeconds > 0 && finalSize > 0) "${formatSize(finalSize / totalElapsedSeconds)}/s" else "Done"
                val finalSizeStr = if (finalSize > 0) formatSize(finalSize) else "Done"
                _progressState.value = _progressState.value.copy(
                    isComplete = true,
                    percentage = 100,
                    filename = "${remoteFile.name}.zip",
                    speed = finalSpeed,
                    transferred = finalSizeStr,
                    total = finalSizeStr,
                    eta = "Done",
                    elapsed = formatTime(totalElapsedSeconds),
                    statusMessage = "Fetch Complete - Saved to ${targetZip.parentFile?.name ?: "Download"}/${targetZip.name}"
                )
                _uiState.value = UsbUiState.Success("Directory Received Successfully (${remoteFile.name}.zip)")
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                usbLogger.e(TAG, "Error fetching directory ${remoteFile.name}: ${e.message}")
                _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                _uiState.value = UsbUiState.Error("Failed to fetch directory ${remoteFile.name}: ${e.message}")
            }
        }
    }



    fun deleteRemoteFile(remotePath: String) {
        viewModelScope.launch {
            if (deleteFileUseCase(remotePath)) {
                directoryCache.remove(_currentRemotePath.value)
                fetchRemoteFiles(_currentRemotePath.value, forceRefresh = true)
            }
        }
    }

    fun renameRemoteFile(oldPath: String, newPath: String) {
        viewModelScope.launch {
            if (renameFileUseCase(oldPath, newPath)) {
                directoryCache.remove(_currentRemotePath.value)
                fetchRemoteFiles(_currentRemotePath.value, forceRefresh = true)
            }
        }
    }

    fun sendTextAsRemoteFile(context: android.content.Context, fileName: String, content: String, targetFolder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, fileName)
            try {
                tempFile.writeText(content)
                sendSingleFileWithProgress(tempFile, targetFolder)
                directoryCache.remove(targetFolder)
                fetchRemoteFiles(targetFolder, forceRefresh = true)
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to send text file $fileName", e)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    fun prepareLocalSmartNavStaging(stagingDir: File, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            usbLogger.i(TAG, "prepareLocalSmartNavStaging: Initializing SmartNavRoot hierarchy under ${stagingDir.absolutePath}...")
            val foldersAndFiles = listOf(
                Pair(Constants.SmartnavRoot.DIR_PASSWORD, Pair(Constants.SmartnavRoot.FILE_PASSWORD, Constants.SmartnavRoot.DEFAULT_PASSWORD_VALUE)),
                Pair(Constants.SmartnavRoot.DIR_PASSWORD, Pair(Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD, Constants.SmartnavRoot.DEFAULT_MAINTENANCE_PASSWORD_VALUE)),
                Pair(Constants.SmartnavRoot.DIR_PASSWORD, Pair(Constants.SmartnavRoot.FILE_KMM_PASSWORD, Constants.SmartnavRoot.DEFAULT_KMM_PASSWORD_VALUE)),
                Pair("${Constants.SmartnavRoot.DIR_TRACKS}/${Constants.SmartnavRoot.DIR_TRACKS_META}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_TRACE, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_IMEI, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("updateApp", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_FIRMWARE_UPGRADE, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_RASTER}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_VECTOR}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_ICONS}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_DATABASE, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_LOG_MANAGER, Pair(Constants.SmartnavRoot.FILE_LOG_COUNTER, Constants.SmartnavRoot.DEFAULT_LOG_COUNTER_VALUE)),
                Pair(Constants.SmartnavRoot.DIR_GNSS_DATA_LOGS, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_DEV_LOGS}/${Constants.SmartnavRoot.DIR_CRASH_LOGS}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, ""))
            )
            for ((folder, filePair) in foldersAndFiles) {
                if (!isActive) break
                val (fileName, content) = filePair
                val dir = File(stagingDir, folder)
                dir.mkdirs()
                val file = File(dir, fileName)
                if (!file.exists()) {
                    file.writeText(content)
                }
            }
            usbLogger.i(TAG, "prepareLocalSmartNavStaging: Done.")
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun sendMultipleFiles(files: List<File>, targetFolder: String) {
        viewModelScope.launch {
            usbLogger.i(TAG, "sendMultipleFiles: sending ${files.size} folders to $targetFolder")
            _isRemoteLoading.value = true
            for (file in files) {
                if (!isActive) break
                if (file.isDirectory) {
                    sendDirectory(file, targetFolder)
                } else {
                    sendSingleFileWithProgress(file, targetFolder)
                }
            }
            _isRemoteLoading.value = false
            directoryCache.clear()
            fetchRemoteFiles(targetFolder, forceRefresh = true)
        }
    }

    private suspend fun sendDirectory(dir: File, destinationPath: String, batchStartTime: Long = System.currentTimeMillis(), currentFileIndex: Int = 1, totalFiles: Int = 1) {
        usbLogger.i(TAG, "Preparing to send directory: ${dir.name} to $destinationPath")
        val tempZip = withContext(Dispatchers.IO) { File.createTempFile(dir.name, ".zip") }
        try {
            _progressState.value = _progressState.value.copy(
                isVisible = true,
                filename = "${dir.name}.zip",
                statusMessage = "Zipping ${dir.name}...",
                currentFileIndex = currentFileIndex,
                totalFiles = totalFiles
            )
            zipDirectory(dir, tempZip) { currentItem ->
                _progressState.value = _progressState.value.copy(statusMessage = "Zipping: $currentItem")
            }
            usbLogger.i(TAG, "Sending zipped directory: ${tempZip.name} to $destinationPath")
            
            val startTime = System.currentTimeMillis()
            val fileSize = tempZip.length()
            
            _progressState.value = _progressState.value.copy(
                statusMessage = "Sending ${dir.name}.zip...",
                total = formatSize(fileSize),
                percentage = 0,
                transferred = "0 B",
                speed = "0 B/s"
            )
            
            sendFileUseCase(tempZip, destinationPath, isDirectory = true, remoteFileName = dir.name).collect { progress ->
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - startTime) / 1000L
                val transferredBytes = (fileSize * progress) / 100
                val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                val eta = if (speedBytesPerSec > 0) ((fileSize - transferredBytes) / speedBytesPerSec).toString() + "s" else "Calculating..."
                
                _progressState.value = _progressState.value.copy(
                    percentage = progress,
                    transferred = formatSize(transferredBytes),
                    speed = "${formatSize(speedBytesPerSec)}/s",
                    eta = eta,
                    elapsed = "${elapsedSeconds}s"
                )
            }
            _progressState.value = _progressState.value.copy(
                isComplete = true,
                statusMessage = "Sent ${dir.name}.zip successfully",
                percentage = 100
            )
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                usbLogger.e(TAG, "Error sending directory ${dir.name}", e)
                _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
            }
        } finally {
            withContext(Dispatchers.IO) { tempZip.delete() }
        }
    }

    private suspend fun zipDirectory(dir: File, zipFile: File, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zout ->
            dir.walkTopDown().forEach { file ->
                ensureActive()
                val entryName = file.toRelativeString(dir).replace('\\', '/')
                if (entryName.isNotEmpty()) {
                    val entry = java.util.zip.ZipEntry(if (file.isDirectory) "$entryName/" else entryName)
                    zout.putNextEntry(entry)
                    if (!file.isDirectory) {
                        onProgress(entryName)
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                ensureActive()
                                zout.write(buffer, 0, bytes)
                                bytes = input.read(buffer)
                            }
                        }
                    }
                    zout.closeEntry()
                }
            }
        }
    }

    fun disconnect(sendSignal: Boolean = true) {
        usbLogger.d(TAG, "disconnect: Resetting connection and state (sendSignal=$sendSignal, role=${_usbRole.value})")
        connectJob?.cancel()
        connectJob = null
        commandJob?.cancel()
        commandJob = null

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

                if (_usbRole.value is UsbRole.Client || clientServiceController.isServiceRunning.value) {
                    clientServiceController.stopService()
                } else {
                    delegatingConnection.disconnect()
                    aoaManager.disconnect()
                    hostManager.disconnect()
                }

                currentDevice = null
                currentAccessory = null

                if (!sendSignal || !_isCablePhysicallyConnected.value || !usbManagerWrapper.isUsbCablePhysicallyConnected()) {
                    usbLogger.i(TAG, "disconnect: Cable unplugged or remote disconnect. Clearing role and setting state to NoDevice.")
                    _usbRole.value = null
                    preferencesManager.usbRole = null
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
