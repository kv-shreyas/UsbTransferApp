package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceList
import org.usb4java.LibUsb
import java.nio.ByteBuffer

open class UsbDeviceManager {

    private val context = Context()
    private val usbLock = Any()

    init {
        LibUsb.init(context)
    }

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

    /**
     * Constructs a stable, physical hardware identifier for a device based on bus and port numbers.
     * Uses LibUsb.getBusNumber, LibUsb.getPortNumber, and LibUsb.getPortNumbers for hub hierarchy.
     */
    private fun getHardwareIdentifier(device: Device): Triple<String, Int, Int> {
        val busNumber = LibUsb.getBusNumber(device).toInt() and 0xFF
        val portNumber = LibUsb.getPortNumber(device).toInt() and 0xFF

        val pathBuffer = ByteBuffer.allocateDirect(7)
        val numPorts = LibUsb.getPortNumbers(device, pathBuffer)
        val portPathStr = if (numPorts > 0) {
            val ports = ByteArray(numPorts)
            pathBuffer.get(ports)
            ports.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            portNumber.toString()
        }

        val id = "bus_${busNumber}_port_${portPathStr}"
        return Triple(id, busNumber, portNumber)
    }

    /**
     * Enumerates all physical USB devices connected to the host and returns all matching Android devices.
     * Each returned [DiscoveredUsbDevice] has its native Device reference count incremented (+1).
     * Callers must invoke [releaseDevice] on device handles when finished.
     */
    open fun discoverDevices(): List<DiscoveredUsbDevice> = synchronized(usbLock) {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)
        if (result < 0) {
            println("[UsbDeviceManager] LibUsb.getDeviceList error: $result (${LibUsb.strError(result)})")
            return emptyList()
        }

        val discovered = mutableListOf<DiscoveredUsbDevice>()
        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == LibUsb.SUCCESS) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF

                    val isAndroidVid = vid in knownAndroidVids
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (isAndroidVid || isAoa) {
                        val (id, busNumber, portNumber) = getHardwareIdentifier(device)
                        val modeStr = if (isAoa) "[ACCESSORY/AOA]" else "[NORMAL/MTP]"
                        val deviceName = "Android Device ($id, VID:${String.format("%04X", vid)}, PID:${String.format("%04X", pid)}) $modeStr"

                        // Retain reference (+1) for returned device handle
                        LibUsb.refDevice(device)
                        discovered.add(
                            DiscoveredUsbDevice(
                                id = id,
                                busNumber = busNumber,
                                portNumber = portNumber,
                                vendorId = vid,
                                productId = pid,
                                isAoa = isAoa,
                                deviceName = deviceName,
                                device = device
                            )
                        )
                    }
                }
            }
            return discovered
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    /**
     * Searches for a specific connected device matching [hardwareId] (e.g. "bus_1_port_3").
     * Returns a retained Device pointer (+1 ref count) or null if not found.
     */
    open fun findDeviceById(hardwareId: String): Device? = synchronized(usbLock) {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)
        if (result < 0) return null

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == LibUsb.SUCCESS) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF

                    val isAndroidVid = vid in knownAndroidVids
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (isAndroidVid || isAoa) {
                        val (id, _, _) = getHardwareIdentifier(device)
                        if (id == hardwareId) {
                            LibUsb.refDevice(device)
                            return device
                        }
                    }
                }
            }
            return null
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    /**
     * Checks whether a specific device matching [hardwareId] is currently physically connected.
     * Returns a Pair of Boolean (connected) and String (friendly device name or null).
     */
    open fun isDevicePhysicallyConnected(hardwareId: String): Pair<Boolean, String?> = synchronized(usbLock) {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)
        if (result < 0) return Pair(false, null)

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == LibUsb.SUCCESS) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF

                    val isAndroidVid = vid in knownAndroidVids
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (isAndroidVid || isAoa) {
                        val (id, _, _) = getHardwareIdentifier(device)
                        if (id == hardwareId) {
                            val modeStr = if (isAoa) "[ACCESSORY/AOA]" else "[NORMAL/MTP]"
                            val deviceName = "Android Device ($id, VID:${String.format("%04X", vid)}, PID:${String.format("%04X", pid)}) $modeStr"
                            return Pair(true, deviceName)
                        }
                    }
                }
            }
            return Pair(false, null)
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    /**
     * Legacy helper returning the first discovered Android device.
     * Delegates to [discoverDevices] and releases unselected device references.
     */
    open fun findAndroidDevice(requireAccessory: Boolean = false): Device? {
        val devices = discoverDevices()
        try {
            val target = devices.firstOrNull { if (requireAccessory) it.isAoa else true }
            if (target != null) {
                LibUsb.refDevice(target.device)
                return target.device
            }
            return null
        } finally {
            devices.forEach { releaseDevice(it.device) }
        }
    }

    /**
     * Legacy status check returning whether any Android device is physically attached.
     * Delegates to [discoverDevices] and releases device references.
     */
    open fun isDevicePhysicallyConnected(): Pair<Boolean, String?> {
        val devices = discoverDevices()
        try {
            if (devices.isNotEmpty()) {
                val first = devices.first()
                return Pair(true, first.deviceName)
            }
            return Pair(false, null)
        } finally {
            devices.forEach { releaseDevice(it.device) }
        }
    }

    /**
     * Releases a retained device reference count (-1).
     */
    open fun releaseDevice(device: Device) {
        LibUsb.unrefDevice(device)
    }

    /**
     * Deinitializes the libusb context on shutdown.
     */
    open fun cleanup() {
        LibUsb.exit(context)
    }
}