# Handoff Report — Milestone 1 Hardware Layer Worker (`teamwork_preview_worker_m1_1`)

## 1. Observation

- **Task Scope**: Refactor the USB hardware layer (`UsbDeviceManager.kt`) to support multi-device USB discovery and create the `DiscoveredUsbDevice` domain model.
- **File Ownership**:
  - `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt` (Created)
  - `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt` (Modified)
- **Observations from Upstream Explorer Reports**:
  - Legacy `UsbDeviceManager` used `list.firstOrNull` which limited Android device discovery to only a single connected device.
  - Devices lacked physical hardware identifiers; devices were only identified by VID/PID without tracking physical bus numbers or hub port hierarchies.
  - Native `LibUsb.getDeviceList` calls were unguarded, posing thread-safety risks when accessed concurrently by background polling coroutines or transfer threads.
  - Retaining device pointers across list freeing requires calling `LibUsb.refDevice(device)` prior to `LibUsb.freeDeviceList(list, true)`.

---

## 2. Logic Chain

1. **Domain Model (`DiscoveredUsbDevice.kt`)**:
   - Created `DiscoveredUsbDevice` data class encapsulating `id: String`, `busNumber: Int`, `portNumber: Int`, `vendorId: Int`, `productId: Int`, `isAoa: Boolean`, `deviceName: String`, and `device: Device`.
   - Represents a retained physical USB device reference (+1 ref count) ready for session assignment.

2. **Invariant Physical Hardware Identifiers (`getHardwareIdentifier`)**:
   - Extracted USB bus number (`LibUsb.getBusNumber(device) and 0xFF`) and primary port number (`LibUsb.getPortNumber(device) and 0xFF`).
   - Querying `LibUsb.getPortNumbers(device, pathBuffer)` with direct buffer `ByteBuffer.allocateDirect(7)` retrieves the complete port path down nested USB hubs.
   - Formatted identifier string `"bus_${busNumber}_port_${portPathStr}"` (e.g. `"bus_1_port_3"` or `"bus_1_port_1.3"`).
   - Invariant guarantee: When an Android phone switches modes (MTP -> AOA re-enumeration), its USB address changes, but its physical bus/port string remains identical, allowing `UsbSessionManager` to re-bind sessions seamlessly.

3. **Multi-Device Discovery (`discoverDevices()`)**:
   - Iterates through the entire `DeviceList` returned by `LibUsb.getDeviceList(context, list)`.
   - Filters devices matching `knownAndroidVids` or AOA PIDs (`0x2D00`, `0x2D01`).
   - For every match, calls `LibUsb.refDevice(device)` (+1 ref count), constructs `DiscoveredUsbDevice`, and adds to list.
   - Safely frees `DeviceList` in a `finally` block via `LibUsb.freeDeviceList(list, true)`.

4. **Targeted Device Finding & Connection Verification**:
   - Implemented `findDeviceById(hardwareId: String): Device?`: Scans connected devices, matches target `hardwareId`, calls `LibUsb.refDevice(device)`, and returns retained device handle.
   - Implemented `isDevicePhysicallyConnected(hardwareId: String): Pair<Boolean, String?>`: Scans connected devices for matching `hardwareId` and returns `Pair(true, deviceName)` or `Pair(false, null)`.

5. **Thread Safety & Lock Protection**:
   - Protected all native `LibUsb.getDeviceList` calls with `synchronized(usbLock)` to prevent JNI state corruption from concurrent calls.

6. **Backward Compatibility & Memory Cleanup**:
   - Refactored legacy `findAndroidDevice()` and `isDevicePhysicallyConnected()` to delegate to `discoverDevices()`.
   - In `finally` blocks, all non-selected discovered device references are safely released using `devices.forEach { releaseDevice(it.device) }`.

---

## 3. Caveats

- **Device Handle Lifetime**: Callers of `discoverDevices()` or `findDeviceById()` receive device handles with an incremented reference count (+1). Callers are responsible for invoking `releaseDevice(device)` or `LibUsb.unrefDevice(device)` when discarding sessions/references to prevent native memory leaks.
- **Root Hub Devices**: Root hub pseudo-devices report port number 0, while physical devices report port numbers >= 1.

---

## 4. Conclusion

The hardware layer refactor for Milestone 1 is complete. `UsbDeviceManager` now supports concurrent enumeration of multiple attached Android devices, generates invariant physical hardware keys, guards native calls with thread synchronization locks, and manages libusb reference counts safely.

---

## 5. Verification Method

### Source Files Inspected & Modified
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`

### Verification Steps & Commands
1. **Model Contract Inspection**:
   - Verified `DiscoveredUsbDevice` contains all required fields: `id`, `busNumber`, `portNumber`, `vendorId`, `productId`, `isAoa`, `deviceName`, `device`.
2. **Method Signature & Synchronization Verification**:
   - Verified `discoverDevices(): List<DiscoveredUsbDevice>` is implemented with `synchronized(usbLock)` and proper `refDevice` / `freeDeviceList` lifecycle.
   - Verified `findDeviceById(hardwareId: String): Device?` and `isDevicePhysicallyConnected(hardwareId: String): Pair<Boolean, String?>` are implemented.
   - Verified legacy `findAndroidDevice()` and `isDevicePhysicallyConnected()` delegate cleanly to `discoverDevices()`.
3. **Build & Compilation Command**:
   ```bash
   ./gradlew compileKotlinJvm
   ./gradlew desktopTest
   ```
