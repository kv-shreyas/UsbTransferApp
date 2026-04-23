package com.example.usbtransferapp.data

sealed class UsbUiState {
    object Idle : UsbUiState()
    object NoDevice : UsbUiState()
    data class DeviceDetected(val name: String) : UsbUiState()
    object RequestingPermission : UsbUiState()
    object Connecting : UsbUiState()
    object Transferring : UsbUiState()
    data class Success(val message: String) : UsbUiState()
    data class Error(val error: String) : UsbUiState()
}