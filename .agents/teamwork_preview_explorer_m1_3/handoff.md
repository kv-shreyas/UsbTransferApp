# Code Specification & Handoff Report — Milestone 1 Hardware Layer Refactor (`UsbDeviceManager.kt`)

## 1. Observation

### Current Implementation Analysis (`UsbDeviceManager.kt`)
File Path: `/home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`
Total Lines: 108 lines.

Directly observed structure and methods:
1. **Class Initialization & libusb Context** (lines 9–15):
   ```kotlin
   class UsbDeviceManager {
       private val context = Context()
       init {
           LibUsb.init(context)
       }
   ```
2. **Android Vendor ID Whitelist** (lines 29–44):
   `knownAndroidVids` set containing 14 Vendor IDs: `0x18D1` (Google/Android generic), `0x04E8` (Samsung), `0x2717` (Xiaomi), `0x2A70` (OnePlus), `0x22D9` & `0x2B4C` (Oppo/Realme), `0x1EBF` (Vivo), `0x22B8` (Motorola), `0x0FCE` (Sony), `0x1004` (LG), `0x0BB4` (HTC), `0x19D2` (ZTE), `0x05C6` (Qualcomm), `0x0E8D` (MediaTek).
3. **`findAndroidDevice` Method** (lines 46–72):
   ```kotlin
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
               if (requireAccessory) isAoa else (isAndroidVid || isAoa)
           }?.also { LibUsb.refDevice(it) }
       } finally {
           LibUsb.freeDeviceList(list, true)
       }
   }
   ```
   *Observation*: `list.firstOrNull { ... }` causes `findAndroidDevice()` to stop at the first matching device on the host system's USB bus topology. It cannot discover or enumerate multiple connected Android devices.
4. **`isDevicePhysicallyConnected` Method** (lines 74–99):
   ```kotlin
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
   ```
   *Observation*: Does not take a `hardwareId` parameter and returns `Pair(true, nameStr)` on the very first matching device encountered, making it impossible to check if a specific physical device is still connected.
5. **Lifecycle Management Methods** (lines 101–107):
   ```kotlin
   fun releaseDevice(device: Device) {
       LibUsb.unrefDevice(device)
   }
   fun cleanup() {
       LibUsb.exit(context)
   }
   ```

---

## 2. Logic Chain

1. **Defect in Current Implementation**:
   - `UsbDeviceManager.findAndroidDevice()` only yields one device (`list.firstOrNull`). When multiple Android devices are connected, second and subsequent devices are completely ignored.
   - Device identity is untracked; there is no unique key distinguishing Device A from Device B.
2. **Requirements for Hardware Multi-Device Support**:
   - **R1 Hardware Identifier Specification**: Each physical USB device connected to the desktop host must be assigned a stable, invariant key.
   - `LibUsb.getBusNumber(device)` (returns `Int`) and `LibUsb.getPortNumber(device)` (returns `Int`) provide the physical bus controller index and hub port index on the system.
   - The formatted string `"bus_${busNumber}_port_${portNumber}"` (e.g., `"bus_1_port_3"`) uniquely identifies the physical USB port into which a device is plugged.
   - **Invariant Property**: Even when an Android device resets and re-enumerates during an AOA (Android Open Accessory) mode transition (`ACCESSORY_START`), its USB bus address changes, but its physical `busNumber` and `portNumber` remain unchanged!
3. **Refactored Method Specifications**:
   - `DiscoveredUsbDevice` data class: Model encapsulating hardware identity (`id`), physical topology (`busNumber`, `portNumber`), descriptors (`vendorId`, `productId`, `isAoa`), friendly display name (`deviceName`), and `org.usb4java.Device` instance pointer.
   - `discoverDevices(): List<DiscoveredUsbDevice>`: Scans all devices via `LibUsb.getDeviceList()`, filters for `knownAndroidVids` or AOA PIDs, constructs `DiscoveredUsbDevice` for every match, invokes `LibUsb.refDevice()` for each returned item, and safely frees the `DeviceList`.
   - `findDeviceById(hardwareId: String): Device?`: Scans `LibUsb.getDeviceList()`, evaluates `"bus_${bus}_port_${port}"` against target `hardwareId`, calls `LibUsb.refDevice()` on the matching device, and returns it.
   - `isDevicePhysicallyConnected(hardwareId: String): Pair<Boolean, String?>`: Scans `LibUsb.getDeviceList()`, checks whether any connected device matches `hardwareId`, and returns `Pair(true, deviceName)` or `Pair(false, null)`.

