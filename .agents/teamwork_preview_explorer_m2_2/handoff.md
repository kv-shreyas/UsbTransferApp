# Handoff Report — Milestone 2 Koin DI & Lifecycle Explorer

## 1. Observation

Direct code observations from `/home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp`:

1. **Current Koin Module Singletons (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/di/appModule.kt:11-22`)**:
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
   - `UsbRepositoryImpl` is registered as a Koin `single`, bound to a global singleton `UsbConnection`.
   - `UsbConnection` (`UsbConnection.kt:10-26`) holds a single `private var handle: DeviceHandle? = null` and single direct I/O buffers (`readBuffer`, `writeBuffer`).
   - `MainViewModel` (`MainViewModel.kt:19-25`) injects singletons `UsbUseCases` and `UsbRepository`, locking all operations behind a global `private val usbMutex = Mutex()`.

2. **Milestone 1 Discovery Capability (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt:64-109`)**:
   ```kotlin
   fun discoverDevices(): List<DiscoveredUsbDevice> = synchronized(usbLock) {
       ...
       for (device in list) {
           ...
           if (isAndroidVid || isAoa) {
               val (id, busNumber, portNumber) = getHardwareIdentifier(device)
               ...
               LibUsb.refDevice(device) // Ref count +1
               discovered.add(DiscoveredUsbDevice(id = id, ..., device = device))
           }
       }
       return discovered
   }
   ```
   - `discoverDevices()` correctly identifies multiple connected Android devices concurrently, generating unique physical hardware IDs (`"bus_X_port_Y"`).
   - **Crucial Native Memory Behavior**: `discoverDevices()` calls `LibUsb.refDevice(device)` (ref count +1) on EVERY item returned in the list.

3. **Entry Point Koin Startup (`composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/main.kt:16-21`)**:
   ```kotlin
   startKoin {
       modules(appModule)
   }
   application {
       val vm: MainViewModel = getKoin().get()
       ...
   }
   ```
   - Koin initializes `appModule` on JVM startup and resolves `MainViewModel` from the global context.

---

## 2. Logic Chain

1. **Current Bottleneck & Root Cause**:
   - The application currently binds `UsbRepositoryImpl` and `UsbConnection` as global singletons in Koin `appModule.kt`.
   - Connecting a second Android device overwrites or collides with the single `UsbConnection` handle.
   - Global `usbMutex` in `MainViewModel` blocks transfers across all devices.

2. **Requirement for Milestone 2 Architecture**:
   - Replace global `UsbRepositoryImpl` / `UsbConnection` singletons with a singleton `UsbSessionManager`.
   - `UsbSessionManager` runs a coroutine polling loop (`startPolling()`) invoking `deviceManager.discoverDevices()`.
   - Each physical device (`"bus_X_port_Y"`) gets its own isolated `UsbSession` holding a dedicated `UsbConnection`, `UsbRepositoryImpl`, and per-device `Mutex`.
   - `sessionsState: StateFlow<Map<String, UsbSessionState>>` exposes the state of all active sessions to `MainViewModel` and the UI layer.

3. **Native Reference-Count Safety Rule**:
   - `deviceManager.discoverDevices()` increments the native libusb reference count (+1) for every returned `DiscoveredUsbDevice`.
   - When `UsbSessionManager` polls every 1500ms, devices ALREADY tracked in `activeSessions` will produce a new `DiscoveredUsbDevice` object with an unneeded +1 reference count.
   - `UsbSessionManager.pollDevices()` MUST call `deviceManager.releaseDevice(devInfo.device)` for all already-tracked devices to prevent severe native libusb handle memory leaks.
   - When a device is unplugged or removed, calling `session.disconnect()` must release the session's retained `Device` handle.

4. **Refactored Koin DI Architecture**:
   - `single { UsbDeviceManager() }`: Global hardware enumerator.
   - `single { UsbSessionManager(get()) }`: Global multi-device session orchestrator.
   - `factory { UsbConnection() }`: Factory producing fresh `UsbConnection` instances per session.
   - `single { MainViewModel(get()) }`: Refactored ViewModel consuming `UsbSessionManager`.

---

## 3. Caveats

- **AOA Re-enumeration Handshake**: When switching a device to AOA mode, the device disconnects and re-enumerates on the USB bus. Because `getHardwareIdentifier()` uses physical bus and port hierarchy (`"bus_X_port_Y"`), the hardware ID remains invariant before and after AOA mode switch.
- **Concurrent Polling Thread Safety**: `activeSessions` must use `ConcurrentHashMap<String, UsbSession>` and `sessionsState` updates must be atomic to ensure zero race conditions between background polling and UI state reading.
- **Disconnect Cleanup**: Disconnecting a session must gracefully close the `UsbConnection`, cancel active transfer jobs, and unref the native device handle without interrupting transfers on other connected devices.

---

## 4. Conclusion & Exact Code Specifications

### Specification 1: `UsbSessionState.kt`
Path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/UsbSessionState.kt`

```kotlin
package com.example.securequicktransferapp.domain.model

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

---

### Specification 2: `UsbSession.kt`
Path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbSession.kt`

```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.data.repo.UsbRepositoryImpl
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

