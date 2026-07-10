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
import com.example.usbtransferapp.domain.model.RemoteFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val fetchDirectoryUseCase: com.example.usbtransferapp.domain.usecases.FetchDirectoryUseCase,
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

    private val _usbRole = MutableStateFlow<UsbRole?>(preferencesManager.usbRole)
    val usbRole: StateFlow<UsbRole?> = _usbRole

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/sdcard")
    val currentRemotePath: StateFlow<String> = _currentRemotePath

    private val _isRemoteLoading = MutableStateFlow(false)
    val isRemoteLoading: StateFlow<Boolean> = _isRemoteLoading

    private val directoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<RemoteFile>>>()

    val logLines: StateFlow<List<String>> = usbLogger.logLines
    fun clearLogs() = usbLogger.clearLogs()
    fun getLogFilePath() = usbLogger.getLogFilePath()

    init {
        usbLogger.i(TAG, "ViewModel initialized. Role: ${_usbRole.value}")
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
        if (isBusyOrConnected()) {
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
        if (isBusyOrConnected()) {
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
                    val grantedEvent = withTimeoutOrNull(60_000) {
                        UsbPermissionBus.flow.firstOrNull { event ->
                            (event is UsbPermissionEvent.DeviceGranted && event.device.deviceId == device.deviceId) ||
                            (event is UsbPermissionEvent.DeviceDenied && event.device.deviceId == device.deviceId)
                        }
                    }
                    if (grantedEvent is UsbPermissionEvent.DeviceGranted) {
                        usbLogger.i(TAG, "requestPermissionAndConnect: Permission GRANTED for device ${device.deviceId}")
                        proceedWithDevice(grantedEvent.device)
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
                    val grantedEvent = withTimeoutOrNull(60_000) {
                        UsbPermissionBus.flow.firstOrNull { event ->
                            (event is UsbPermissionEvent.AccessoryGranted && event.accessory == accessory) ||
                            (event is UsbPermissionEvent.AccessoryDenied && event.accessory == accessory)
                        }
                    }
                    if (grantedEvent is UsbPermissionEvent.AccessoryGranted) {
                        usbLogger.i(TAG, "requestPermissionAndConnect: Permission GRANTED for accessory ${accessory.model}")
                        proceedWithAccessory(grantedEvent.accessory)
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

    fun sendRemoteFile(localFile: File, remotePath: String) {
        viewModelScope.launch {
            _uiState.value = UsbUiState.Transferring
            try {
                sendFileUseCase(localFile, remotePath).collect { prog ->
                    _uiState.value = UsbUiState.Receiving(localFile.name, prog / 100f)
                }
                _uiState.value = UsbUiState.Success("File Sent Successfully")
                directoryCache.remove(remotePath)
                fetchRemoteFiles(remotePath, forceRefresh = true)
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

    fun fetchRemoteDirectory(remotePath: String, localSaveDir: File) {
        viewModelScope.launch {
            _uiState.value = UsbUiState.Transferring
            val dirName = remotePath.substringAfterLast('/')
            val targetFile = File(localSaveDir, "$dirName.zip")
            try {
                fetchDirectoryUseCase(remotePath, targetFile).collect { prog ->
                    _uiState.value = UsbUiState.Receiving("$dirName.zip", prog / 100f)
                }
                _uiState.value = UsbUiState.Success("Directory Received Successfully ($dirName.zip)")
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to fetch directory", e)
                _uiState.value = UsbUiState.Error("Failed to fetch directory: ${e.message}")
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

                delegatingConnection.disconnect()
                aoaManager.disconnect()
                hostManager.disconnect()

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
