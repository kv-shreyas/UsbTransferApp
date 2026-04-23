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
        Log.d(TAG, "Detecting devices and accessories...")
        val devices = usbManagerWrapper.getDevices()
        val accessories = usbManagerWrapper.getAccessories()
        
        val device = devices.firstOrNull()
        val accessory = accessories?.firstOrNull()

        if (device != null) {
            currentDevice = device
            currentAccessory = null
            _uiState.value = UsbUiState.DeviceDetected(name = device.productName ?: "USB Device")
        } else if (accessory != null) {
            currentAccessory = accessory
            currentDevice = null
            _uiState.value = UsbUiState.DeviceDetected(name = accessory.model ?: "USB Accessory")
        } else {
            _uiState.value = UsbUiState.NoDevice
        }
    }

    fun requestPermissionAndConnect() {
        viewModelScope.launch {
            val device = currentDevice
            val accessory = currentAccessory

            if (device != null) {
                if (!usbManagerWrapper.hasPermission(device)) {
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(device)
                    UsbPermissionBus.flow.collect { event ->
                        if (event is UsbPermissionEvent.DeviceGranted && event.device.deviceId == device.deviceId) {
                            proceedWithDevice(event.device)
                            cancel()
                        }
                    }
                } else {
                    proceedWithDevice(device)
                }
            } else if (accessory != null) {
                if (!usbManagerWrapper.hasPermission(accessory)) {
                    _uiState.value = UsbUiState.RequestingPermission
                    usbManagerWrapper.requestPermission(accessory)
                    UsbPermissionBus.flow.collect { event ->
                        if (event is UsbPermissionEvent.AccessoryGranted && event.accessory == accessory) {
                            proceedWithAccessory(event.accessory)
                            cancel()
                        }
                    }
                } else {
                    proceedWithAccessory(accessory)
                }
            }
        }
    }

    private suspend fun proceedWithDevice(device: UsbDevice) {
        _uiState.value = UsbUiState.Connecting
        if (hostManager.connect(device)) {
            delegatingConnection.setDelegate(hostManager)
            startHandshakeAndListen()
        } else {
            _uiState.value = UsbUiState.Error("Host connection failed")
        }
    }

    private suspend fun proceedWithAccessory(accessory: UsbAccessory) {
        _uiState.value = UsbUiState.Connecting
        if (aoaManager.connect(accessory)) {
            delegatingConnection.setDelegate(aoaManager)
            startHandshakeAndListen()
        } else {
            _uiState.value = UsbUiState.Error("AOA connection failed")
        }
    }

    private suspend fun startHandshakeAndListen() {
        _uiState.value = UsbUiState.Transferring
        if (dataSource.performHandshake()) {
            _uiState.value = UsbUiState.Success("Secure Channel Established")
            
            commandJob?.cancel()
            commandJob = viewModelScope.launch {
                commandProcessor.startListening()
                _uiState.value = UsbUiState.Idle
            }
        } else {
            _uiState.value = UsbUiState.Error("Handshake failed")
        }
    }

    override fun onCleared() {
        super.onCleared()
        commandJob?.cancel()
    }
}
