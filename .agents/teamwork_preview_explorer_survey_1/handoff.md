# Hardware Layer Analysis Handoff Report

## 1. Observation

### Codebase Inspection Findings
- **`UsbDeviceManager.kt`** (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`):
  - Uses `org.usb4java` (`libusb-1.0` native wrapper) with a single global `Context` initialized in `init { LibUsb.init(context) }` (line 14).
  - Maintains `knownAndroidVids` set (lines 29–44) containing 14 Vendor IDs: Google (`0x18D1`), Samsung (`0x04E8`), Xiaomi (`0x2717`), OnePlus (`0x2A70`), Oppo/Realme (`0x22D9`, `0x2B4C`), Vivo (`0x1EBF`), Motorola (`0x22B8`), Sony (`0x0FCE`), LG (`0x1004`), HTC (`0x0BB4`), ZTE (`0x19D2`), Qualcomm (`0x05C6`), MediaTek (`0x0E8D`).
  - Method `findAndroidDevice(requireAccessory: Boolean = false): Device?` (lines 46–72):
    ```kotlin
    val list = DeviceList()
    val result = LibUsb.getDeviceList(context, list)
    ...
    return list.firstOrNull { device ->
        val desc = DeviceDescriptor()
        LibUsb.getDeviceDescriptor(device, desc)
        val vid = desc.idVendor().toInt() and 0xFFFF
        val pid = desc.idProduct().toInt() and 0xFFFF
        val isAndroidVid = vid in knownAndroidVids
        val isAoa = pid == 0x2D00 || pid == 0x2D01
        if (requireAccessory) isAoa else (isAndroidVid || isAoa)
    }?.also { LibUsb.refDevice(it) }
    ```
    Observed: It uses `list.firstOrNull { ... }` which truncates discovery to the **first matching device** found on the host machine's USB bus hierarchy, ignoring all other connected Android devices.
  - Method `isDevicePhysicallyConnected(): Pair<Boolean, String?>` (lines 74–99): Also returns early on the first matching device found (`return Pair(true, nameStr)`).
  - Reference counting (lines 68, 101-103): Calls `LibUsb.refDevice(it)` on returned device and `LibUsb.unrefDevice(device)` in `releaseDevice(device)`.

- **`UsbConnection.kt`** (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbConnection.kt`):
  - Encapsulates a single device handle (`private var handle: DeviceHandle? = null`, line 10).
  - `switchToAoa(device: Device): Boolean` (lines 28–116): Opens a temporary `DeviceHandle`, issues vendor control requests 51 (`ACCESSORY_GET_PROTOCOL`), 52 (`ACCESSORY_SEND_STRING`), and 53 (`ACCESSORY_START`). When `ACCESSORY_START` is issued, the device resets its USB PHY/gadget driver and re-enumerates on the USB bus with VID `0x18D1` and PID `0x2D00` or `0x2D01`.
  - `open(device: Device): Boolean` (lines 120–168): Opens `DeviceHandle`, discovers bulk IN/OUT endpoints via `findAoaEndpoints(device)`, auto-detaches kernel driver (`LibUsb.setAutoDetachKernelDriver(handle, true)`), and claims interface (`LibUsb.claimInterface`).

- **`UsbRepositoryImpl.kt`** (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/repo/UsbRepositoryImpl.kt`):
  - Accepts single instances of `UsbDeviceManager` and `UsbConnection` (lines 15–19).
  - `connect()` (lines 30–85) polls `deviceManager.findAndroidDevice()`, switches to AOA if needed, waits for `findAndroidDevice(requireAccessory = true)` to reappear, opens `connection`, and performs channel handshake.

- **`appModule.kt`** (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/di/appModule.kt`):
  - Declares `single { UsbDeviceManager() }`, `single { UsbConnection() }`, and `single<UsbRepository> { UsbRepositoryImpl(get(), get()) }` (lines 12–17).

- **Hardware Identifier APIs in `usb4java` / `libusb`**:
  - `LibUsb.getBusNumber(device: Device): Int` — Physical host controller USB bus index (0–255).
  - `LibUsb.getPortNumber(device: Device): Int` — Physical hub port index (0–255).
  - `LibUsb.getPortNumbers(device: Device, buffer: ByteBuffer): Int` — Hierarchical array of port numbers from root hub to device (e.g. `[1, 3]`).
  - `LibUsb.getDeviceAddress(device: Device): Int` — Dynamic USB bus address assigned during enumeration (changes on device reset or AOA switch).
  - `DeviceDescriptor.iSerialNumber()` (Byte) + `LibUsb.getStringDescriptorAscii(handle, index)` — Device serial string (requires opening `DeviceHandle`).

---

## 2. Logic Chain

1. **Problem Statement**: The current USB hardware layer can only discover, connect to, and manage a single Android device.
2. **Root Cause Identification**:
   - `UsbDeviceManager.findAndroidDevice()` uses `list.firstOrNull` (line 53 of `UsbDeviceManager.kt`), returning only the first Android device detected in `LibUsb.getDeviceList()`.
   - `UsbConnection` holds a single `handle: DeviceHandle?` field (line 10 of `UsbConnection.kt`), binding it to one physical USB connection at a time.
   - `appModule.kt` injects `UsbDeviceManager`, `UsbConnection`, and `UsbRepositoryImpl` as singletons (lines 12–17 of `appModule.kt`).
3. **Multi-Device Hardware Tracking Requirements**:
   - To support multiple simultaneous devices, `UsbDeviceManager` must enumerate ALL matching devices into a list/map instead of returning only the first match (`firstOrNull`).
   - Each device must be assigned a unique, stable hardware identifier.