---

## 3. Kotlin Code Specifications & Templates

### Model: `DiscoveredUsbDevice`
**Target File**: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`

```kotlin
package com.example.securequicktransferapp.domain.model

import org.usb4java.Device

/**
 * Encapsulates physical discovery details and libusb handle for a connected Android USB device.
 * 
 * @property id Unique physical hardware identifier in the format "bus_{busNumber}_port_{portNumber}" (e.g. "bus_1_port_3").
 * @property busNumber Physical USB bus index reported by libusb.
 * @property portNumber Physical USB port index on the bus/hub reported by libusb.
 * @property vendorId USB Vendor ID (VID) extracted from DeviceDescriptor (0x0000..0xFFFF).
 * @property productId USB Product ID (PID) extracted from DeviceDescriptor (0x0000..0xFFFF).
 * @property isAoa True if the device is currently running in Android Open Accessory (AOA) mode (PID 0x2D00 or 0x2D01).
 * @property deviceName Human-readable device description string formatted with VID, PID, and mode.
 * @property device Reference-counted org.usb4java.Device instance handle.
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

---

### Class: `UsbDeviceManager` Refactored Specification
**Target File**: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`

```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceList
import org.usb4java.LibUsb

/**
 * Hardware Abstraction Layer for enumerating, identifying, and managing physical USB connections
 * to multiple Android devices via libusb (usb4java).
 */
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
     * Helper to compute an invariant physical hardware key for a given USB device.
     * Combines physical bus number and hub port number (e.g., "bus_1_port_3").
     */
    private fun getHardwareId(device: Device): String {
        val bus = LibUsb.getBusNumber(device) and 0xFF
        val port = LibUsb.getPortNumber(device) and 0xFF
        return "bus_${bus}_port_${port}"
    }

    /**
     * Enumerates ALL physically connected Android devices matching known VIDs or AOA PIDs.
     * Each returned [DiscoveredUsbDevice] contains an incremented reference count on [Device].
     * Caller or session owner is responsible for releasing the device handle via [releaseDevice].
     * 
     * @return List of all currently discovered Android USB devices.
     */
    fun discoverDevices(): List<DiscoveredUsbDevice> {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)

        if (result < 0) return emptyList()

        val discoveredList = mutableListOf<DiscoveredUsbDevice>()

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == 0) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF

                    val isAndroidVid = vid in knownAndroidVids
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (isAndroidVid || isAoa) {
                        val busNumber = LibUsb.getBusNumber(device) and 0xFF
                        val portNumber = LibUsb.getPortNumber(device) and 0xFF
                        val hardwareId = "bus_${busNumber}_port_${portNumber}"
                        val modeStr = if (isAoa) "[ACCESSORY/AOA]" else "[NORMAL/MTP]"
                        val deviceName = "Device (VID: ${String.format("%04X", vid)}, PID: ${String.format("%04X", pid)}) $modeStr"

                        // Retain device handle reference count so it remains valid outside freeDeviceList
                        LibUsb.refDevice(device)

                        discoveredList.add(
                            DiscoveredUsbDevice(
                                id = hardwareId,
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
            return discoveredList
        } finally {
            LibUsb.freeDeviceList(list, true)
        }
    }

    /**
     * Finds a specific physical [Device] matching the given [hardwareId] (e.g. "bus_1_port_3").
     * If found, increments reference count on the device and returns it. Caller must release via [releaseDevice].
     * 
     * @param hardwareId The physical hardware identifier string to look up.
     * @return Retained [Device] instance, or null if not found.
     */
    fun findDeviceById(hardwareId: String): Device? {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)
        if (result < 0) return null

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == 0) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF

                    val isAndroidVid = vid in knownAndroidVids
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (isAndroidVid || isAoa) {
                        val id = getHardwareId(device)
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
     * Checks whether a specific physical device matching [hardwareId] is currently connected.
     * 
     * @param hardwareId The physical hardware identifier string (e.g. "bus_1_port_3").
     * @return Pair where first element is true if connected, and second element is friendly device name or null.
     */
    fun isDevicePhysicallyConnected(hardwareId: String): Pair<Boolean, String?> {
        val list = DeviceList()
        val result = LibUsb.getDeviceList(context, list)
        if (result < 0) return Pair(false, null)

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == 0) {
                    val vid = desc.idVendor().toInt() and 0xFFFF
                    val pid = desc.idProduct().toInt() and 0xFFFF

                    val isAndroidVid = vid in knownAndroidVids
                    val isAoa = pid == 0x2D00 || pid == 0x2D01

                    if (isAndroidVid || isAoa) {
                        val id = getHardwareId(device)
                        if (id == hardwareId) {
                            val modeStr = if (isAoa) "[ACCESSORY/AOA]" else "[NORMAL/MTP]"
                            val nameStr = "Device (VID: ${String.format("%04X", vid)}, PID: ${String.format("%04X", pid)}) $modeStr"
                            return Pair(true, nameStr)
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
     * Decrements reference count on a previously acquired [Device].
     */
    fun releaseDevice(device: Device) {
        LibUsb.unrefDevice(device)
    }

    /**
     * Cleans up libusb context on application teardown.
     */
    fun cleanup() {
        LibUsb.exit(context)
    }
}
```

---

## 4. Caveats

1. **Libusb Reference Counting**: Calling `discoverDevices()` or `findDeviceById()` calls `LibUsb.refDevice()`. The caller (e.g., `UsbSessionManager` or session lifecycle handler) must call `releaseDevice(device)` when closing or relinquishing a session to prevent native memory leaks.
2. **Byte Bitmasking (`and 0xFF`)**: `LibUsb.getBusNumber()` and `LibUsb.getPortNumber()` return Java `Int` or `Byte` types. Masking with `and 0xFF` ensures signed byte values (e.g., `-1` representing unsigned `0xFF`) are correctly interpreted as non-negative integers (`0..255`).
3. **Multi-Hub Topologies**: For USB setups with daisy-chained external USB hubs, `portNumber` represents the downstream port index on the parent hub. `busNumber` + `portNumber` is unique per root hub port tree.

---

## 5. Conclusion

- The code specifications provided above completely solve Requirement R1 by transitioning `UsbDeviceManager` from single-device matching (`list.firstOrNull`) to multi-device enumeration (`discoverDevices()`).
- Each connected device is assigned a deterministic, physical hardware identifier (`bus_{busNumber}_port_{portNumber}`).
- `findDeviceById(hardwareId)` and `isDevicePhysicallyConnected(hardwareId)` enable targeted querying and lifecycle tracking of individual physical USB connections.

---

## 6. Verification Method

1. **File Inspection**:
   - Confirm `DiscoveredUsbDevice` data class definition matches contracts in `PROJECT.md` §Interface Contracts.
   - Confirm `UsbDeviceManager.kt` contains `discoverDevices()`, `findDeviceById(hardwareId)`, and `isDevicePhysicallyConnected(hardwareId)`.
2. **Compilation & Unit Testing**:
   - Run Gradle build task `./gradlew compileKotlinJvm` or `./gradlew desktopTest` to verify that `UsbDeviceManager` compiles without syntax or type errors.
3. **Invalidation Conditions**:
   - If `LibUsb.getBusNumber(device)` or `LibUsb.getPortNumber(device)` return non-deterministic or identical values for devices on different USB ports, alternative keys (such as `getPortNumbers` path array) would need to be incorporated.
