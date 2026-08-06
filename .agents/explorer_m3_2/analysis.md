# Analysis: UsbSessionManager & UsbSession Integration into MainViewModel

## Executive Summary
This analysis details the integration architecture of `UsbSessionManager` and `UsbSession` into `MainViewModel` for Milestone 3 (ViewModel State & Concurrency). The objective is to replace the legacy single-device singleton approach with a dynamic multi-device architecture that seamlessly handles active device selection, reactive Compose UI state emissions, isolated per-device concurrency, and robust edge-case protection.

---

## 1. UsbSessionManager.sessionsState Behavior Analysis

### 1.1 State Architecture & Data Types
In `UsbSessionManager.kt`, session state is exposed via:
```kotlin
private val _sessionsState = MutableStateFlow<Map<String, UsbSessionState>>(emptyMap())
val sessionsState: StateFlow<Map<String, UsbSessionState>> = _sessionsState.asStateFlow()
```
- **Key**: Invariant hardware bus/port identifier (e.g., `"bus_1_port_3"`, `"bus_2_port_1.2"`), matching the format `Regex("^bus_\\d+_port_[\\d.]+$")`.
- **Value**: `UsbSessionState` data class holding session-bound metadata:
  - `deviceId: String`
  - `deviceName: String`
  - `status: DeviceSessionStatus` (`Connecting`, `Ready`, `Disconnected`, `Error(message)`)
  - `remoteFiles: List<RemoteFile>`
  - `currentPath: String` (defaults to `"/sdcard"`)
  - `progress: TransferProgress`
  - `isAoaMode: Boolean`

### 1.2 Lifecycles & Flow Emissions
`UsbSessionManager.pollDevices()` runs periodically (every 1500ms on `Dispatchers.IO`):
1. **Device Discovery & Registration**:
   - `deviceManager.discoverDevices()` returns discovered devices with physical keys (`"bus_X_port_Y"`).
   - For newly discovered IDs not in `activeSessions`, `UsbSessionManager` instantiates a new `UsbSession(id, devInfo, deviceManager)`.
   - An observation job (`scope.launch { session.sessionState.collect { updateSessionsStateMap() } }`) is attached to propagate internal state changes immediately.
   - An asynchronous `session.connect()` is launched on `Dispatchers.IO`, setting status `Connecting` -> `Ready` (or `Error`).
2. **Device Removal / Unplugging**:
   - Any device key missing from `discoverDevices()` is identified as unplugged (`removedIds`).
   - `sessionJobs.remove(id)?.cancel()` cancels the state observation job.
   - `session.disconnect()` is called, closing native USB handles and updating `status` to `Disconnected`.
   - The device key is removed from `activeSessions`, and `updateSessionsStateMap()` publishes the updated map (removing the key from `sessionsState`).

---

## 2. Active Device Selection Management (`activeDeviceId`)

### 2.1 ViewModel Fields
```kotlin
private val _activeDeviceId = MutableStateFlow<String?>(null)
val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()
```

### 2.2 Auto-Selection Algorithm
`MainViewModel` collects `sessionManager.sessionsState` in its `init` block (or scope):

1. **Initial / Cold Start**:
   - When no devices are connected, `activeDeviceId.value` is `null`.
   - When the first device connects (e.g., `"bus_1_port_1"` added to `sessionsState`), `MainViewModel` detects `activeDeviceId.value == null` and auto-selects `sessionsState.keys.firstOrNull()`.
2. **Active Device Disconnection**:
   - If `activeDeviceId.value` (e.g., `"bus_1_port_1"`) is unplugged, `"bus_1_port_1"` is removed from `sessionsState.value`.
   - `MainViewModel` checks if `activeDeviceId.value !in sessionsState.value`.
   - If true, `MainViewModel` auto-selects the next available device ID (`sessionsState.keys.firstOrNull()`).
   - If `sessionsState.value.isEmpty()`, `activeDeviceId.value` is reset to `null`, and active UI explorer lists are cleared (`emptyList()`).
3. **Explicit Selection (`selectDevice`)**:
   - `fun selectDevice(deviceId: String)` sets `_activeDeviceId.value = deviceId`.
   - Guard check: verifies `deviceId in sessionManager.sessionsState.value` before updating.
   - Immediately triggers update of active explorer view state bindings (`activeRemoteFiles`, `activeCurrentPath`).

---

## 3. UI State Flow & Session Lookup Architecture for Compose UI