4. **Hardware Identifier Selection Logic**:
   - **VID & PID**: Not unique across multiple devices of the same manufacturer or model (e.g. two Samsung phones share VID `0x04E8` and PID `0x6860`).
   - **Device Address (`getDeviceAddress`)**: Dynamic and changes whenever the device re-enumerates (e.g. during AOA mode switch).
   - **Serial Number (`getStringDescriptorAscii`)**: Requires opening a `DeviceHandle` (which might fail if the device is busy or uninitialized) and may be generic in AOA mode.
   - **Bus & Port Path (`getBusNumber` + `getPortNumber` / `getPortNumbers`)**:
     - Physical-socket based: directly identifies the hardware port on the computer/hub.
     - Invariant across AOA mode switches: when a phone switches to AOA mode (`ACCESSORY_START`), its address changes, but its physical Bus and Port numbers remain identical!
     - No permissions or device opening required: `LibUsb.getBusNumber(device)` and `LibUsb.getPortNumber(device)` operate on un-opened `Device` pointers.
   - **Conclusion on Unique Identifier**: The composite string `"bus${busNumber}-port${portNumber}"` (or `"bus${busNumber}-path${portPath}"`, e.g. `"1-2"`) is the ideal unique hardware key for tracking connected devices.

5. **Proposed Hardware Layer Refactoring Architecture**:
   - Define a domain model / data class `UsbDeviceInfo`:
     ```kotlin
     data class UsbDeviceInfo(
         val id: String,          // e.g. "bus1-port2"
         val busNumber: Int,      // e.g. 1
         val portNumber: Int,     // e.g. 2
         val portPath: String,    // e.g. "1-2"
         val vendorId: Int,       // e.g. 0x04E8
         val productId: Int,      // e.g. 0x6860
         val isAoa: Boolean,      // true if PID is 0x2D00 or 0x2D01
         val displayName: String  // e.g. "Samsung Device (VID: 04E8, PID: 6860) [Bus 1, Port 2]"
     )
     ```
   - Update `UsbDeviceManager`:
     - `findAllAndroidDevices(): List<DiscoveredUsbDevice>` — enumerates all connected Android devices, returning their descriptors and `UsbDeviceInfo`.
     - `findDeviceByHardwareId(hardwareId: String): Device?` — locates a specific device by its physical bus/port hardware ID.
     - `switchToAoaForDevice(hardwareId: String): Boolean` — performs AOA handshake specifically on the target device matching `hardwareId`.
   - Update `UsbConnection` instantiation:
     - Replace singleton `UsbConnection` with per-device instances (instantiated per active session in `UsbSessionManager`).

---

## 3. Caveats

- **Kernel Driver Detach Behavior on Linux**: On Linux hosts, `LibUsb.detachKernelDriver` is required to release kernel interface ownership (e.g. MTP driver). If another desktop process (like gvfsd-mtp) holds an exclusive claim, `LibUsb.open` or `claimInterface` may fail with `ERROR_BUSY` (-6).
- **USB Hubs & Port Paths**: `getPortNumbers` returns a path for nested hubs (e.g. `[1, 4]` for port 4 on hub port 1). Using `busNumber` combined with full port path ensures uniqueness even through nested hubs.
- **AOA Re-enumeration Delay**: When `switchToAoa()` sends `ACCESSORY_START`, the device drops off the bus for 100ms–1500ms before re-enumerating in AOA mode. During this transition, matching the newly appeared AOA device to the original device should be done by filtering for `isAoa == true` on the same `busNumber` and `portNumber`.

---

## 4. Conclusion

- The current hardware layer in `UsbDeviceManager.kt` is hardcoded to find and manage only a single USB device (`list.firstOrNull`).
- Unique hardware identification for concurrent devices can be achieved cleanly via libusb physical bus and port path numbers (`LibUsb.getBusNumber` and `LibUsb.getPortNumber` / `LibUsb.getPortNumbers`), creating stable keys like `"bus1-port2"`.
- `UsbDeviceManager` needs to be refactored to discover all matching devices (`findAllAndroidDevices()`) and allow targeted device selection by hardware ID (`findDeviceByHardwareId(id)`).
- `UsbConnection` and `UsbRepositoryImpl` must be decoupled from global Koin singletons so that a separate `UsbConnection` and `UsbRepositoryImpl` instance can be created per physical device session.

---

## 5. Verification Method

To independently verify these observations and conclusions:
1. **Inspect Files**:
   - `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt` (lines 46–72: `findAndroidDevice` single match; lines 29–44: `knownAndroidVids`).
   - `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbConnection.kt` (lines 28–116: `switchToAoa`; lines 120–168: `open`).
   - `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/repo/UsbRepositoryImpl.kt` (lines 30–85: `connect`).
   - `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/di/appModule.kt` (lines 12–17: Koin singletons).
2. **Libusb Verification**:
   - Verify `org.usb4java.LibUsb` methods `getBusNumber(device)`, `getPortNumber(device)`, `getPortNumbers(device, buffer)` exist and return physical bus/port topology without requiring open device handles.
3. **Invalidation Conditions**:
   - If `LibUsb.getBusNumber` or `getPortNumber` return identical values for devices plugged into separate physical USB ports, the topology key hypothesis would be invalid (however, according to USB 2.0/3.x specifications and libusb API contracts, bus/port numbers are guaranteed to be unique per host controller port).
