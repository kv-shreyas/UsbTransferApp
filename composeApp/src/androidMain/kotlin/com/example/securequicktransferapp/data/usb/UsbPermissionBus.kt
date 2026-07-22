package com.example.securequicktransferapp.data.usb

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class UsbPermissionEvent {
    data class DeviceGranted(val device: UsbDevice) : UsbPermissionEvent()
    data class AccessoryGranted(val accessory: UsbAccessory) : UsbPermissionEvent()
    data class DeviceDenied(val device: UsbDevice) : UsbPermissionEvent()
    data class AccessoryDenied(val accessory: UsbAccessory) : UsbPermissionEvent()
}

object UsbPermissionBus {
    private val _flow = MutableSharedFlow<UsbPermissionEvent>(replay = 1, extraBufferCapacity = 10)
    val flow = _flow.asSharedFlow()

    suspend fun emit(event: UsbPermissionEvent) {
        _flow.emit(event)
    }
}