### 3.1 Exposing State to Compose (`Sidebar` & `MainScreen`)
To support concurrent multi-device display in `Sidebar` and active browsing in `MainScreen`:

```kotlin
// Map of all active device sessions for Sidebar rendering
val deviceSessions: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState

// Active device selection for MainScreen header and file list
val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

// Convenient reactive flows for current active device view
val activeRemoteFiles: StateFlow<List<RemoteFile>>
val activeCurrentPath: StateFlow<String>
val activeProgressState: StateFlow<TransferProgress>
```

### 3.2 Non-Blocking UI Architecture
- **Sidebar Tabs**: `Sidebar` observes `deviceSessions`. Each tab displays device ID, model name, status, and background transfer progress indicator.
- **MainScreen**: Consumes active device state. When user clicks tab for `"bus_2_port_3"`, `selectDevice("bus_2_port_3")` executes synchronously in < 1ms, updating `activeDeviceId`.
- **Parallel Concurrency Isolation**:
  - Each `UsbSession` owns a dedicated `sessionMutex: Mutex`.
  - Background transfers (e.g., `sendFiles("bus_1_port_1", files)`) lock `sessionA.sessionMutex` and run on `Dispatchers.IO`.
  - Device B's `sessionMutex` remains unlocked. The user can switch to Device B tab and initiate operations without UI latency or lock contention (`switchDuration < 200ms` as verified in `testF4_NonBlockingUiDuringTransfer`).

---

## 4. Comprehensive Edge Case Handling Matrix

| Edge Case | Description | Handling Strategy in `MainViewModel` |
|---|---|---|
| **Operations on Disconnected Session** | User calls `sendFiles`, `fetchFiles`, or `listDirectory` for a device ID that was unplugged or invalid. | Check `val session = sessionManager.getSession(deviceId)`. If `session == null` or `session.sessionState.value.status is DeviceSessionStatus.Disconnected`, fail-fast: skip operation and return/throw gracefully without locking mutex or touching native handles (`testB6_TransferToUnpluggedDevice`). |
| **Null `deviceId` Parameter** | VM method called with `deviceId: String? = null`. | Fallback: `val targetId = deviceId ?: activeDeviceId.value ?: return`. If no device is active/connected, safely no-op and log error message. |
| **Mid-Transfer Unplugging** | Active device unplugged while sending/fetching large file. | Native USB transport throws exception. `UsbSession.disconnect()` marks session `Disconnected`. Peer device transfers on other sessions continue completely uninterrupted (`testF4_DeviceUnplugPreservesPeerTransfer`). |
| **Concurrent Transfers on Different Devices** | User starts transfer on Device A, then immediately starts transfer on Device B. | Operations target separate `UsbSession` instances. `sessionA.sessionMutex` and `sessionB.sessionMutex` lock independently, allowing simultaneous transfers at full speed (`testF3_ParallelTransferExecution`, `testC4`). |
| **Concurrent Operations on SAME Device** | User attempts dual transfers or refresh on the same device. | Serialized via `sessionA.sessionMutex`. Second operation suspends until first completes, preventing protocol corruption. |
| **Rapid Tab Switching Under Load** | User clicks between Device A and B tabs 50+ times during active transfer. | `selectDevice` only updates `_activeDeviceId` StateFlow. Does NOT cancel or interrupt running transfer jobs (`testB16_DeviceSelectionSwitchMidTransfer`). |
| **Rapid Device Plug / Unplug** | USB cable rapidly connected/disconnected 20 times. | `UsbSessionManager.pollDevices()` cleanly disconnects removed sessions and releases libusb native handles without memory leak (`testB2_RapidDevicePlugUnplug`). |

---

## 5. Architectural Recommendations for Implementation
1. **Primary Constructor Injection**: Inject `sessionManager: UsbSessionManager` as a single constructor dependency in `MainViewModel`.
2. **Dynamic Flow Collection**: In `MainViewModel.init`, launch a coroutine to collect `sessionManager.sessionsState` and maintain `activeDeviceId` auto-selection and active view binding synchronization.
3. **Overloaded Convenience Signatures**: Provide default parameters (`deviceId: String? = null`) on `sendFiles`, `fetchFiles`, `refreshRemoteFiles`, `createFolder`, `deleteFile`, `renameFile` for ease of UI binding while maintaining explicit `deviceId` parameters for multi-device test fixtures.
