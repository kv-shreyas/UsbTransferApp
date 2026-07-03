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

    private var currentDevice: UsbDevice? = null
    private var currentAccessory: UsbAccessory? = null
    private var commandJob: Job? = null
    private val TAG = "UsbTransferVM"

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

    fun requestPermissionAndConnect() {
        viewModelScope.launch {
            // Ensure any previous stale connection is closed before retrying
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
            Log.e(TAG, "proceedWithDevice: Host connection failed.")
            _uiState.value = UsbUiState.Error("Host connection failed")
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
            
            commandJob?.cancel()
            commandJob = viewModelScope.launch {
                Log.d(TAG, "startHandshakeAndListen: Starting command listener...")
                delegatingConnection.clearBuffer()
                commandProcessor.startListening()
                _uiState.value = UsbUiState.Idle
                Log.i(TAG, "startHandshakeAndListen: Command listener terminated. Resetting connection.")
                disconnect()
            }
        } else {
            Log.e(TAG, "startHandshakeAndListen: Handshake FAILED.")
            _uiState.value = UsbUiState.Error("Handshake failed")
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect: Resetting connection and state")
        commandJob?.cancel()
        commandJob = null
        
        delegatingConnection.disconnect()
        aoaManager.disconnect()
        hostManager.disconnect()
        
        currentDevice = null
        currentAccessory = null
        _uiState.value = UsbUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: Cleaning up ViewModel and cancelling jobs.")
        disconnect()
    }
}
