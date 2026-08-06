# Milestone 3 Analysis: ViewModel State & Concurrency Refactor

## Executive Summary
This analysis details the technical design for refactoring `MainViewModel.kt` in `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/presentation/vm/MainViewModel.kt` to support concurrent multi-device operations.

The current implementation relies on a single device connection model protected by a global `usbMutex`, storing state for only one connected Android device. This refactor transitions `MainViewModel` to consume `UsbSessionManager`, manage per-device session states via `StateFlow<Map<String, UsbSessionState>>`, maintain active device selection state (`activeDeviceId`), and lock operations using per-device session mutexes (`session.sessionMutex`) rather than a single global lock.

---

## 1. Analysis of Existing `MainViewModel.kt`

### Current State & Architecture
- **Primary Constructor & Dependencies**:
  ```kotlin
  class MainViewModel(
      private val usbUseCases: UsbUseCases,
      private val usbRepository: UsbRepository
  )
  ```
  The secondary constructor accepts `sessionManager: UsbSessionManager`, but discards it completely and instantiates dummy singleton dependencies: `UsbUseCases(UsbRepositoryImpl(UsbDeviceManager(), UsbConnection()))`.
- **Global Concurrency Lock (`usbMutex`)**:
  A single `private val usbMutex = Mutex()` guards all filesystem operations across the application. When an operation (such as listing files, creating folders, uploading or fetching files) is executing on one device, all operations on any other device are blocked until `usbMutex` is released.
- **Single-Device Reactive State Flows**:
  - `_state: MutableStateFlow<String>` ("Idle", "Searching...", "Ready", etc.)
  - `_remoteFiles: MutableStateFlow<List<RemoteFile>>`
  - `_currentRemotePath: MutableStateFlow<String>` (defaults to `"/sdcard"`)
  - `_progressState: MutableStateFlow<TransferProgress>`
  - `_isPhysicallyConnected: MutableStateFlow<Boolean>`
  - `_physicallyConnectedDeviceName: MutableStateFlow<String?>`
- **Single Transfer Job (`transferJob: Job?`)**:
  A single nullable `Job` reference tracks active transfers. Invoking a new operation or cancelling a transfer affects all ongoing activity globally rather than targeting a specific device.
- **Physical Cable Connection Polling**:
  `startPhysicalConnectionMonitor()` runs an independent 1-second loop executing `usbRepository.checkPhysicalConnection()`. This conflicts with `UsbSessionManager`'s native LibUsb polling.

---

## 2. Refactored Architecture & Design Specifications

### Primary Constructor & Dependency Injection
Inject `UsbSessionManager` as a primary dependency (configured in `appModule.kt` as `single { MainViewModel(get()) }`):
```kotlin
class MainViewModel(
    val sessionManager: UsbSessionManager
)
```

### Device State Mapping & Active Device Selection
1. **Device Sessions State**:
   Expose all active session states reactively:
   ```kotlin
   val deviceSessions: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState
   ```
2. **Active Device Selection**:
   Maintain `_activeDeviceId` state:
   ```kotlin
   private val _activeDeviceId = MutableStateFlow<String?>(null)
   val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()
   ```
3. **Auto-Selection Logic**:
   Observe `deviceSessions` in `init`:
   - If `activeDeviceId` is `null` or points to a device no longer connected, set `activeDeviceId` to the first available key in `deviceSessions.value` (or `null` if empty).
   - Whenever `activeDeviceId` or the corresponding session state updates, sync active UI projections.

