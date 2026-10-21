package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.data.driver.DriverInstallManager
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceHandle
import org.usb4java.DeviceList
import org.usb4java.LibUsb
import java.nio.ByteBuffer

/**
 * Enumerates physical USB devices via libusb and exposes them as [DiscoveredUsbDevice] domain objects.
 *
 * On **Windows**, libusb requires a WinUSB-compatible kernel driver to claim USB interfaces.
 * If a known Android VID is detected but libusb cannot open the device (ERROR_ACCESS / ERROR_NOT_SUPPORTED),
 * [driverInstallManager] is notified so the UI can prompt the user to install the correct driver via Zadig.
 *
 * @param driverInstallManager Optional [DriverInstallManager] — when provided, driver-less device
 *   detection is active. Pass `null` to disable automatic driver prompts (e.g. in tests).
 */
open class UsbDeviceManager(
    val driverInstallManager: DriverInstallManager? = null
) {

    private val context = Context()
    private val usbLock = Any()
    private var isInitialized = false
    private val probedDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
     *
     * On Windows, this method also performs a lightweight driver-accessibility probe for non-AOA devices.
     * If the probe detects a missing WinUSB driver, [driverInstallManager] is notified.
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
                    val isAoa = pid in 0x2D00..0x2D05

                    if (isAndroidVid || isAoa) {
                        val (id, busNumber, portNumber) = getHardwareIdentifier(device)
                        val modeStr = if (isAoa) "[ACCESSORY/AOA]" else "[NORMAL/MTP]"
                        val rawName = "Android Device ($id, VID:${String.format("%04X", vid)}, PID:${String.format("%04X", pid)}) $modeStr"
                        val deviceName = rawName
                            .replace("Android Device (", "")
                            .replace(")", "")
                            .substringBefore(",")
                            .trim()

                        // --- Windows driver detection probe ---
                        // On Windows, libusb requires a WinUSB-compatible kernel driver to open a device.
                        // We do a lightweight open test: if it fails with ACCESS / NOT_SUPPORTED, the
                        // device likely has no WinUSB driver installed and we signal the install manager.
                        // Check if a WinUSB-compatible driver is present on Windows (strictly for AOA mode)
                        if (driverInstallManager != null && isAoa) {
                            probeDeviceAccessibility(device, vid, pid, deviceName)
                        }

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
     * Attempts a non-destructive open of [device] to detect whether a WinUSB-compatible driver
     * is installed on Windows. If the open fails with an access/IO error, [driverInstallManager]
     * is notified via [DriverInstallManager.onDeviceNeedsDriver].
     *
     * The temporary handle is closed immediately after the test — this is a probe only.
     */
    private fun probeDeviceAccessibility(device: Device, vid: Int, pid: Int, deviceName: String) {
        val key = "VID_${String.format("%04X", vid)}_PID_${String.format("%04X", pid)}"
        if (!probedDevices.add(key)) return

        // 1. If Windows Registry already confirms a WinUSB / libusb driver is installed, skip probe entirely
        if (com.example.securequicktransferapp.data.driver.ZadigDriverInstaller.isDriverAlreadyInstalled(vid, pid)) {
            println("[UsbDeviceManager] Driver already installed for $key — skipping accessibility probe.")
            return
        }

        val tempHandle = DeviceHandle()
        val openResult = LibUsb.open(device, tempHandle)
        if (openResult == LibUsb.ERROR_ACCESS) {
            // ERROR_ACCESS (-3) on Windows means the device is currently opened/claimed by an active connection.
            // A driver is already present and active; do not treat this as a missing driver!
            println("[UsbDeviceManager] Device $key is currently in-use / claimed (code=-3). Driver is present.")
            return
        }
        when (openResult) {
            LibUsb.SUCCESS -> {
                // Device is accessible — WinUSB (or compatible) driver is present. No action needed.
                LibUsb.close(tempHandle)
                println("[UsbDeviceManager] Driver probe OK for VID:${String.format("%04X", vid)} PID:${String.format("%04X", pid)}")
            }
            LibUsb.ERROR_NOT_SUPPORTED -> {
                // On Windows: ERROR_NOT_SUPPORTED typically means no WinUSB driver is installed.
                // ERROR_ACCESS means the driver exists but we don't have permissions (less common on Desktop).
                println("[UsbDeviceManager] Driver probe FAILED (code=$openResult) for VID:${String.format("%04X", vid)} PID:${String.format("%04X", pid)} — notifying DriverInstallManager")
                driverInstallManager?.onDeviceNeedsDriver(vid, pid, deviceName)
            }
            else -> {
                // Transient state (e.g. device temporarily busy) — remove key so it can be re-evaluated later
                probedDevices.remove(key)
                println("[UsbDeviceManager] Driver probe returned $openResult for VID:${String.format("%04X", vid)} — ignoring")
            }
        }
    }

    /**
     * Reports that a device encountered a driver/permission issue during connection attempts,
     * allowing the DriverInstallManager to prompt the user to install the WinUSB driver.
     */
    open fun reportDriverIssue(vendorId: Int, productId: Int, deviceName: String = "Android Device") {
        if (com.example.securequicktransferapp.data.driver.ZadigDriverInstaller.isDriverAlreadyInstalled(vendorId, productId)) {
            println("[UsbDeviceManager] reportDriverIssue ignored: driver already installed for VID:${String.format("%04X", vendorId)} PID:${String.format("%04X", productId)}")
            return
        }
        driverInstallManager?.onDeviceNeedsDriver(vendorId, productId, deviceName)
    }

    /**
     * Returns true if WinUSB driver installation is actively underway.
     */
    open fun isDriverInstalling(): Boolean {
        return driverInstallManager?.driverInstallState?.value is com.example.usbdriverinstaller.api.DriverInstallState.Installing
    }

    /**
     * Suspends until the active driver installation completes or times out.
     */
    open suspend fun waitForDriverInstall(timeoutMs: Long = 45000): Boolean {
        return driverInstallManager?.waitForInstall(timeoutMs) ?: true
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
                    val isAoa = pid in 0x2D00..0x2D05

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
                    val isAoa = pid in 0x2D00..0x2D05

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
