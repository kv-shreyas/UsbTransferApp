# Handoff Report — Milestone 1: Hardware Layer Refactor (`UsbDeviceManager.kt`)

## 1. Observation
From direct inspection of the codebase:
- **`PROJECT.md` Interface Contract**:
  - `DiscoveredUsbDevice` model path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`
  - Required fields: `id: String` (e.g., `"bus_1_port_3"`), `busNumber: Int`, `portNumber: Int`, `vendorId: Int`, `productId: Int`, `isAoa: Boolean`, `deviceName: String`, `device: Device`.
  - Required methods on `UsbDeviceManager`: `discoverDevices(): List<DiscoveredUsbDevice>` and `findDeviceById(id: String): Device?`.
- **Existing `UsbDeviceManager.kt`** (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`, lines 46-72):
  - `findAndroidDevice()` returns only the *first* matching `Device` and does not track physical bus/port topology or multiple concurrent devices.
  - `knownAndroidVids` (lines 29-44) contains VIDs for major Android OEMs (`0x18D1`, `0x04E8`, `0x2717`, `0x2A70`, `0x22D9`, `0x2B4C`, `0x1EBF`, `0x22B8`, `0x0FCE`, `0x1004`, `0x0BB4`, `0x19D2`, `0x05C6`, `0x0E8D`).
  - AOA PIDs are identified by `0x2D00` (Accessory mode) and `0x2D01` (Accessory + ADB mode).
- **LibUsb Memory Management** (`usb4java`):
  - `LibUsb.getDeviceList(context, list)` populates `DeviceList` and increments reference count (+1) for all devices in the list.
  - Calling `LibUsb.freeDeviceList(list, unref = true)` decrements reference count (-1) for all devices in the list.
  - To retain a `Device` beyond the discovery invocation, `LibUsb.refDevice(device)` MUST be called prior to `LibUsb.freeDeviceList(list, true)`.
  - Releasing a device handle requires calling `LibUsb.unrefDevice(device)`.

---

## 2. Logic Chain

1. **Physical Hardware Key Generation**:
   - `LibUsb.getBusNumber(device)` returns an integer (1..255) representing the physical USB bus.
   - `LibUsb.getPortNumber(device)` returns an integer (1..255) representing the physical port on the bus or parent hub.
   - Using `LibUsb.getPortNumbers(device, buffer)` retrieves the full port hierarchy path (e.g. `[1, 3]`), allowing support for nested USB hubs.
   - Formatting this into `"bus_${busNumber}_port_${portPathStr}"` (e.g. `"bus_1_port_3"` or `"bus_1_port_1.3"`) guarantees an invariant, physical hardware key that persists across connection mode switches (MTP ↔ AOA).

2. **Multi-Device Discovery (`discoverDevices()`)**:
   - Call `LibUsb.getDeviceList(context, list)`.
   - Iterate over `list`, fetching `DeviceDescriptor` via `LibUsb.getDeviceDescriptor(device, desc)`.
   - Filter for `isAndroidVid` (`vid in knownAndroidVids`) or `isAoa` (`pid == 0x2D00 || pid == 0x2D01`).
   - For every matching device:
     - Derive physical hardware key `id` using `getBusNumber`, `getPortNumber`, and `getPortNumbers`.
     - Increment device reference count: `LibUsb.refDevice(device)`.
     - Instantiate and add `DiscoveredUsbDevice` to the result list.
   - In `finally` block, call `LibUsb.freeDeviceList(list, true)`. The matching devices remain alive because their reference count went from +2 to +1.

3. **Targeted Device Finding (`findDeviceById(hardwareId: String)`)**:
   - Call `LibUsb.getDeviceList(context, list)`.
   - Iterate over `list`, matching the computed `id` against `hardwareId`.
   - When a match is found:
     - Increment reference count: `LibUsb.refDevice(device)`.
     - Return the `Device` pointer.
   - In `finally` block, call `LibUsb.freeDeviceList(list, true)`.

4. **Reference Balance & Leak Prevention**:
   - Every caller receiving a `Device` from `discoverDevices()` or `findDeviceById()` assumes ownership of one reference (+1).
   - Calling `releaseDevice(device)` (which invokes `LibUsb.unrefDevice(device)`) balances the reference count back to 0, permitting native memory cleanup by libusb.
   - Existing single-device helper methods (`findAndroidDevice` and `isDevicePhysicallyConnected`) are refactored to delegate to `discoverDevices()`, ensuring proper cleanup of unselected devices.

