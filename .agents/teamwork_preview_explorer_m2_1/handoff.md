# Handoff Report — Milestone 2 Domain & Session State Explorer

## 1. Observation

Direct code observations from `/home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp`:

1. **Hardware Layer Multi-Device Discovery (`DiscoveredUsbDevice.kt` & `UsbDeviceManager.kt`)**:
   - `DiscoveredUsbDevice` (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt:17-26`):
     ```kotlin
     data class DiscoveredUsbDevice(
         val id: String,          // Hardware identifier: "bus_{busNumber}_port_{portPathStr}"
         val busNumber: Int,
         val portNumber: Int,
         val vendorId: Int,
         val productId: Int,
         val isAoa: Boolean,
         val deviceName: String,
         val device: Device       // Native usb4java Device handle (+1 ref count)
     )
     ```
   - `UsbDeviceManager.kt:38-57`: `getHardwareIdentifier(device)` constructs invariant hardware identifiers using USB bus and hub port path (e.g. `"bus_1_port_3"`).
   - `UsbDeviceManager.kt:64-109`: `discoverDevices()` enumerates all connected USB devices and returns `List<DiscoveredUsbDevice>`.
   - `UsbDeviceManager.kt:115-143`: `findDeviceById(hardwareId)` finds a connected device matching a given physical `hardwareId`.

2. **Existing Singleton Repository & Mutex Bottlenecks (`appModule.kt:9-17` & `MainViewModel.kt:25`)**:
   - `appModule.kt:9-17`:
     ```kotlin
     val appModule = module {
         single { UsbDeviceManager() }
         single { UsbConnection() }
         single<UsbRepository> { UsbRepositoryImpl(get(), get()) }
         factory { UsbUseCases(get()) }
         single { MainViewModel(get(), get()) }
     }
     ```
     `UsbRepositoryImpl` and `UsbConnection` are singletons. A single connection handle prevents multi-device operations.
   - `MainViewModel.kt:25`: `private val usbMutex = Mutex()` — a single global mutex locks all file transfers, directory listings, folder creations, and file operations. A large file transfer on Device A blocks all operations on any other device.

3. **Existing Model Definitions**:
   - `RemoteFile` (`composeApp/src/commonMain/kotlin/com/example/securequicktransferapp/domain/model/RemoteFile.kt:3-8`):
     ```kotlin
     data class RemoteFile(
         val name: String,
         val isDirectory: Boolean,
         val size: Long,
         val path: String
     )
     ```
   - `TransferProgress` (`composeApp/src/commonMain/kotlin/com/example/securequicktransferapp/domain/model/TransferState.kt:3-18`): Contains progress metrics (`isVisible`, `filename`, `total`, `percentage`, `speed`, `transferred`, `eta`, `elapsed`, `statusMessage`, `batchElapsed`, `totalFiles`, `currentFileIndex`, `isComplete`, `queue`).

---

## 2. Logic Chain

1. **Decoupling Singleton Connection to Multi-Device Sessions**:
   - To support multiple simultaneous Android USB devices, the single global `UsbConnection` and single global `UsbRepositoryImpl` must be superseded by per-device session objects (`UsbSession`).
   - Each `UsbSession` encapsulates:
     - `deviceId`: Physical hardware string (e.g. `"bus_1_port_3"`).
     - `discoveredDevice`: Initial and current `DiscoveredUsbDevice` hardware descriptor.
     - `connection`: Dedicated `UsbConnection` instance per session.
     - `repository`: Dedicated `UsbRepositoryImpl` instance bound to the session's `UsbConnection` and target `deviceId`.
     - `sessionMutex`: Dedicated `Mutex` instance per session. Locks only operations for this specific device, allowing Device B to browse/transfer while Device A is transferring.
     - `sessionState`: `StateFlow<UsbSessionState>` exposing the isolated session state.

2. **Session State Flow Structure (`UsbSessionState.kt`)**:
   - `UsbSessionState` must encapsulate all state required to present a single device's UI tab and file browser:
     - `deviceId: String`
     - `deviceName: String`
     - `status: DeviceSessionStatus` (`Disconnected`, `Connecting`, `Ready`, `Error(message)`)
     - `remoteFiles: List<RemoteFile>`
     - `currentPath: String` (defaults to `"/sdcard"`)
     - `progress: TransferProgress`
     - `isAoaMode: Boolean`
   - Using immutable `data class` with explicit `copy()` updates guarantees thread-safe emissions over `StateFlow`.

3. **Concurrency & Thread Safety**:
   - `sessionMutex` in `UsbSession` eliminates global UI blocking.
   - Updating `sessionState` using `_sessionState.value = _sessionState.value.copy(...)` is atomic and thread-safe.
   - Independent coroutines running transfers on `sessionA` lock `sessionA.sessionMutex` without touching `sessionB.sessionMutex`.

---

## 3. Caveats

1. **AOA Transition & Hardware Identifier Stability**:
   - When `switchToAoa` is executed on Device A, the physical USB device resets its stack and re-enumerates as an AOA accessory (PID `0x2D00` or `0x2D01`).
   - The physical USB bus and hub port path (`"bus_X_port_Y"`) remain invariant during this transition.
   - `UsbSession` must update its internal `discoveredDevice` and `isAoaMode` state when the device re-enumerates under AOA mode without destroying session identity.

2. **LibUsb Native Memory Management**:
   - `DiscoveredUsbDevice` holds a retained native `Device` handle (+1 ref count via `LibUsb.refDevice`).
   - When a `UsbSession` is disconnected or garbage collected, `UsbDeviceManager.releaseDevice(device)` or `connection.close()` must be called to decrement ref count and avoid native memory leaks.

3. **Physical Unplugging Edge Cases**:
   - If a physical USB cable is pulled mid-transfer:
     - The affected device's bulk I/O fails (`ERROR_NO_DEVICE`).
     - `UsbSession.disconnect()` or error callback transitions its status to `DeviceSessionStatus.Error("Device disconnected unexpectedly")` or `Disconnected`.
     - Other connected devices continue active transfers seamlessly.

---

## 4. Conclusion & Kotlin Code Specifications

### Specification A: `DeviceSessionStatus` & `UsbSessionState.kt`

Path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/UsbSessionState.kt`

```kotlin
package com.example.securequicktransferapp.domain.model

/**
 * Sealed representation of a USB device session status.
 */
sealed class DeviceSessionStatus {
    object Disconnected : DeviceSessionStatus()
    object Connecting : DeviceSessionStatus()
    object Ready : DeviceSessionStatus()
    data class Error(val message: String) : DeviceSessionStatus()
}

/**
 * Domain model representing the isolated reactive state of a single USB device session.
 *
 * @property deviceId Unique physical hardware identifier (e.g. "bus_1_port_3").
 * @property deviceName Human-readable display name summarizing device identity.
 * @property status Current session lifecycle status (Disconnected, Connecting, Ready, Error).
 * @property remoteFiles List of files currently loaded in the device's remote file browser view.
 * @property currentPath Current active remote directory path (defaults to "/sdcard").
 * @property progress Active transfer progress metrics for this specific device session.
 * @property isAoaMode True if the device has successfully negotiated AOA accessory transport.
 */
data class UsbSessionState(
    val deviceId: String,
    val deviceName: String,
    val status: DeviceSessionStatus = DeviceSessionStatus.Disconnected,
    val remoteFiles: List<RemoteFile> = emptyList(),
    val currentPath: String = "/sdcard",
    val progress: TransferProgress = TransferProgress(),
    val isAoaMode: Boolean = false
)
```

---

### Specification B: `UsbSession.kt`

Path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbSession.kt`

```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.data.repo.UsbRepositoryImpl
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Per-device session wrapper managing connection, transport, repository, state, and concurrency mutex.
 *
 * @property deviceId Invariant physical hardware identifier (e.g. "bus_1_port_3").
 * @property discoveredDevice Discovered device descriptor containing physical topology and handles.
 * @property connection Dedicated UsbConnection instance for this specific device.
 * @property repository Dedicated UsbRepository instance managing SDK communication for this device.
 * @property sessionMutex Dedicated Coroutine Mutex ensuring per-device transaction isolation.
 */
class UsbSession(
    val deviceId: String,
    var discoveredDevice: DiscoveredUsbDevice,
    val deviceManager: UsbDeviceManager,
    val connection: UsbConnection = UsbConnection(),
    val repository: UsbRepository = UsbRepositoryImpl(deviceManager, connection, deviceId)
) {
    val sessionMutex = Mutex()

    private val _sessionState = MutableStateFlow(
        UsbSessionState(
            deviceId = deviceId,
            deviceName = discoveredDevice.deviceName,
            isAoaMode = discoveredDevice.isAoa
        )
    )
    val sessionState: StateFlow<UsbSessionState> = _sessionState.asStateFlow()

    /**
     * Updates the underlying [DiscoveredUsbDevice] handle and state metadata upon re-enumeration (e.g. MTP -> AOA).
     */
    fun updateDiscoveredDevice(newDevice: DiscoveredUsbDevice) {
        this.discoveredDevice = newDevice
        _sessionState.value = _sessionState.value.copy(
            deviceName = newDevice.deviceName,
            isAoaMode = newDevice.isAoa
        )
    }

    /**
     * Initiates connection and SDK handshake for this physical device session.
     */
    suspend fun connect(): Boolean {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Connecting)
        val success = repository.connect()
        _sessionState.value = _sessionState.value.copy(
            status = if (success) DeviceSessionStatus.Ready else DeviceSessionStatus.Error("Connection or handshake failed"),
            isAoaMode = repository.isAoaMode
        )
        return success
    }

    /**
     * Disconnects the session, closes native endpoints, and updates state.
     */
    fun disconnect() {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Disconnected)
        repository.disconnect()
    }

    /**
     * Updates the list of remote files loaded for this device session.
     */
    fun updateRemoteFiles(files: List<RemoteFile>) {
        _sessionState.value = _sessionState.value.copy(remoteFiles = files)
    }

    /**
     * Updates the active remote navigation path for this device session.
     */
    fun updateCurrentPath(path: String) {
        _sessionState.value = _sessionState.value.copy(currentPath = path)
    }

    /**
     * Updates the transfer progress state for this device session.
     */
    fun updateProgress(progress: TransferProgress) {
        _sessionState.value = _sessionState.value.copy(progress = progress)
    }

    /**
     * Sets explicit session status.
     */
    fun updateStatus(status: DeviceSessionStatus) {
        _sessionState.value = _sessionState.value.copy(status = status)
    }
}
```

---

### Specification C: Recommended Edge Case Handling Protocol

1. **AOA Mode Handshake Isolation**:
   - `UsbRepositoryImpl.connect()` must target `deviceId` via `deviceManager.findDeviceById(deviceId)`.
   - When `switchToAoa` triggers device disconnect/re-connect, `findDeviceById` polls for the device on the same `busNumber`/`portNumber` path.

2. **Graceful Disconnect Cleanup**:
   - When a session disconnects, `connection.close()` releases claimed USB interfaces and attaches kernel drivers.
   - `deviceManager.releaseDevice(discoveredDevice.device)` decrements ref count.

3. **Mutex Isolation**:
   - `sessionMutex` locks only `UsbSession.repository` calls.
   - Multiple threads or UI coroutines can simultaneously execute transfers on `sessionA` and `sessionB` without blocking.

---

## 5. Verification Method

To verify the domain & session model specs:

1. **File Location Check**:
   - Ensure `UsbSessionState.kt` is located at `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/UsbSessionState.kt`.
   - Ensure `UsbSession.kt` is located at `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbSession.kt`.

2. **Compilation & Build Verification**:
   - Run `./gradlew compileKotlinJvm` or `./gradlew build` to confirm zero compilation errors.

3. **Unit / Integration Verification**:
   - Instantiate two `UsbSession` instances (`bus_1_port_1`, `bus_1_port_2`).
   - Verify that locking `session1.sessionMutex` does not block `session2.sessionMutex.withLock { ... }`.
   - Verify state emissions on `sessionState` flow independently for each device.
