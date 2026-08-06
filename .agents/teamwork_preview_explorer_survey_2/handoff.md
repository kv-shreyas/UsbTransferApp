# Handoff Report — Domain & Session Explorer

## 1. Observation

Direct code observations from `/home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp`:

1. **Repository & Transport Layer Singleton Binding (`appModule.kt:12-21`)**:
   ```kotlin
   val appModule = module {
       single { UsbDeviceManager() }
       single { UsbConnection() }
       single<UsbRepository> {
           UsbRepositoryImpl(get(), get())
       }
       factory { UsbUseCases(get()) }
       single { MainViewModel(get(), get()) }
   }
   ```
   - `UsbRepositoryImpl` is registered as a Koin `single` (singleton), taking a single global `UsbConnection` instance.
   - `UsbConnection` (`UsbConnection.kt:10`) holds a single `private var handle: DeviceHandle? = null` and fixed internal buffers (`readBuffer`, `writeBuffer`).

2. **Single-Device Enumeration in `UsbDeviceManager.kt:46-72`**:
   ```kotlin
   fun findAndroidDevice(requireAccessory: Boolean = false): Device? {
       val list = DeviceList()
       val result = LibUsb.getDeviceList(context, list)
       if (result < 0) return null
       try {
           return list.firstOrNull { device -> ... }?.also { LibUsb.refDevice(it) }
       } finally {
           LibUsb.freeDeviceList(list, true)
       }
   }
   ```
   - `findAndroidDevice()` returns only the `firstOrNull` matching USB device on the system bus.
   - Physical connection monitoring (`UsbDeviceManager.kt:74-99`) returns a global `Pair(Boolean, String?)` for the first Android device found, without differentiating multiple connected devices by hardware bus or port.

3. **Repository Connection & SDK Binding (`UsbRepositoryImpl.kt:15-30`)**:
   ```kotlin
   class UsbRepositoryImpl(
       private val deviceManager: UsbDeviceManager,
       private val connection: UsbConnection,
       private val logger: ILogger = ConsoleLogger()
   ) : UsbRepository {
       private val transport = DesktopUsbTransport(connection)
       private val channel = SecureQtSdk.createChannel(transport)
       private val transferClient = SecureQtSdk.createTransferClient(channel)
   ```
   - `UsbRepositoryImpl` instantiates `DesktopUsbTransport`, `Channel`, and `TransferClient` tied to a single `UsbConnection`.
   - `connect()` (`UsbRepositoryImpl.kt:30-85`) searches for any Android device, switches it to AOA mode, opens `connection`, and performs the SDK handshake (`channel.performHandshake(isInitiator = true)`).

4. **Global Mutex & Single-Device State in `MainViewModel.kt`**:
   - `MainViewModel.kt:25`: `private val usbMutex = Mutex()` — single global mutex used for all file listing, uploads (`sendFiles`), downloads (`fetchFiles`), folder creation (`createFolder`), and deletions (`deleteFile`).
   - `MainViewModel.kt:27-45`: Global state flows `_state: MutableStateFlow<String>`, `_remoteFiles: MutableStateFlow<List<RemoteFile>>`, `_currentRemotePath: MutableStateFlow<String>`, `_progressState: MutableStateFlow<TransferProgress>`, `_isPhysicallyConnected: MutableStateFlow<Boolean>`.
   - A long transfer to Device A locks `usbMutex` (e.g. `usbMutex.withLock { transferSingleFile(...) }` line 269), freezing all operations and preventing interaction with any other device.

---

## 2. Logic Chain

1. **Current Bottleneck**:
   - The application relies on singleton instances of `UsbConnection`, `UsbRepositoryImpl`, and `UsbUseCases` bound in Koin `appModule.kt`.
   - `UsbDeviceManager` returns only 1 `Device` handle.
   - `MainViewModel` controls operations through a single `usbMutex: Mutex` and single UI state flows.

2. **Root Cause of Single-Device Limitation**:
   - When a 2nd Android device is plugged in, `findAndroidDevice()` either returns the 1st device or conflicts when trying to reuse the global `UsbConnection` handle.
   - Any transfer on Device A acquires `usbMutex`, blocking all UI state updates and network/file I/O for any other device.

3. **Required Refactoring for Multi-Device Support (`UsbSessionManager`)**:
   - **Hardware Level (`UsbDeviceManager`)**:
     - `UsbDeviceManager` must enumerate all connected devices and return a list of `DiscoveredUsbDevice` objects.
     - Each `DiscoveredUsbDevice` must include a unique hardware identifier constructed from `LibUsb.getBusNumber(device)` and `LibUsb.getPortNumber(device)` (e.g., `"bus_1_port_3"`).
   - **Session Level (`UsbSessionManager` & `UsbSession`)**:
     - Replace the singleton `UsbRepositoryImpl` approach in Koin with a singleton `UsbSessionManager`.
     - `UsbSessionManager` polls for hardware devices, maintains active `UsbSession` instances in a `StateFlow<Map<String, UsbSessionState>>` (or `StateFlow<List<UsbSession>>`).
     - Each `UsbSession` owns its own dedicated `UsbConnection`, `DesktopUsbTransport`, SDK `Channel`, SDK `TransferClient`, `UsbRepositoryImpl`, and per-session `Mutex`.
   - **Domain & Koin DI**:
     - Register `UsbSessionManager` as a singleton in `appModule.kt`.
     - Define `factory` bindings for `UsbConnection` and `UsbRepositoryImpl` so `UsbSessionManager` can instantiate them per device.
   - **State & Concurrency (`MainViewModel`)**:
     - `MainViewModel` observes `UsbSessionManager.sessions`.
     - Transfers on Device A lock only `UsbSession("bus_1_port_3").sessionMutex`, leaving Device B's session and UI free to execute independent transfers concurrently.

