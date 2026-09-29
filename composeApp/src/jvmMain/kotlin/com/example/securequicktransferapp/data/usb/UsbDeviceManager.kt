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
    private var isInitialized = false

    init {
        try {
            val result = LibUsb.init(context)
            if (result == LibUsb.SUCCESS) {
                isInitialized = true
            } else {
                println("[UsbDeviceManager] LibUsb.init returned error code: $result")
            }
        } catch (t: Throwable) {
            println("[UsbDeviceManager] Warning: Failed to initialize LibUsb native library: ${t.message}")
            isInitialized = false
        }
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
        0x0E8D, // MediaTek
        0x12D1, // Huawei
        0x0B05, // Asus
        0x17EF, // Lenovo
        0x0489, // Foxconn / Acer
        0x10A9, // Pantech
        0x0408, // Smartisan
        0x29D1, // Transsion (Tecno / Infinix / Itel)
        0x3170, // Nothing
        0x1949, // Lab126 / Amazon
        0x2207, // Rockchip
        0x1F3A, // Allwinner
        0x0502, // Acer
        0x0414, // GigaByte
        0x2080, // Nook
        0x0930, // Toshiba
        0x0482, // Kyocera
        0x1705  // Generic MTK
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
        if (!isInitialized) return emptyList()
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
                        val rawName = "Android Device ($id, VID:${String.format("%04X", vid)}, PID:${String.format("%04X", pid)}) $modeStr"
                        val deviceName = rawName
                            .replace("Android Device (", "")
                            .replace(")", "")
                            .substringBefore(",")
                            .trim()

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
    open fun findDeviceById(hardwareId: String, requireAccessory: Boolean = false): Device? = synchronized(usbLock) {
        if (!isInitialized) return null
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
                        if (requireAccessory && !isAoa) continue
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
        if (!isInitialized) return Pair(false, null)
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
                            val rawName = "Android Device ($id, VID:${String.format("%04X", vid)}, PID:${String.format("%04X", pid)}) $modeStr"
                            val deviceName = rawName
                                .replace("Android Device (", "")
                                .replace(")", "")
                                .substringBefore(",")
                                .trim()
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
        if (isInitialized) {
            try {
                LibUsb.unrefDevice(device)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Deinitializes the libusb context on shutdown.
     */
    open fun cleanup() {
        if (isInitialized) {
            try {
                LibUsb.exit(context)
            } catch (_: Throwable) {}
        }
    }
}