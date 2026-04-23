package com.example.usbtransferapp.data.usb

import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceList
import org.usb4java.LibUsb

class UsbDeviceManager {

    private val context = Context()

    init {
        LibUsb.init(context)
    }

    fun findAndroidDevice(): Device? {
        val list = DeviceList()
        LibUsb.getDeviceList(context, list)

        return list.firstOrNull { device ->
            val desc = DeviceDescriptor()
            LibUsb.getDeviceDescriptor(device, desc)

            desc.idVendor().toInt() == 0x18D1 // Google VID
        }
    }

    fun cleanup() {
        LibUsb.exit(context)
    }
}