---

## 3. Caveats

- **libusb Kernel Driver & Interface Detach**: When multiple devices are attached to USB hubs, libusb handles (`DeviceHandle`) must be opened and released carefully per device. Closing/resetting port on Device A must not affect Device B's bus handle.
- **AOA Mode Re-enumeration**: When Device A switches to AOA mode (PID 0x2D00/0x2D01), its USB product ID changes and it disconnects/re-enumerates on the bus. The `UsbSessionManager` polling loop must correlate re-enumerated AOA devices with their physical bus/port identifier to preserve session continuity.

---

## 4. Conclusion & Proposed Design

### Architectural Blueprint for Multi-Device Session Management

#### A. Data Models (`DiscoveredUsbDevice.kt` & `UsbSessionState.kt`)
```kotlin
package com.example.securequicktransferapp.domain.model

import org.usb4java.Device

data class DiscoveredUsbDevice(
    val id: String, // e.g. "bus_1_port_3"
    val busNumber: Int,
    val portNumber: Int,
    val vendorId: Int,
    val productId: Int,
    val isAoa: Boolean,
    val deviceName: String,
    val device: Device
)

sealed class DeviceSessionStatus {
    object Disconnected : DeviceSessionStatus()
    object Connecting : DeviceSessionStatus()
    object Ready : DeviceSessionStatus()
    data class Error(val message: String) : DeviceSessionStatus()
}

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

#### B. Per-Device Session Wrapper (`UsbSession.kt`)
```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.data.repo.UsbRepositoryImpl
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

class UsbSession(
    val deviceId: String,
    val discoveredDevice: DiscoveredUsbDevice,
    val connection: UsbConnection = UsbConnection(),
    val deviceManager: UsbDeviceManager
) {
    val repository = UsbRepositoryImpl(deviceManager, connection, discoveredDevice)
    val sessionMutex = Mutex()

    private val _sessionState = MutableStateFlow(
        UsbSessionState(deviceId = deviceId, deviceName = discoveredDevice.deviceName)
    )
    val sessionState: StateFlow<UsbSessionState> = _sessionState

    suspend fun connect(): Boolean {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Connecting)
        val success = repository.connect()
        _sessionState.value = _sessionState.value.copy(
            status = if (success) DeviceSessionStatus.Ready else DeviceSessionStatus.Error("Handshake failed"),
            isAoaMode = repository.isAoaMode
        )
        return success
    }

    fun disconnect() {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Disconnected)
        repository.disconnect()
    }
}
```

#### C. `UsbSessionManager` Implementation (`UsbSessionManager.kt`)
```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class UsbSessionManager(
    private val deviceManager: UsbDeviceManager
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()

    private val _sessionsState = MutableStateFlow<Map<String, UsbSessionState>>(emptyMap())
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = _sessionsState

    fun startPolling() {
        scope.launch {
            while (true) {
                pollDevices()
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    private fun pollDevices() {
        val currentDiscovered = deviceManager.discoverDevices()
        val discoveredMap = currentDiscovered.associateBy { it.id }

        // Remove disconnected sessions
        val toRemove = activeSessions.keys.filter { it !in discoveredMap }
        for (id in toRemove) {
            activeSessions[id]?.disconnect()
            activeSessions.remove(id)
        }

        // Add newly connected sessions
        for ((id, devInfo) in discoveredMap) {
            if (!activeSessions.containsKey(id)) {
                val session = UsbSession(id, devInfo, UsbConnection(), deviceManager)
                activeSessions[id] = session
            }
        }

        // Update state flow
        _sessionsState.value = activeSessions.mapValues { it.value.sessionState.value }
    }

    fun getSession(deviceId: String): UsbSession? = activeSessions[deviceId]
}
```

#### D. Koin Module Refactoring (`appModule.kt`)
```kotlin
package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import org.koin.dsl.module

val appModule = module {
    single { UsbDeviceManager() }
    single { UsbSessionManager(get()) }
    factory { UsbConnection() }
    single { MainViewModel(get()) }
}
```

---

## 5. Verification Method

To verify the domain & session manager migration:

1. **Inspection of File Structures**:
   - Confirm `UsbSessionManager.kt` and `UsbSession.kt` exist under `data/usb/`.
   - Confirm `appModule.kt` registers `UsbSessionManager` as `single`.
   - Confirm `UsbRepositoryImpl` is created per-session rather than as a global singleton.

2. **Static Code Analysis**:
   - Verify that `MainViewModel` uses `UsbSessionManager` to select and manage devices rather than referencing a single `UsbRepository`.
   - Verify that each session executes file transfers using its own `sessionMutex`, ensuring transfers on Device A do not lock Device B.

3. **Runtime & Test Verification**:
   - Connect two physical Android devices to the host machine.
   - Observe both devices appearing in `UsbSessionManager.sessionsState`.
   - Initiate a large upload on Device A and simultaneously browse/download files on Device B. Verify zero blocking.