---

## 3. Caveats
- **Root Hub Devices**: Root hub devices report port number 0. Connected Android phones on real ports will report port numbers >= 1.
- **Port Path Format**: `getPortNumbers` returns an array of port numbers down the hub tree. If depth = 1, `"bus_1_port_3"` is produced; if depth > 1 (e.g., through external hubs), `"bus_1_port_1.3"` is produced, preventing ID collision between devices on the same external hub.
- **Ownership Contract**: Callers of `discoverDevices()` must ensure that `releaseDevice(device)` or `LibUsb.unrefDevice(device)` is invoked on each `DiscoveredUsbDevice.device` when the list/session is discarded.

---

## 4. Conclusion & Proposed Code Implementation

### A. Domain Model (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`)

```kotlin
package com.example.securequicktransferapp.domain.model

import org.usb4java.Device

/**
 * Domain model representing a physically discovered USB device on the host system.
 *
 * @property id Unique physical hardware identifier in format "bus_{busNumber}_port_{portNumber}" (e.g. "bus_1_port_3").
 * @property busNumber The USB bus number (1..255).
 * @property portNumber The USB port number on the hub/bus (1..255).
 * @property vendorId The 16-bit USB Vendor ID (VID).
 * @property productId The 16-bit USB Product ID (PID).
 * @property isAoa True if the device is currently in Android Open Accessory (AOA) mode (PID 0x2D00 or 0x2D01).
 * @property deviceName Human-readable display name summarizing device identity and connection mode.
 * @property device The underlying usb4java Device handle. Holds a retained reference (+1 ref count).
 */
data class DiscoveredUsbDevice(
    val id: String,
    val busNumber: Int,
    val portNumber: Int,
    val vendorId: Int,
    val productId: Int,
    val isAoa: Boolean,
    val deviceName: String,
    val device: Device
)
```

### B. Hardware Layer Manager (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`)

```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceList
import org.usb4java.LibUsb
import java.nio.ByteBuffer

class UsbDeviceManager {

    private val context = Context()

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

        // Retrieve full port numbers chain to support multi-level USB hub hierarchies
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
    fun discoverDevices(): List<DiscoveredUsbDevice> {
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
            // Free list and decrement reference count (-1) for all items in the list.
            // Items added to `discovered` had refDevice called, so their count drops from 2 to 1 and stay alive.
            LibUsb.freeDeviceList(list, true)
        }
    }

    /**
     * Searches for a specific connected device matching [hardwareId] (e.g. "bus_1_port_3").
     * Returns a retained Device pointer (+1 ref count) or null if not found.
     */
    fun findDeviceById(hardwareId: String): Device? {
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
     * Backward-compatible helper returning the first discovered Android device.
     */
    fun findAndroidDevice(requireAccessory: Boolean = false): Device? {
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
     * Backward-compatible status check returning whether any Android device is physically attached.
     */
    fun isDevicePhysicallyConnected(): Pair<Boolean, String?> {
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
    fun releaseDevice(device: Device) {
        LibUsb.unrefDevice(device)
    }

    /**
     * Deinitializes the libusb context on shutdown.
     */
    fun cleanup() {
        LibUsb.exit(context)
    }
}
```

---

## 5. Verification Method

1. **File Location Verification**:
   - Inspect that `DiscoveredUsbDevice.kt` is located at `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`.
   - Inspect that `UsbDeviceManager.kt` is located at `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`.

2. **Compilation Verification**:
   - When implementer creates `DiscoveredUsbDevice.kt` and updates `UsbDeviceManager.kt`, run `./gradlew compileKotlinJvm` or `./gradlew build` to confirm zero compilation errors.

3. **Unit / Integration Verification**:
   - Call `UsbDeviceManager.discoverDevices()` with 0, 1, and 2 connected Android devices.
   - Verify each device in the returned list has a distinct `id` formatted as `"bus_X_port_Y"`.
   - Verify `findDeviceById("bus_X_port_Y")` returns the matching `Device` handle.
   - Confirm that calling `releaseDevice(device)` does not crash or throw JNI errors.
