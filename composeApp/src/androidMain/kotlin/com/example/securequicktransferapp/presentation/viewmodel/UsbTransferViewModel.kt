package com.example.securequicktransferapp.presentation.viewmodel

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.securequicktransferapp.data.UsbRole
import com.example.securequicktransferapp.data.UsbUiState
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.preferences.UsbPreferencesManager
import com.example.securequicktransferapp.data.usb.broadcast.UsbPermissionBus
import com.example.securequicktransferapp.data.usb.broadcast.UsbPermissionEvent
import com.example.securequicktransferapp.data.usb.command.HostCommandSender
import com.example.securequicktransferapp.data.usb.connection.AoaHostSwitcher
import com.example.securequicktransferapp.data.usb.connection.UsbConnectionManager
import com.example.securequicktransferapp.data.usb.connection.UsbManagerWrapper
import com.example.securequicktransferapp.data.usb.service.ClientServiceController
import com.example.securequicktransferapp.data.usb.storage.UsbDataSource
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.usecases.UsbUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class UsbTransferViewModel @Inject constructor(
    private val usbManagerWrapper: UsbManagerWrapper,
    private val connectionManager: UsbConnectionManager,
    private val aoaHostSwitcher: AoaHostSwitcher,
    private val dataSource: UsbDataSource,
    private val hostCommandSender: HostCommandSender,
    private val usbUseCases: UsbUseCases,
    private val preferencesManager: UsbPreferencesManager,
    private val clientServiceController: ClientServiceController,
    val usbLogger: UsbLogger
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

    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState.asStateFlow()

    val logLines: StateFlow<List<String>> = usbLogger.logLines
    fun clearLogs() = usbLogger.clearLogs()
    fun getLogFilePath() = usbLogger.getLogFilePath()

    fun installAppUpdate(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val updateDirs = listOf(
                File(android.os.Environment.getExternalStorageDirectory(), "updateUsbApp"),
                File("/sdcard/updateUsbApp")
            )

            // Priority: usb_update_app.apk first, followed by usb_desktop_update.apk
            val candidateNames = listOf("usb_update_app.apk", "usb_desktop_update.apk")
            var apkFile: File? = null

            for (dir in updateDirs) {
                for (name in candidateNames) {
                    val candidate = File(dir, name)
                    if (candidate.exists() && candidate.isFile) {
                        apkFile = candidate
                        break
                    }
                }
                if (apkFile != null) break
            }

            // Fallback: check if any .apk exists in updateUsbApp directory
            if (apkFile == null) {
                for (dir in updateDirs) {
                    if (dir.exists() && dir.isDirectory) {
                        val anyApk = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
                            ?.firstOrNull()
                        if (anyApk != null) {
                            apkFile = anyApk
                            break
                        }
                    }
                }
            }

            if (apkFile == null || !apkFile.exists()) {
                val errorMsg = "Update file not found (expected usb_update_app.apk at /sdcard/updateUsbApp/)"
                Log.e(TAG, errorMsg)
                usbLogger.e(TAG, errorMsg)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            usbLogger.i(TAG, "Found update APK: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

            // Check REQUEST_INSTALL_PACKAGES permission on Android 8.0+ (Oreo)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Please grant permission to install unknown apps, then retry update.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        try {
                            val settingsIntent = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(settingsIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open install unknown apps settings", e)
                        }
                    }
                    return@launch
                }
            }

            try {
                val fileToShare = try {
                    apkFile.canonicalFile
                } catch (e: Exception) {
                    apkFile
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    fileToShare
                )

                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryIntentActivities(
                        intent,
                        android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong())
                    )
                } else {
                    context.packageManager.queryIntentActivities(
                        intent,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                    )
                }
                for (resolveInfo in resolveInfos) {
                    val packageName = resolveInfo.activityInfo.packageName
                    context.grantUriPermission(packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(intent)
                usbLogger.i(TAG, "Launched installer for: ${apkFile.name}")
            } catch (e: Exception) {
                val msg = "Update failed: ${e.localizedMessage ?: e.message}"
                Log.e(TAG, msg, e)
                usbLogger.e(TAG, msg)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
                if (_usbRole.value is UsbRole.Client) {
                    if (clientServiceController.isServiceRunning.value || state is UsbUiState.NoDevice || state is UsbUiState.Idle) {
                        _uiState.value = state
                    }
                    if (state is UsbUiState.Success && !state.deviceId.isNullOrBlank()) {
                        val currentRole = _usbRole.value as UsbRole.Client
                        if (currentRole.connectedHostName != state.deviceId) {
                            _usbRole.value = UsbRole.Client(connectedHostName = state.deviceId)
                            preferencesManager.usbRole = _usbRole.value
                        }
                    }
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
                    val hasAccessory = usbManagerWrapper.getAccessories()?.isNotEmpty() == true
                    // Whenever cable is plugged in fresh (wasConnected was false), re-detect device.
                    if (!wasConnected) {
                        usbLogger.i(TAG, "USB cable physically plugged in! Re-detecting device...")
                        detectAndConnect()
                    } else if (hasAccessory && currentAccessory == null && !isBusyOrConnected()) {
                        usbLogger.i(TAG, "USB Accessory detected! Host switched to AOA mode. Auto-connecting...")
                        detectAndConnect()
                    } else if (_uiState.value is UsbUiState.NoDevice && !isBusyOrConnected()) {
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
            val hostName = accessory.description?.takeIf { it.isNotBlank() && it != "Handshake Demo" } ?: accessory.model ?: "USB Accessory"
            _uiState.value = UsbUiState.DeviceDetected(name = hostName)
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
            val hostName = accessory.description?.takeIf { it.isNotBlank() && it != "Handshake Demo" } ?: accessory.model ?: "USB Host"
            usbLogger.i(TAG, "detectAndConnect: UsbAccessory detected ($hostName). Remote device switched us to AOA mode. Auto-connecting as Client...")
            _usbRole.value = UsbRole.Client(connectedHostName = hostName)
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
        if (currentDevice == null && currentAccessory == null) {
            detectDevice()
        }
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
            
            connectionManager.disconnect()

            val device = currentDevice
            val accessory = currentAccessory

            if (device != null) {
                if (!usbManagerWrapper.hasPermission(device)) {
                    usbLogger.d(TAG, "requestPermissionAndConnect: Requesting permission for device ${device.deviceId}")
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(device)
                    val granted = withTimeoutOrNull(60_000.milliseconds) {
                        var isGranted = false
                        while (isActive) {
                            if (usbManagerWrapper.hasPermission(device)) {
                                isGranted = true
                                break
                            }
                            val event = withTimeoutOrNull(250.milliseconds) { UsbPermissionBus.flow.firstOrNull() }
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
                    val granted = withTimeoutOrNull(60_000.milliseconds) {
                        var isGranted = false
                        while (isActive) {
                            if (usbManagerWrapper.hasPermission(accessory)) {
                                isGranted = true
                                break
                            }
                            val event = withTimeoutOrNull(250.milliseconds) { UsbPermissionBus.flow.firstOrNull() }
                            if (event is UsbPermissionEvent.AccessoryGranted && event.accessory.model == accessory.model && event.accessory.manufacturer == accessory.manufacturer) {
                                isGranted = true
                                break
                            } else if (event is UsbPermissionEvent.AccessoryDenied && event.accessory.model == accessory.model && event.accessory.manufacturer == accessory.manufacturer) {
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

            connectionManager.disconnect()

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
        // Always update the role to ensure the real connectedDeviceName is populated rather than the hardcoded fallback from UI manual selection.
        val actualDeviceName = device.productName ?: "Remote Device"
        if (_usbRole.value !is UsbRole.Host || (_usbRole.value as UsbRole.Host).connectedDeviceName != actualDeviceName) {
            usbLogger.i(TAG, "proceedWithDevice: Hardware USB Host detected. Aligning role to Host(connectedDeviceName=$actualDeviceName)")
            _usbRole.value = UsbRole.Host(actualDeviceName)
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
                if (connectionManager.connect(aoaDevice)) {
                    usbLogger.i(TAG, "proceedWithDevice: Host connection to AOA device successful.")
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
            if (connectionManager.connect(device)) {
                usbLogger.i(TAG, "proceedWithDevice: Host connection to already-in-AOA device successful.")
                startHostHandshakeAndCommands()
            } else {
                usbLogger.e(TAG, "proceedWithDevice: Failed to connect to AOA device.")
                _uiState.value = UsbUiState.Error("Failed to connect to AOA device")
            }
        }
    }

    private suspend fun proceedWithAccessory(accessory: UsbAccessory) {
        val hostName = accessory.description?.takeIf { it.isNotBlank() && it != "Handshake Demo" } ?: accessory.model ?: "USB Host"
        usbLogger.d(TAG, "proceedWithAccessory: Starting client foreground service for accessory: $hostName...")
        _uiState.value = UsbUiState.Connecting
        _usbRole.value = UsbRole.Client(connectedHostName = hostName)
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
            } else {
                usbLogger.e(TAG, "startHostHandshakeAndCommands: Client failed ready handshake.")
                _uiState.value = UsbUiState.Error("Timed out waiting for remote device to enter Client mode.")
            }
        } else {
            usbLogger.e(TAG, "startHostHandshakeAndCommands: Handshake FAILED as Initiator.")
            _uiState.value = UsbUiState.Error("Cryptographic handshake failed or timed out.")
        }
    }


    fun cancelTransfer() {
        commandJob?.cancel()
        commandJob = null
        _progressState.value = _progressState.value.copy(statusMessage = "Transfer Cancelled ❌", isComplete = true)
        _uiState.value = UsbUiState.Success("Transfer Cancelled")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                usbUseCases.cancelTransfer()
            } catch (e: Exception) {
                usbLogger.w(TAG, "cancelTransfer error: ${e.message}")
            }
        }
    }

    fun dismissProgress() {
        _progressState.value = TransferProgress()
    }


    fun resetConnection() {
        usbLogger.i(TAG, "resetConnection: User requested full reset. Resetting connection, role, and all state to start fresh.")
        connectJob?.cancel()
        connectJob = null
        commandJob?.cancel()
        commandJob = null

        viewModelScope.launch(Dispatchers.IO) {
            // Attempt to send disconnect packet to remote device
            try {
                withTimeoutOrNull(250.milliseconds) {
                    if (_usbRole.value is UsbRole.Host) {
                        hostCommandSender.sendDisconnect()
                    } else {
                        dataSource.sendSecure(byteArrayOf(4.toByte()))
                    }
                    delay(50.milliseconds)
                }
            } catch (e: Exception) {
                usbLogger.d(TAG, "resetConnection: Remote disconnect signal skipped: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                // Stop and reset client service controller
                clientServiceController.reset()

                // Clean up hardware connections and crypto session
                connectionManager.disconnect()
                dataSource.disconnect()

                currentDevice = null
                currentAccessory = null

                // Clear persisted preferences
                preferencesManager.clear()
                preferencesManager.usbRole = null

                // Reset role and progress
                _usbRole.value = null
                dismissProgress()

                // Check physical cable and reset UI state to Idle so user can select role or connect to fresh device
                val isCable = usbManagerWrapper.isUsbCablePhysicallyConnected()
                _isCablePhysicallyConnected.value = isCable
                _uiState.value = if (isCable) UsbUiState.Idle else UsbUiState.NoDevice

                usbLogger.i(TAG, "resetConnection: Reset complete. Ready to connect to any fresh device.")
            }
        }
    }

    fun disconnect(sendSignal: Boolean = true) {
        usbLogger.d(TAG, "disconnect: Resetting connection and state (sendSignal=$sendSignal, role=${_usbRole.value})")
        connectJob?.cancel()
        connectJob = null
        commandJob?.cancel()
        commandJob = null

        viewModelScope.launch(Dispatchers.IO) {
            if (sendSignal) {
                try {
                    withTimeoutOrNull(250.milliseconds) {
                        if (_usbRole.value is UsbRole.Host) {
                            hostCommandSender.sendDisconnect()
                        } else {
                            dataSource.sendSecure(byteArrayOf(4.toByte()))
                        }
                        delay(50.milliseconds)
                    }
                } catch (e: Exception) {
                    usbLogger.w(TAG, "disconnect: Failed to send disconnect command to remote", e)
                }
            }

            withContext(Dispatchers.Main) {

                if (_usbRole.value is UsbRole.Client || clientServiceController.isServiceRunning.value) {
                    clientServiceController.reset()
                } else {
                    connectionManager.disconnect()
                    dataSource.disconnect()
                }

                currentDevice = null
                currentAccessory = null

                if (!sendSignal || !_isCablePhysicallyConnected.value || !usbManagerWrapper.isUsbCablePhysicallyConnected()) {
                    usbLogger.i(TAG, "disconnect: Cable unplugged or remote disconnect. Clearing role and setting state to NoDevice.")
                    _usbRole.value = null
                    preferencesManager.usbRole = null
                    _uiState.value = UsbUiState.NoDevice
                    dismissProgress()
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
