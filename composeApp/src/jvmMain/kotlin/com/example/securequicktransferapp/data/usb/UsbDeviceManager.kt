package com.example.securequicktransferapp.data.usb

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

    private val knownAndroidVids = setOf(
        0x18D1, // Google / Android generic
        0x04E8, // Samsung
        0x2717, // Xiaomi
        0x2A70, // OnePlus
        0x22D9, // Oppo / Realme
        0x2B4C, // Realme
        0x1EBF, // Vivo
        0x22B8, // Motorola
        0x0FCE, // Sony
        0x1004, // LG
        0x0BB4, // HTC
        0x19D2, // ZTE
        0x05C6, // Qualcomm
        0x0E8D  // MediaTek
    )

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
                
                val isAndroidVid = vid in knownAndroidVids
                val isAoa = pid == 0x2D00 || pid == 0x2D01
                
                if (isAndroidVid || isAoa) {
                    println("[UsbDeviceManager] Found device: VID=${String.format("%04X", vid)}, PID=${String.format("%04X", pid)} ${if (isAoa) "[ACCESSORY MODE]" else "[NORMAL MODE]"}")
                }

                if (requireAccessory) isAoa else (isAndroidVid || isAoa)
            }?.also { LibUsb.refDevice(it) }
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    fun isDevicePhysicallyConnected(): Pair<Boolean, String?> {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)
        if (result < 0) return Pair(false, null)

        try {

            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == 0) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (vid in knownAndroidVids || isAoa) {
                        val modeStr = if (isAoa) "[ACCESSORY/AOA]" else "[NORMAL/MTP]"
                        val nameStr = "Device (VID: ${String.format("%04X", vid)}, PID: ${String.format("%04X", pid)}) $modeStr"
                        return Pair(true, nameStr)
                    }
                }
            }
            return Pair(false, null)
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