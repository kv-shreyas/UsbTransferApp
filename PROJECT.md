# Project: Multi-Device USB Transfer Refactor

## Architecture
- **Hardware Layer (`UsbDeviceManager.kt`)**: Enumerates physical USB devices via libusb (`usb4java`), filtering for Android VIDs / AOA PIDs. Generates invariant hardware bus/port keys (`"bus_X_port_Y"`).
- **Domain & Session Layer (`UsbSessionManager`, `UsbSession`)**: Manages per-device sessions dynamically. Each `UsbSession` owns its own `UsbConnection`, `UsbRepositoryImpl`, SDK transport/channel, and per-device coroutine `Mutex`. Exposed via `StateFlow<Map<String, UsbSessionState>>`. DI managed by Koin (`appModule.kt`).
- **ViewModel Concurrency Layer (`MainViewModel.kt`)**: Maintains active device selection and per-device state flow mapping. Replaces global single `usbMutex` with per-device session mutexes, enabling true parallel file transfers without cross-device blocking.
- **UI Presentation Layer (`MainScreen.kt`, `Sidebar.kt`)**: Refactors Compose Sidebar to display connected devices as selectable tabs/cards. Selecting a tab updates the main File Explorer view to reflect that specific device's state while background transfers continue on other devices.
- **E2E Testing & Hardening Track (`TEST_INFRA.md`)**: Independent requirement-driven test suite validating concurrent device discovery, session isolation, non-blocking UI during transfers, and safe unplugging.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Hardware Multi-Device Discovery | Update `UsbDeviceManager` to discover & track multiple devices concurrently using unique bus/port physical identifiers (`"bus_X_port_Y"`). | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Domain & Session Management | Create `UsbSessionManager` (Koin singleton) managing per-device `UsbSession` instances with state flow and per-device `UsbConnection`/`UsbRepositoryImpl`. | M2 | ORIGINAL_REQUEST §R2 |
| 3 | State & ViewModel Concurrency | Refactor `MainViewModel` to manage per-device states (`StateFlow<Map<String, DeviceUiState>>`) and per-device mutexes instead of single `usbMutex`. | M3 | ORIGINAL_REQUEST §R3 |
| 4 | UI Sidebar & Device Selection Tabs | Update Compose `Sidebar` & `MainScreen` to display device cards/tabs, allow device selection, update file explorer view, and preserve background transfers. | M4 | ORIGINAL_REQUEST §R4 |
| 5 | E2E Testing Suite & Hardening | Opaque-box E2E test suite (Tiers 1-4) + Tier 5 adversarial coverage hardening verifying concurrent multi-device transfer independence. | M5 | Project Dual Track Protocol |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Hardware Layer Refactor | Refactor `UsbDeviceManager` for multi-device discovery & unique hardware bus/port identifiers | None | DONE |
| M2 | Domain & Session Management | Build `UsbSessionManager`, `UsbSession`, `appModule.kt` Koin bindings | M1 | DONE |
| M3 | ViewModel State & Concurrency | Update `MainViewModel` for multi-device state map and per-device mutexes | M2 | IN_PROGRESS |
| M4 | Compose UI Multi-Device Tabs | Refactor `Sidebar` & `MainScreen` for multi-device selection & non-blocking UI | M3 | PLANNED |
| M5 | 100% E2E Pass & Hardening | Pass 100% of E2E test suite (Tiers 1-4) and Tier 5 adversarial coverage hardening | M1, M2, M3, M4 | PLANNED |

## Interface Contracts

### UsbDeviceManager ↔ UsbSessionManager
```kotlin
data class DiscoveredUsbDevice(
    val id: String,          // e.g. "bus_1_port_3"
    val busNumber: Int,
    val portNumber: Int,
    val vendorId: Int,
    val productId: Int,
    val isAoa: Boolean,
    val deviceName: String,
    val device: Device
)

fun discoverDevices(): List<DiscoveredUsbDevice>
fun findDeviceById(id: String): Device?
```

### UsbSessionManager ↔ MainViewModel
```kotlin
class UsbSessionManager(private val deviceManager: UsbDeviceManager) {
    val sessionsState: StateFlow<Map<String, UsbSessionState>>
    fun getSession(deviceId: String): UsbSession?
    fun startPolling()
}
```

### MainViewModel ↔ Compose UI
```kotlin
class MainViewModel(val sessionManager: UsbSessionManager) {
    val activeDeviceId: StateFlow<String?>
    val deviceSessions: StateFlow<Map<String, UsbSessionState>>
    fun selectDevice(deviceId: String)
    fun sendFiles(deviceId: String, files: List<File>)
    fun fetchFiles(deviceId: String, remotePath: String, localDir: File)
}
```

## Code Layout
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/UsbSessionState.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbSessionManager.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbSession.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbConnection.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/repo/UsbRepositoryImpl.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/di/appModule.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/presentation/vm/MainViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/presentation/ui/MainScreen.kt`