class UsbSession(
    val deviceId: String,
    val initialDiscoveredDevice: DiscoveredUsbDevice,
    val connection: UsbConnection,
    val deviceManager: UsbDeviceManager
) {
    val repository: UsbRepository = UsbRepositoryImpl(
        deviceManager = deviceManager,
        connection = connection,
        targetDeviceId = deviceId
    )
    val sessionMutex = Mutex()

    private val _sessionState = MutableStateFlow(
        UsbSessionState(
            deviceId = deviceId,
            deviceName = initialDiscoveredDevice.deviceName
        )
    )
    val sessionState: StateFlow<UsbSessionState> = _sessionState

    fun updateState(transform: (UsbSessionState) -> UsbSessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    suspend fun connect(): Boolean {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Connecting)
        val success = repository.connect()
        _sessionState.value = _sessionState.value.copy(
            status = if (success) DeviceSessionStatus.Ready else DeviceSessionStatus.Error("Connection or Handshake failed"),
            isAoaMode = repository.isAoaMode
        )
        return success
    }

    fun disconnect() {
        _sessionState.value = _sessionState.value.copy(status = DeviceSessionStatus.Disconnected)
        try {
            repository.disconnect()
        } catch (e: Exception) {
            println("[UsbSession] Error during repository disconnect for $deviceId: ${e.message}")
        }
        deviceManager.releaseDevice(initialDiscoveredDevice.device)
    }
}
```

---

### Specification 3: `UsbSessionManager.kt`
Path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbSessionManager.kt`

```kotlin
package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class UsbSessionManager(
    private val deviceManager: UsbDeviceManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()

    private val _sessionsState = MutableStateFlow<Map<String, UsbSessionState>>(emptyMap())
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = _sessionsState

    @Volatile
    private var isPolling = false

    fun startPolling() {
        if (isPolling) return
        isPolling = true
        scope.launch {
            while (isActive) {
                try {
                    pollDevices()
                } catch (e: Exception) {
                    println("[UsbSessionManager] Polling error: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    fun stopPolling() {
        isPolling = false
        scope.coroutineContext.cancelChildren()
        for (session in activeSessions.values) {
            session.disconnect()
        }
        activeSessions.clear()
        _sessionsState.value = emptyMap()
    }

    private fun pollDevices() {
        val discoveredDevices = deviceManager.discoverDevices()
        val discoveredMap = discoveredDevices.associateBy { it.id }

        // 1. Identify and remove disconnected sessions
        val removedIds = activeSessions.keys.filter { it !in discoveredMap }
        for (id in removedIds) {
            val session = activeSessions.remove(id)
            session?.disconnect()
            println("[UsbSessionManager] Device removed: $id")
        }

        // 2. Identify newly connected devices & manage native ref counts
        for ((id, devInfo) in discoveredMap) {
            if (!activeSessions.containsKey(id)) {
                // New device detected -> create UsbSession taking ownership of devInfo handle
                println("[UsbSessionManager] New device detected: $id (${devInfo.deviceName})")
                val session = UsbSession(
                    deviceId = id,
                    initialDiscoveredDevice = devInfo,
                    connection = UsbConnection(),
                    deviceManager = deviceManager
                )
                activeSessions[id] = session
                
                // Asynchronously attempt connection
                scope.launch {
                    session.connect()
                }
            } else {
                // Device already tracked in activeSessions.
                // discoverDevices() returned a +1 refCount handle that won't be stored.
                // MUST release reference to prevent native libusb handle leak!
                deviceManager.releaseDevice(devInfo.device)
            }
        }

        // 3. Update published StateFlow
        _sessionsState.value = activeSessions.mapValues { it.value.sessionState.value }
    }

    fun getSession(deviceId: String): UsbSession? = activeSessions[deviceId]

    fun updateSessionState(deviceId: String, transform: (UsbSessionState) -> UsbSessionState) {
        val session = activeSessions[deviceId] ?: return
        session.updateState(transform)
        _sessionsState.value = activeSessions.mapValues { it.value.sessionState.value }
    }
}
```

---

### Specification 4: `appModule.kt` Koin Bindings
Path: `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/di/appModule.kt`

```kotlin
package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSessionManager
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

## 5. Lifecycle Safety & Memory Rules

1. **Native Handle Release**:
   - Every call to `deviceManager.discoverDevices()` increments the ref-count (+1) of all returned `Device` handles.
   - `UsbSessionManager` MUST call `deviceManager.releaseDevice(devInfo.device)` on every poll for devices that are already registered in `activeSessions`.
   - When a device is unplugged or `session.disconnect()` is called, `deviceManager.releaseDevice(initialDiscoveredDevice.device)` MUST be called.

2. **Per-Device Mutex Isolation**:
   - Each `UsbSession` owns its `sessionMutex`.
   - UI and ViewModel operations target a specific `deviceId`'s `sessionMutex`, guaranteeing that file transfers on Device A never block operations on Device B.

3. **Concurrency Safety**:
   - `UsbSessionManager` uses `ConcurrentHashMap<String, UsbSession>` and atomic `StateFlow` updates.
   - `startPolling()` uses `@Volatile` flag and `SupervisorJob()` scope to ensure background polling failure does not crash the supervisor or leak threads.

---

## 6. Verification Method

1. **Inspection**:
   - Verify `UsbSessionState.kt`, `UsbSession.kt`, `UsbSessionManager.kt`, and `appModule.kt` exist and match the specs above.
   - Verify Koin bindings in `appModule.kt` bind `UsbDeviceManager` and `UsbSessionManager` as `single`, and `UsbConnection` as `factory`.

2. **Compilation & Build Test**:
   - Execute `./gradlew :composeApp:compileKotlinJvm` to verify zero syntax or type resolution errors.

3. **Unit / Integration Test Verification**:
   - Verify that calling `UsbSessionManager.startPolling()` correctly discovers attached devices, populates `sessionsState`, and releases duplicate libusb handles.