4. **Active UI State Projections (Backward Compatibility for MainScreen & SmartNav)**:
   - `activeRemoteFiles: StateFlow<List<RemoteFile>>`
   - `activeCurrentPath: StateFlow<String>`
   - `state: StateFlow<String>` (active device status or global readiness)
   - `progressState: StateFlow<TransferProgress>` (active device transfer progress)
   - `isPhysicallyConnected: StateFlow<Boolean>` (`deviceSessions.value.isNotEmpty()`)
   - `physicallyConnectedDeviceName: StateFlow<String?>` (active device's `deviceName` or `"No Device"`)

---

## 3. Session Resolution & Per-Device Mutex Isolation

### Target Session Resolution
Every file and directory operation accepts an optional `deviceId: String? = null`. If omitted, the operation resolves to `targetId = deviceId ?: activeDeviceId.value`.

```kotlin
val targetId = deviceId ?: activeDeviceId.value ?: return
val session = sessionManager.getSession(targetId) ?: return
```

### Per-Device Lock & Transfer Isolation
- **Eliminate Global Mutex**: Replace `usbMutex` with `session.sessionMutex` attached to each `UsbSession`.
- **Per-Device Transfer Jobs**: Track active transfer coroutine jobs per device using a concurrent map:
  ```kotlin
  private val activeTransferJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
  ```
- **Cancellation Isolation**:
  Calling `cancelTransfer(deviceId: String? = null)` cancels `activeTransferJobs[targetId]`, calls `session.repository.cancelTransfer()`, and updates `session.updateProgress(TransferProgress())`. Transfers on other devices continue uninterrupted.

---

## 4. Method-by-Method Refactoring Breakdown

| Method Signature | Action / Refactoring Strategy |
|---|---|
| `selectDevice(deviceId: String)` | Updates `_activeDeviceId.value = deviceId` and updates active UI projections. |
| `getSidebarRenderedDeviceIds(): List<String>` | Returns `deviceSessions.value.keys.toList()`. |
| `refreshRemoteFiles(deviceId: String? = null)` | Resolves session, acquires `session.sessionMutex`, calls `session.repository.listDirectory(currentPath)`, updates `session.updateRemoteFiles(...)`. |
| `navigateTo(file: RemoteFile, deviceId: String? = null)` | Updates `session.updateCurrentPath(file.path)` and refreshes remote files for `deviceId`. |
| `navigateUp(deviceId: String? = null)` | Calculates parent directory for `session.sessionState.value.currentPath`, updates path, and refreshes remote files. |
| `sendFiles(deviceId: String, files: List<File>, targetPath: String? = null): Job` | Overloaded/defaulted. Launches transfer `Job` on `scope`, locks `session.sessionMutex`, executes upload, updates `session.updateProgress(...)`, returns `Job`. |
| `fetchFiles(deviceId: String, remoteFiles: List<RemoteFile>, localDir: File? = null): Job` | Overloaded/defaulted. Launches fetch `Job` on `scope`, locks `session.sessionMutex`, streams files to `localDir`, updates progress, returns `Job`. |
| `cancelTransfer(deviceId: String? = null)` | Cancels `activeTransferJobs[targetId]`, calls `session.repository.cancelTransfer()`, resets progress for `targetId`. |
| `createFolder(folderName: String, deviceId: String? = null): Job` | Locks `session.sessionMutex`, calls `session.repository.createFolder(...)`, refreshes files, returns `Job`. |
| `deleteFile(remoteFile: RemoteFile, deviceId: String? = null): Job` | Locks `session.sessionMutex`, calls `session.repository.deleteFile(...)`, refreshes files, returns `Job`. |
| `renameFile(remoteFile: RemoteFile, newName: String, deviceId: String? = null): Job` | Locks `session.sessionMutex`, calls `session.repository.renameFile(...)`, refreshes files, returns `Job`. |
| `sendTextAsRemoteFile(...)` | Creates temp file and calls `session.repository.sendFile(...)` under `session.sessionMutex`. |
| `prepareLocalSmartNavStaging(...)` | Retained as-is for local filesystem staging. |

---

## 5. Verification Plan

1. **Unit & Integration Tests**:
   - `Tier1FeatureCoverageTest`: `testF3_PerDeviceMutexIsolation`, `testF3_ViewModelStateMapEmissions`, `testF3_ActiveDeviceSelection`, `testF3_ParallelTransferExecution`, `testF3_TransferCancellationIsolation`.
   - `./gradlew desktopTest` execution verifying zero build errors and clean test runs.
2. **Interface Contract Check**:
   - `MainViewModel` implements all contracts required by `PROJECT.md`, `TEST_INFRA.md`, `MainScreen.kt`, and test fixtures.
