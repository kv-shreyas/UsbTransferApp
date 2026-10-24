package com.example.securequicktransferapp.presentation.viewmodel

import android.hardware.usb.UsbAccessory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.securequicktransferapp.data.UsbRole
import com.example.securequicktransferapp.data.UsbUiState
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.preferences.UsbPreferencesManager
import com.example.securequicktransferapp.data.usb.broadcast.UsbPermissionBus
import com.example.securequicktransferapp.data.usb.broadcast.UsbPermissionEvent
import com.example.securequicktransferapp.data.usb.connection.UsbConnectionManager
import com.example.securequicktransferapp.data.usb.connection.UsbManagerWrapper
import com.example.securequicktransferapp.data.usb.service.ClientServiceController
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.securequicktransferapp.domain.model.TransferProgress
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

/**
 * Presentation ViewModel for Android Client mode.
 * Android functions exclusively as the USB Client (AOA Accessory), receiving
 * and executing commands sent by the Desktop USB Host.
 */
@HiltViewModel
class UsbTransferViewModel @Inject constructor(
    private val usbManagerWrapper: UsbManagerWrapper,
    private val connectionManager: UsbConnectionManager,
    private val channel: SecureChannel,
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
                if (clientServiceController.isServiceRunning.value || state is UsbUiState.NoDevice || state is UsbUiState.Idle) {
                    _uiState.value = state
                }
                if (state is UsbUiState.Success && !state.deviceId.isNullOrBlank()) {
                    val currentRole = _usbRole.value as? UsbRole.Client
                    if (currentRole?.connectedHostName != state.deviceId) {
                        _usbRole.value = UsbRole.Client(connectedHostName = state.deviceId)
                        preferencesManager.usbRole = _usbRole.value
                    }
                }
            }
        }
        viewModelScope.launch {
            clientServiceController.clientTransferProgress.collect { progress ->
                if (clientServiceController.isServiceRunning.value) {
                    _progressState.value = progress
                }
            }
        }
    }

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
                    if (wasConnected || currentAccessory != null || _uiState.value !is UsbUiState.NoDevice) {
                        usbLogger.i(TAG, "USB cable physically unplugged! Triggering disconnect and setting NoDevice state.")
                        disconnect(sendSignal = false)
                    } else {
                        _uiState.value = UsbUiState.NoDevice
                    }
                } else {
                    val hasAccessory = usbManagerWrapper.getAccessories()?.isNotEmpty() == true
                    if (!wasConnected) {
                        usbLogger.i(TAG, "USB cable physically plugged in! Detecting accessory...")
                        detectAndConnect()
                    } else if (hasAccessory && currentAccessory == null && !isBusyOrConnected()) {
                        usbLogger.i(TAG, "USB Accessory detected! Desktop Host initialized AOA mode. Auto-connecting...")
                        detectAndConnect()
                    } else if (_uiState.value is UsbUiState.NoDevice && !isBusyOrConnected()) {
                        detectAndConnect()
                    }
                }
            }
        }
    }

    fun detectDevice() {
        usbLogger.d(TAG, "detectDevice: Scanning for USB accessories...")
        val accessories = usbManagerWrapper.getAccessories()
        val accessory = accessories?.firstOrNull()

        if (accessory != null) {
            val hostName = accessory.description?.takeIf { it.isNotBlank() && it != "Handshake Demo" } ?: accessory.model ?: "Desktop Host"
            usbLogger.i(TAG, "detectDevice: Found USB Accessory - ${accessory.model} (Host: $hostName)")
            currentAccessory = accessory
            _uiState.value = UsbUiState.DeviceDetected(name = hostName)
        } else {
            usbLogger.w(TAG, "detectDevice: No USB accessory detected.")
            currentAccessory = null
            _uiState.value = if (_isCablePhysicallyConnected.value) UsbUiState.Idle else UsbUiState.NoDevice
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
            val acc = currentAccessory
            val hasPerm = acc != null && usbManagerWrapper.hasPermission(acc)
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
        val accessory = currentAccessory

        if (wasError) {
            usbLogger.d(TAG, "detectAndConnect: In Error state after previous connection attempt. Waiting for user interaction or physical re-plug.")
            return
        }

        if (accessory != null) {
            val hostName = accessory.description?.takeIf { it.isNotBlank() && it != "Handshake Demo" } ?: accessory.model ?: "Desktop Host"
            usbLogger.i(TAG, "detectAndConnect: UsbAccessory detected ($hostName). Desktop Host initialized AOA mode. Auto-connecting as Client...")
            _usbRole.value = UsbRole.Client(connectedHostName = hostName)
            preferencesManager.usbRole = _usbRole.value
            requestPermissionAndConnect()
        } else {
            usbLogger.d(TAG, "detectAndConnect: No USB accessory found. Waiting for Desktop Host to initiate AOA connection.")
        }
    }

    fun requestPermissionAndConnect() {
        if (currentAccessory == null) {
            detectDevice()
        }
        val accessory = currentAccessory
        val hasPerm = accessory != null && usbManagerWrapper.hasPermission(accessory)
        if (isBusyOrConnected() && !(_uiState.value is UsbUiState.RequestingPermission && hasPerm)) {
            usbLogger.d(TAG, "requestPermissionAndConnect: Already actively connecting or checking (state: ${_uiState.value}, connectJob=${connectJob?.isActive}). Ignoring redundant request.")
            return
        }

        if (accessory == null) {
            usbLogger.d(TAG, "requestPermissionAndConnect: No accessory available. Physical cable connected: ${_isCablePhysicallyConnected.value}")
            _uiState.value = if (_isCablePhysicallyConnected.value) UsbUiState.Idle else UsbUiState.NoDevice
            return
        }

        _uiState.value = UsbUiState.Connecting
        connectJob = viewModelScope.launch {
            commandJob?.cancel()
            commandJob = null
            
            connectionManager.disconnect()

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
        }
    }

    private suspend fun proceedWithAccessory(accessory: UsbAccessory) {
        val hostName = accessory.description?.takeIf { it.isNotBlank() && it != "Handshake Demo" } ?: accessory.model ?: "Desktop Host"
        usbLogger.d(TAG, "proceedWithAccessory: Starting client foreground service for accessory: $hostName...")
        _uiState.value = UsbUiState.Connecting
        _usbRole.value = UsbRole.Client(connectedHostName = hostName)
        preferencesManager.usbRole = _usbRole.value
        clientServiceController.startService(accessory)
    }

    fun cancelTransfer() {
        commandJob?.cancel()
        commandJob = null
        _progressState.value = _progressState.value.copy(statusMessage = "Transfer Cancelled ❌", isComplete = true)
        _uiState.value = UsbUiState.Success("Transfer Cancelled")
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
            // Attempt to send disconnect packet to Desktop Host
            try {
                withTimeoutOrNull(250.milliseconds) {
                    channel.sendSecure(byteArrayOf(4.toByte()))
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
                channel.disconnect()

                currentAccessory = null

                // Clear persisted preferences
                preferencesManager.clear()
                preferencesManager.usbRole = null

                // Reset role and progress
                _usbRole.value = null
                dismissProgress()

                // Check physical cable and reset UI state to Idle so client is ready
                val isCable = usbManagerWrapper.isUsbCablePhysicallyConnected()
                _isCablePhysicallyConnected.value = isCable
                _uiState.value = if (isCable) UsbUiState.Idle else UsbUiState.NoDevice

                usbLogger.i(TAG, "resetConnection: Reset complete. Ready for Desktop Host connection.")
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
                        channel.sendSecure(byteArrayOf(4.toByte()))
                        delay(50.milliseconds)
                    }
                } catch (e: Exception) {
                    usbLogger.w(TAG, "disconnect: Failed to send disconnect command to remote", e)
                }
            }

            withContext(Dispatchers.Main) {
                clientServiceController.reset()
                connectionManager.disconnect()
                channel.disconnect()

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
