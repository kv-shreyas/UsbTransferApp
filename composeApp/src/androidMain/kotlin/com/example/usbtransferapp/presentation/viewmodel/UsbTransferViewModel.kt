package com.example.usbtransferapp.presentation.viewmodel

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.usbtransferapp.data.UsbUiState
import com.example.usbtransferapp.data.usb.AoaConnectionManager
import com.example.usbtransferapp.data.usb.DelegatingUsbConnection
import com.example.usbtransferapp.data.usb.UsbConnectionManager
import com.example.usbtransferapp.data.usb.UsbDataSource
import com.example.usbtransferapp.data.usb.UsbManagerWrapper
import com.example.usbtransferapp.data.usb.UsbPermissionBus
import com.example.usbtransferapp.data.usb.UsbPermissionEvent
import com.example.usbtransferapp.data.usb.UsbCommandProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UsbTransferViewModel @Inject constructor(
    private val usbManagerWrapper: UsbManagerWrapper,
    private val hostManager: UsbConnectionManager,
    private val aoaManager: AoaConnectionManager,
    private val delegatingConnection: DelegatingUsbConnection,
    private val dataSource: UsbDataSource,
    private val commandProcessor: UsbCommandProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow<UsbUiState>(UsbUiState.Idle)
    val uiState: StateFlow<UsbUiState> = _uiState

    private val _isCablePhysicallyConnected = MutableStateFlow(false)
    val isCablePhysicallyConnected: StateFlow<Boolean> = _isCablePhysicallyConnected

    init {
        startCableMonitor()
    }

    private var currentDevice: UsbDevice? = null
    private var currentAccessory: UsbAccessory? = null
    private var commandJob: Job? = null
    private var cableMonitorJob: Job? = null
    private val TAG = "UsbTransferVM"

    private fun startCableMonitor() {
        cableMonitorJob?.cancel()
        cableMonitorJob = viewModelScope.launch {
            usbManagerWrapper.observeCableState().collect { isConnected ->
                Log.i(TAG, "Physical cable connection state changed: $isConnected")
                val wasConnected = _isCablePhysicallyConnected.value
                _isCablePhysicallyConnected.value = isConnected

                if (!isConnected) {
                    if (wasConnected || currentDevice != null || currentAccessory != null || _uiState.value !is UsbUiState.NoDevice) {
                        Log.i(TAG, "USB cable physically unplugged! Triggering disconnect and setting NoDevice state.")
                        disconnect(sendSignal = false)
                    } else {
                        _uiState.value = UsbUiState.NoDevice
                    }
                } else if (isConnected && !wasConnected && _uiState.value is UsbUiState.NoDevice) {
                    Log.i(TAG, "USB cable physically plugged in! Re-detecting device...")
                    detectDevice()
                }
            }
        }
    }

    fun detectDevice() {
        Log.d(TAG, "detectDevice: Scanning for USB devices and accessories...")
        val devices = usbManagerWrapper.getDevices()
        val accessories = usbManagerWrapper.getAccessories()
        
        val device = devices.firstOrNull()
        val accessory = accessories?.firstOrNull()

        if (device != null) {
            Log.i(TAG, "detectDevice: Found USB Device - ${device.productName} (ID: ${device.deviceId})")
            currentDevice = device
            currentAccessory = null
            _uiState.value = UsbUiState.DeviceDetected(name = device.productName ?: "USB Device")
        } else if (accessory != null) {
            Log.i(TAG, "detectDevice: Found USB Accessory - ${accessory.model} (Manufacturer: ${accessory.manufacturer})")
            currentAccessory = accessory
            currentDevice = null
            _uiState.value = UsbUiState.DeviceDetected(name = accessory.model ?: "USB Accessory")
        } else {
            Log.w(TAG, "detectDevice: No USB devices or accessories detected.")
            _uiState.value = UsbUiState.NoDevice
        }
    }

    fun detectAndConnect() {
        val state = _uiState.value
        if (state is UsbUiState.Connecting || state is UsbUiState.Transferring || 
            state is UsbUiState.Receiving || state is UsbUiState.Success || 
            state is UsbUiState.RequestingPermission) {
            Log.d(TAG, "detectAndConnect: Already connecting or connected. Ignoring intent.")
            return
        }

        detectDevice()
        if (currentDevice != null || currentAccessory != null) {
            Log.i(TAG, "detectAndConnect: Device found, auto-connecting...")
            requestPermissionAndConnect()
        }
    }

    fun requestPermissionAndConnect() {
        val state = _uiState.value
        if (state is UsbUiState.Connecting || state is UsbUiState.Transferring || 
            state is UsbUiState.Receiving || state is UsbUiState.Success || 
            state is UsbUiState.RequestingPermission) {
            Log.d(TAG, "requestPermissionAndConnect: Already connecting or connected (state: $state). Ignoring redundant request.")
            return
        }

        viewModelScope.launch {
            // Ensure any previous stale connection and background jobs are completely closed before retrying
            commandJob?.cancel()
            commandJob = null
            
            delegatingConnection.disconnect()
            aoaManager.disconnect()
            hostManager.disconnect()

            val device = currentDevice
            val accessory = currentAccessory

            if (device != null) {
                if (!usbManagerWrapper.hasPermission(device)) {
                    Log.d(TAG, "requestPermissionAndConnect: Requesting permission for device ${device.deviceId}")
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(device)
                    UsbPermissionBus.flow.collect { event ->
                        if (event is UsbPermissionEvent.DeviceGranted && event.device.deviceId == device.deviceId) {
                            Log.i(TAG, "requestPermissionAndConnect: Permission GRANTED for device ${device.deviceId}")
                            proceedWithDevice(event.device)
                            cancel()
                        }
                    }
                } else {
                    Log.d(TAG, "requestPermissionAndConnect: Already have permission for device ${device.deviceId}")
                    proceedWithDevice(device)
                }
            } else if (accessory != null) {
                if (!usbManagerWrapper.hasPermission(accessory)) {
                    Log.d(TAG, "requestPermissionAndConnect: Requesting permission for accessory ${accessory.model}")
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(accessory)
                    UsbPermissionBus.flow.collect { event ->
                        if (event is UsbPermissionEvent.AccessoryGranted && event.accessory == accessory) {
                            Log.i(TAG, "requestPermissionAndConnect: Permission GRANTED for accessory ${accessory.model}")
                            proceedWithAccessory(event.accessory)
                            cancel()
                        }
                    }
                } else {
                    Log.d(TAG, "requestPermissionAndConnect: Already have permission for accessory ${accessory.model}")
                    proceedWithAccessory(accessory)
                }
            } else {
                Log.e(TAG, "requestPermissionAndConnect: Failed - No device or accessory selected.")
                _uiState.value = UsbUiState.NoDevice
            }
        }
    }

    private suspend fun proceedWithDevice(device: UsbDevice) {
        Log.d(TAG, "proceedWithDevice: Attempting to connect via Host Mode...")
        _uiState.value = UsbUiState.Connecting
        if (hostManager.connect(device)) {
            Log.i(TAG, "proceedWithDevice: Host connection successful.")
            delegatingConnection.setDelegate(hostManager)
            startHandshakeAndListen()
        } else {
            Log.w(TAG, "proceedWithDevice: Host connection failed. This is expected if the Desktop is currently negotiating AOA mode switch.")
            // Don't show a scary red error. The desktop will force a reconnect in AOA mode momentarily.
            _uiState.value = UsbUiState.DeviceDetected("Desktop Detected")
        }
    }

    private suspend fun proceedWithAccessory(accessory: UsbAccessory) {
        Log.d(TAG, "proceedWithAccessory: Attempting to connect via AOA Mode...")
        _uiState.value = UsbUiState.Connecting
        if (aoaManager.connect(accessory)) {
            Log.i(TAG, "proceedWithAccessory: AOA connection successful.")
            delegatingConnection.setDelegate(aoaManager)
            startHandshakeAndListen()
        } else {
            Log.e(TAG, "proceedWithAccessory: AOA connection failed.")
            _uiState.value = UsbUiState.Error("AOA connection failed")
        }
    }

    private suspend fun startHandshakeAndListen() {
        Log.d(TAG, "startHandshakeAndListen: Initiating handshake...")
        _uiState.value = UsbUiState.Transferring
        if (dataSource.performHandshake()) {
            Log.i(TAG, "startHandshakeAndListen: Handshake SUCCESS. Secure channel established.")
            _uiState.value = UsbUiState.Success("Secure Channel Established")
            
            commandJob = viewModelScope.launch {
                Log.d(TAG, "startHandshakeAndListen: Starting command listener...")
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
                        _uiState.value = UsbUiState.Error("Transfer Error: $errorMsg")
                    },
                    onDisconnectReceived = {
                        _uiState.value = UsbUiState.Success("Disconnected by Remote")
                        disconnect(sendSignal = false)
                    }
                )
                
                // If the loop terminated (e.g., pipe closed when unplugged or remote disconnected), reset connection
                if (_uiState.value !is UsbUiState.NoDevice && _uiState.value !is UsbUiState.Idle) {
                    Log.i(TAG, "startHandshakeAndListen: Command listener terminated unexpectedly. Resetting connection.")
                    disconnect(sendSignal = false)
                }
            }
        } else {
            Log.e(TAG, "startHandshakeAndListen: Handshake FAILED.")
            if (currentDevice != null) {
                // If we were in Host Mode, the Desktop likely just switched us to AOA mode.
                // The connection dies during this switch. We revert to a waiting state 
                // to gracefully wait for the incoming AOA intent instead of flashing an error.
                Log.i(TAG, "startHandshakeAndListen: Handshake failed in Host Mode. Waiting for AOA reconnection...")
                _uiState.value = UsbUiState.DeviceDetected("Waiting for AOA mode...")
            } else {
                _uiState.value = UsbUiState.Error("Handshake failed")
            }
        }
    }

    fun disconnect(sendSignal: Boolean = true) {
        Log.d(TAG, "disconnect: Resetting connection and state (sendSignal=$sendSignal)")
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (sendSignal) {
                try {
                    // Send CMD_DISCONNECT (4) to the Desktop so it knows we are closing
                    dataSource.sendSecure(byteArrayOf(4.toByte()))
                    kotlinx.coroutines.delay(100) // Give it a moment to flush
                } catch (e: Exception) {
                    Log.w(TAG, "disconnect: Failed to send disconnect command to desktop", e)
                }
            }
            
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                commandJob?.cancel()
                commandJob = null
                
                delegatingConnection.disconnect()
                aoaManager.disconnect()
                hostManager.disconnect()
                
                currentDevice = null
                currentAccessory = null
                
                // If disconnected without signal or cable not connected, immediately transition to NoDevice
                if (!sendSignal || !_isCablePhysicallyConnected.value || !usbManagerWrapper.isUsbCablePhysicallyConnected()) {
                    Log.i(TAG, "disconnect: Cable unplugged or remote disconnect. Setting state to NoDevice.")
                    _uiState.value = UsbUiState.NoDevice
                } else {
                    detectDevice()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: Cleaning up ViewModel and cancelling jobs.")
        disconnect()
    }
}
