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

 /*   fun findAndroidDevice(): Device? {
        val list = DeviceList()
        LibUsb.getDeviceList(context, list)

        return list.firstOrNull { device ->
            val desc = DeviceDescriptor()
            LibUsb.getDeviceDescriptor(device, desc)

            desc.idVendor().toInt() == 0x18D1 // Google VID
        }
    }*/

    fun findAndroidDevice(requireAccessory: Boolean = false): Device? {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)

        if (result < 0) return null

        try {
            return list.firstOrNull { device ->
                val desc = DeviceDescriptor()
                LibUsb.getDeviceDescriptor(device, desc)

                val vid = desc.idVendor().toInt() and 0xFFFF
                val pid = desc.idProduct().toInt() and 0xFFFF
                
                val isGoogle = vid == 0x18D1
                val isAoa = pid == 0x2D00 || pid == 0x2D01
                
                if (isGoogle) {
                    println("[UsbDeviceManager] Found device: VID=${String.format("%04x", vid)}, PID=${String.format("%04x", pid)} ${if (isAoa) "[ACCESSORY MODE]" else "[NORMAL MODE]"}")
                }

                if (requireAccessory) isGoogle && isAoa else isGoogle
            }?.also { LibUsb.refDevice(it) }
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    fun releaseDevice(device: Device) {
        LibUsb.unrefDevice(device)
    }

    fun cleanup() {
        LibUsb.exit(context)
    }
}