# Comprehensive Analysis & Code Blueprint: `MainViewModel.kt` (Milestone 3)

## 1. Executive Summary

Milestone 3 requires refactoring `MainViewModel.kt` to transition from a single-device, global-mutex architecture to a multi-device concurrent ViewModel architecture. 

### Key Objectives:
1. **Dependency Injection**: Accept `UsbSessionManager` via Koin (`appModule.kt` single binding).
2. **State Management**:
   - Expose `activeDeviceId: StateFlow<String?>` for tracking the active device selected in UI.
   - Expose `sessionsState: StateFlow<Map<String, UsbSessionState>>` (and alias `deviceSessions`) mirroring `sessionManager.sessionsState`.
   - Expose active-device scoped reactive flows (`remoteFiles`, `currentRemotePath`, `state`, `progressState`, `isPhysicallyConnected`, `physicallyConnectedDeviceName`, `isAoaMode`) for Compose UI (`MainScreen.kt`, `SmartNavDesktopDashboard.kt`).
3. **Concurrency Isolation**:
   - Replace legacy single `usbMutex` with per-device session mutexes (`session.sessionMutex`) and per-device transfer jobs (`activeJobs: ConcurrentHashMap<String, Job>`).
   - Ensure operations on Device A do not block operations on Device B.
   - Enable independent cancellation per device (`cancelTransfer(deviceId)`).
4. **Test Suite Compatibility**: Ensure 100% binary/source contract compatibility with all E2E tests (`Tier1FeatureCoverageTest`, `Tier2BoundaryCornerCasesTest`, `Tier3CrossFeatureCombinationsTest`, `Tier4RealWorldScenariosTest`, `UsbSessionManagerTest`, `UsbSessionConcurrencyStressTest`) and existing UI components.

---

## 2. Test Suite Compatibility & Requirement Matrix

An inspection of `composeApp/src/jvmTest/` reveals how the test harness interacts with `MainViewModel`:

| Test Suite / Source | Tested Method / Contract | Signature & Behavior Expectations |
|---------------------|--------------------------|-----------------------------------|
| `appModule.kt` & `UsbSessionManagerTest` | Constructor injection | `MainViewModel(val sessionManager: UsbSessionManager)` registered in Koin module. |
| `Tier1FeatureCoverageTest` | `deviceSessions` | `viewModel.deviceSessions.value` returns `Map<String, UsbSessionState>`. |
| `Tier1FeatureCoverageTest` | `selectDevice(deviceId)` | `selectDevice("bus_1_port_1")` updates `activeDeviceId.value` to `"bus_1_port_1"`. |
| `Tier1FeatureCoverageTest` | `sendFiles(deviceId, files)` | Returns `Job`. Transfers files to targeted device asynchronously without blocking peer devices. |
| `Tier1FeatureCoverageTest` | `cancelTransfer(deviceId)` | Cancels active transfer job for target device while preserving transfers on peer devices. |
| `Tier1FeatureCoverageTest` | `getSidebarRenderedDeviceIds()` | Returns `List<String>` of active device IDs from `deviceSessions.value.keys`. |
| `Tier1FeatureCoverageTest` | `activeRemoteFiles` / `activeDeviceId` | Selecting device updates `activeDeviceId` and reflects active remote files. |
| `Tier2BoundaryCornerCasesTest` | `createFolder(deviceId, folderName)` | Returns `Job`. Creates folder under target device's current path. |
| `Tier2BoundaryCornerCasesTest` | `deleteFile(deviceId, remoteFile)` | Returns `Job`. Deletes specified remote file on target device. |
| `Tier2BoundaryCornerCasesTest` | `fetchFiles(deviceId, remoteFiles, localDir)` | Returns `Job`. Fetches files from target device to local destination directory. |
| `Tier2BoundaryCornerCasesTest` | `tabSwitchCount` / Tab switching stress | Device tab selection returns immediately (< 200ms) during active transfers on background devices. |
| `Tier3CrossFeatureCombinationsTest` | Multi-device operations | Parallel transfer on Device A while switching tabs and browsing Device B. |
| `Tier4RealWorldScenariosTest` | Fleet provisioning & photo backup | Concurrent parallel uploads/downloads across 3+ devices simultaneously. |
| `MainScreen.kt` & `SmartNavDesktopDashboard.kt` | UI single-arg & no-arg overloads | `sendFiles(files, targetPath)`, `fetchFiles(remoteFiles)`, `createFolder(folderName)`, `deleteFile(remoteFile)`, `refreshRemoteFiles()`, `cancelTransfer()`. |

---

## 3. Detailed Signature Specification & Overload Strategy

To guarantee total compatibility with both multi-device unit/E2E test suites (which pass `deviceId`) and Compose UI (which defaults to the currently active device), `MainViewModel` must provide dual overloaded methods or default arguments:

### 3.1 Properties & StateFlows
```kotlin
val sessionManager: UsbSessionManager

// Active Device ID State
val activeDeviceId: StateFlow<String?>

// Session Maps (Exposed as both `sessionsState` and `deviceSessions` for full compliance)
val sessionsState: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState
val deviceSessions: StateFlow<Map<String, UsbSessionState>> = sessionsState

// Active Device Explorer & UI Reactive StateFlows
val activeRemoteFiles: StateFlow<List<RemoteFile>>
val remoteFiles: StateFlow<List<RemoteFile>> // Alias pointing to activeRemoteFiles
val activeCurrentPath: StateFlow<String>
val currentRemotePath: StateFlow<String> // Alias pointing to activeCurrentPath
val state: StateFlow<String>
val progressState: StateFlow<TransferProgress>
val isPhysicallyConnected: StateFlow<Boolean>
val physicallyConnectedDeviceName: StateFlow<String?>
val isAoaMode: Boolean
```

### 3.2 Method Signatures & Overloads

1. **Device Selection & Utility**:
   - `fun selectDevice(deviceId: String)`
   - `fun getSidebarRenderedDeviceIds(): List<String>`

2. **File Transfer - Send**:
   - `fun sendFiles(deviceId: String, files: List<File>, targetPath: String = "/sdcard"): Job`
   - `fun sendFiles(files: List<File>, targetPath: String? = null): Job`

3. **File Transfer - Fetch**:
   - `fun fetchFiles(deviceId: String, remoteFiles: List<RemoteFile>, localDir: File): Job`
   - `fun fetchFiles(remoteFiles: List<RemoteFile>): Job`

4. **File Systems - Directory Refresh & Navigation**:
   - `fun refreshRemoteFiles(deviceId: String? = activeDeviceId.value, remotePath: String? = null): Job`
   - `fun refreshRemoteFiles(): Job`
   - `fun navigateTo(file: RemoteFile)`
   - `fun navigateUp()`

5. **File Operations - Folder Creation & Deletion**:
   - `fun createFolder(deviceId: String, folderName: String): Job`
   - `fun createFolder(folderName: String): Job`
   - `fun deleteFile(deviceId: String, remoteFile: RemoteFile): Job`
   - `fun deleteFile(remoteFile: RemoteFile): Job`
   - `fun renameFile(remoteFile: RemoteFile, newName: String): Job`

6. **Cancellation & Progress Dismissal**:
   - `fun cancelTransfer(deviceId: String)`
   - `fun cancelTransfer()`
   - `fun dismissProgress()`

7. **SmartNav Management & Utilities**:
   - `fun checkRemoteFileExists(targetFolder: String, fileName: String, onResult: (Boolean) -> Unit)`
   - `fun sendTextAsRemoteFile(fileName: String, content: String, targetFolder: String)`
   - `fun prepareLocalSmartNavStaging(stagingDir: File, onComplete: () -> Unit)`
   - `fun connect()`
   - `fun disconnect()`

---

## 4. Concurrency & Per-Device Isolation Blueprint

### 4.1 Mutex Isolation Mechanism
Each physical USB device session managed by `UsbSessionManager` owns an isolated `sessionMutex: Mutex` inside `UsbSession`.

In `MainViewModel`:
```kotlin
val session = sessionManager.getSession(targetDeviceId) ?: return@launch
session.sessionMutex.withLock {
    // Isolated transfer execution for targetDeviceId
}
```
Because each device has its own `Mutex`, locking Device A's mutex during a 100MB file transfer leaves Device B's mutex unlocked. Device B can list directories, create folders, or transfer files simultaneously.

### 4.2 Job Tracking & Independent Cancellation
`MainViewModel` maintains a `ConcurrentHashMap<String, Job>` (`activeJobs`):
- When `sendFiles("bus_1_port_1", ...)` is called, the created `Job` is registered under key `"bus_1_port_1"`.
- Calling `cancelTransfer("bus_1_port_1")` cancels only `activeJobs["bus_1_port_1"]`, leaving `activeJobs["bus_1_port_2"]` running without interruption.

---

## 5. Precise Code Blueprint for `MainViewModel.kt`

Below is the complete implementation blueprint for `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/presentation/vm/MainViewModel.kt`:

```kotlin
package com.example.securequicktransferapp.presentation.vm

import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.data.usb.UsbSession
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(
    val sessionManager: UsbSessionManager
) {
    val TAG = "MainViewModel"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Active Device Selection
    private val _activeDeviceId = MutableStateFlow<String?>(null)
    val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

    // Session Map exposing StateFlow
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState
    val deviceSessions: StateFlow<Map<String, UsbSessionState>> = sessionsState

    // Active Device Explorer Views
    private val _activeRemoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val activeRemoteFiles: StateFlow<List<RemoteFile>> = _activeRemoteFiles.asStateFlow()
    val remoteFiles: StateFlow<List<RemoteFile>> = activeRemoteFiles

    private val _activeCurrentPath = MutableStateFlow<String>("/sdcard")
    val activeCurrentPath: StateFlow<String> = _activeCurrentPath.asStateFlow()
    val currentRemotePath: StateFlow<String> = activeCurrentPath

    private val _state = MutableStateFlow("Idle")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState.asStateFlow()

    private val _isPhysicallyConnected = MutableStateFlow(false)
    val isPhysicallyConnected: StateFlow<Boolean> = _isPhysicallyConnected.asStateFlow()

    private val _physicallyConnectedDeviceName = MutableStateFlow<String?>("No Device")
    val physicallyConnectedDeviceName: StateFlow<String?> = _physicallyConnectedDeviceName.asStateFlow()

    val tabSwitchCount = AtomicInteger(0)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        // Start USB Session Manager device polling
        sessionManager.startPolling()

        // Observe session map changes to auto-select and sync state
        scope.launch {
            sessionsState.collect { map ->
                _isPhysicallyConnected.value = map.isNotEmpty()
                val currentActive = _activeDeviceId.value

                if (currentActive == null || currentActive !in map) {
                    val nextDevice = map.keys.firstOrNull()
                    _activeDeviceId.value = nextDevice
                    if (nextDevice != null) {
                        _physicallyConnectedDeviceName.value = map[nextDevice]?.deviceName ?: nextDevice
                        updateActiveExplorerView(nextDevice)
                    } else {
                        _physicallyConnectedDeviceName.value = "No Device"
                        _activeRemoteFiles.value = emptyList()
                        _state.value = "Idle"
                    }
                } else {
                    _physicallyConnectedDeviceName.value = map[currentActive]?.deviceName ?: currentActive
                    updateActiveExplorerView(currentActive)
                }
            }
        }
    }

    val isAoaMode: Boolean
        get() {
            val id = activeDeviceId.value ?: return false
            return sessionsState.value[id]?.isAoaMode ?: false
        }

    fun selectDevice(deviceId: String) {
        tabSwitchCount.incrementAndGet()
        _activeDeviceId.value = deviceId
        updateActiveExplorerView(deviceId)
    }

    fun getSidebarRenderedDeviceIds(): List<String> {
        return sessionsState.value.keys.toList()
    }

    private fun updateActiveExplorerView(deviceId: String) {
        val sessionState = sessionsState.value[deviceId]
        if (sessionState != null) {
            _activeCurrentPath.value = sessionState.currentRemotePath
            _activeRemoteFiles.value = sessionState.remoteFiles
            _progressState.value = sessionState.progress
            _state.value = when (sessionState.status) {
                is DeviceSessionStatus.Ready -> "Ready"
                is DeviceSessionStatus.Connecting -> "Connecting..."
                is DeviceSessionStatus.Disconnected -> "Idle"
                is DeviceSessionStatus.Error -> (sessionState.status as DeviceSessionStatus.Error).message
            }
        } else {
            _activeRemoteFiles.value = emptyList()
            _state.value = "Idle"
        }
    }

    fun sendFiles(deviceId: String, files: List<File>, targetPath: String = "/sdcard"): Job {
        val job = scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            session.sessionMutex.withLock {
                executeSendFilesInternal(session, files, targetPath)
            }
            if (_activeDeviceId.value == deviceId) {
                updateActiveExplorerView(deviceId)
            }
        }
        activeJobs[deviceId] = job
        return job
    }

    fun sendFiles(files: List<File>, targetPath: String? = null): Job {
        val targetDev = activeDeviceId.value ?: return scope.launch { }
        val destination = targetPath ?: _activeCurrentPath.value
        return sendFiles(targetDev, files, destination)
    }

    private suspend fun executeSendFilesInternal(session: UsbSession, files: List<File>, destinationPath: String) {
        val allItems = mutableListOf<Pair<File, String>>()
        fun flatten(file: File, remoteParent: String) {
            if (file.isDirectory) {
                val remoteDir = if (remoteParent.endsWith("/")) remoteParent + file.name else "$remoteParent/${file.name}"
                allItems.add(Pair(file, remoteDir))
                file.listFiles()?.forEach { flatten(it, remoteDir) }
            } else {
                allItems.add(Pair(file, remoteParent))
            }
        }
        files.forEach { flatten(it, destinationPath) }

        val total = allItems.size
        val batchStartTime = System.currentTimeMillis()
        val queueNames = allItems.map { it.first.name }

        session.updateProgress(
            TransferProgress(
                isVisible = true,
                totalFiles = total,
                isComplete = false,
                queue = queueNames
            )
        )
        if (_activeDeviceId.value == session.deviceId) {
            _progressState.value = session.sessionState.value.progress
        }

        for ((index, item) in allItems.withIndex()) {
            if (!scope.isActive) break
            val (file, remoteParentOrPath) = item
            session.updateProgress(session.sessionState.value.progress.copy(currentFileIndex = index + 1))

            if (file.isDirectory) {
                try {
                    session.repository.createFolder(remoteParentOrPath)
                } catch (e: Exception) {
                    println("[$TAG] Error creating folder ${file.name}: ${e.message}")
                }
            } else {
                transferSingleFileInternal(session, file, remoteParentOrPath, batchStartTime)
            }
        }

        session.updateProgress(session.sessionState.value.progress.copy(isComplete = true, statusMessage = "Transfer Complete"))
        refreshRemoteFilesInternal(session)
    }

    private suspend fun transferSingleFileInternal(session: UsbSession, file: File, destinationPath: String, batchStartTime: Long) {
        val startTime = System.currentTimeMillis()
        val fileSize = file.length()

        session.updateProgress(
            session.sessionState.value.progress.copy(
                isVisible = true,
                filename = file.name,
                total = if (file.isDirectory) "Directory" else SecureQtSdk.Utils.formatSize(fileSize),
                percentage = 0,
                speed = "0 B/s",
                transferred = "0 B",
                eta = "Calculating...",
                elapsed = "0s",
                statusMessage = "Sending ${file.name}..."
            )
        )

        try {
            session.repository.sendFile(file, destinationPath, isDirectory = file.isDirectory).collect { progress ->
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - startTime) / 1000L
                val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                val transferredBytes = if (file.isDirectory) 0L else (fileSize * progress) / 100
                val speedBytesPerSec = if (elapsedSeconds > 0 && !file.isDirectory) (transferredBytes / elapsedSeconds) else 0L
                val speed = if (file.isDirectory) "Calculating..." else SecureQtSdk.Utils.formatSize(speedBytesPerSec) + "/s"
                val eta = if (speedBytesPerSec > 0) {
                    val remainingBytes = fileSize - transferredBytes
                    val remainingSeconds = remainingBytes / speedBytesPerSec
                    SecureQtSdk.Utils.formatTime(remainingSeconds)
                } else "Calculating..."

                session.updateProgress(
                    session.sessionState.value.progress.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = if (file.isDirectory) "Processing..." else SecureQtSdk.Utils.formatSize(transferredBytes),
                        eta = eta,
                        elapsed = SecureQtSdk.Utils.formatTime(elapsedSeconds),
                        batchElapsed = SecureQtSdk.Utils.formatTime(batchElapsedSeconds)
                    )
                )
                if (_activeDeviceId.value == session.deviceId) {
                    _progressState.value = session.sessionState.value.progress
                }
            }
        } catch (e: Exception) {
            session.updateProgress(session.sessionState.value.progress.copy(statusMessage = "Error: ${e.message}", isComplete = true))
        }
    }

    fun fetchFiles(deviceId: String, remoteFiles: List<RemoteFile>, localDir: File): Job {
        val job = scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            session.sessionMutex.withLock {
                executeFetchFilesInternal(session, remoteFiles, localDir)
            }
        }
        activeJobs[deviceId] = job
        return job
    }

    fun fetchFiles(remoteFiles: List<RemoteFile>): Job {
        val targetDev = activeDeviceId.value ?: return scope.launch { }
        val downloadDir = File(System.getProperty("user.home"), "Downloads")
        return fetchFiles(targetDev, remoteFiles, downloadDir)
    }

    private suspend fun executeFetchFilesInternal(session: UsbSession, remoteFiles: List<RemoteFile>, localDir: File) {
        localDir.mkdirs()
        val total = remoteFiles.size
        val batchStartTime = System.currentTimeMillis()
        val queueNames = remoteFiles.map { it.name }

        session.updateProgress(
            TransferProgress(
                isVisible = true,
                totalFiles = total,
                isComplete = false,
                queue = queueNames
            )
        )

        for ((index, remoteFile) in remoteFiles.withIndex()) {
            if (!scope.isActive) break
            session.updateProgress(session.sessionState.value.progress.copy(currentFileIndex = index + 1))
            val localFile = File(localDir, remoteFile.name)

            val startTime = System.currentTimeMillis()
            val fileSize = remoteFile.size

            val flow = if (remoteFile.isDirectory) session.repository.fetchDirectory(remoteFile.path, localFile)
                       else session.repository.fetchFile(remoteFile.path, localFile)

            flow.collect { progress ->
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - startTime) / 1000L
                val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                val transferredBytes = if (remoteFile.isDirectory) 0L else (fileSize * progress) / 100
                val speedBytesPerSec = if (elapsedSeconds > 0 && !remoteFile.isDirectory) (transferredBytes / elapsedSeconds) else 0L
                val speed = if (remoteFile.isDirectory) "Calculating..." else SecureQtSdk.Utils.formatSize(speedBytesPerSec) + "/s"
                val eta = if (speedBytesPerSec > 0) {
                    val remainingBytes = fileSize - transferredBytes
                    val remainingSeconds = remainingBytes / speedBytesPerSec
                    SecureQtSdk.Utils.formatTime(remainingSeconds)
                } else "Calculating..."

                session.updateProgress(
                    session.sessionState.value.progress.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = if (remoteFile.isDirectory) "Processing..." else SecureQtSdk.Utils.formatSize(transferredBytes),
                        eta = eta,
                        elapsed = SecureQtSdk.Utils.formatTime(elapsedSeconds),
                        batchElapsed = SecureQtSdk.Utils.formatTime(batchElapsedSeconds)
                    )
                )
                if (_activeDeviceId.value == session.deviceId) {
                    _progressState.value = session.sessionState.value.progress
                }
            }
        }
        session.updateProgress(session.sessionState.value.progress.copy(isComplete = true, statusMessage = "Fetch Complete"))
    }

    fun refreshRemoteFiles(deviceId: String? = activeDeviceId.value, remotePath: String? = null): Job {
        return scope.launch {
            val id = deviceId ?: return@launch
            val session = sessionManager.getSession(id) ?: return@launch
            session.sessionMutex.withLock {
                val path = remotePath ?: session.sessionState.value.currentRemotePath
                session.updateCurrentPath(path)
                refreshRemoteFilesInternal(session)
            }
        }
    }

    fun refreshRemoteFiles(): Job = refreshRemoteFiles(activeDeviceId.value, null)

    private suspend fun refreshRemoteFilesInternal(session: UsbSession) {
        try {
            val path = session.sessionState.value.currentRemotePath
            val files = session.repository.listDirectory(path)
            session.updateRemoteFiles(files)
            if (_activeDeviceId.value == session.deviceId) {
                _activeRemoteFiles.value = files
            }
        } catch (e: Exception) {
            println("[$TAG] Error listing directory for ${session.deviceId}: ${e.message}")
        }
    }

    fun createFolder(deviceId: String, folderName: String): Job {
        return scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            session.sessionMutex.withLock {
                val currentPath = session.sessionState.value.currentRemotePath
                val newPath = "$currentPath/$folderName".replace("//", "/")
                session.repository.createFolder(newPath)
                refreshRemoteFilesInternal(session)
            }
        }
    }

    fun createFolder(folderName: String): Job {
        val targetDev = activeDeviceId.value ?: return scope.launch { }
        return createFolder(targetDev, folderName)
    }

    fun deleteFile(deviceId: String, remoteFile: RemoteFile): Job {
        return scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            session.sessionMutex.withLock {
                session.repository.deleteFile(remoteFile.path)
                refreshRemoteFilesInternal(session)
            }
        }
    }

    fun deleteFile(remoteFile: RemoteFile): Job {
        val targetDev = activeDeviceId.value ?: return scope.launch { }
        return deleteFile(targetDev, remoteFile)
    }

    fun renameFile(remoteFile: RemoteFile, newName: String): Job {
        val targetDev = activeDeviceId.value ?: return scope.launch { }
        return scope.launch {
            val session = sessionManager.getSession(targetDev) ?: return@launch
            session.sessionMutex.withLock {
                session.repository.renameFile(remoteFile.path, newName)
                refreshRemoteFilesInternal(session)
            }
        }
    }

    fun cancelTransfer(deviceId: String) {
        activeJobs[deviceId]?.cancel()
        activeJobs.remove(deviceId)
        val session = sessionManager.getSession(deviceId)
        session?.updateProgress(TransferProgress(isComplete = true, statusMessage = "Cancelled"))
        if (_activeDeviceId.value == deviceId) {
            _progressState.value = TransferProgress()
            _state.value = "Transfer Cancelled ❌"
        }
    }

    fun cancelTransfer() {
        activeDeviceId.value?.let { cancelTransfer(it) }
    }

    fun dismissProgress() {
        _progressState.value = TransferProgress()
        activeDeviceId.value?.let { id ->
            sessionManager.getSession(id)?.updateProgress(TransferProgress())
        }
    }

    fun navigateTo(file: RemoteFile) {
        if (file.isDirectory) {
            val id = activeDeviceId.value ?: return
            val session = sessionManager.getSession(id) ?: return
            session.updateCurrentPath(file.path)
            _activeCurrentPath.value = file.path
            refreshRemoteFiles(id, file.path)
        }
    }

    fun navigateUp() {
        val current = _activeCurrentPath.value
        if (current == "/" || current == "/sdcard") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        val nextPath = if (parent.isEmpty()) "/" else parent
        val id = activeDeviceId.value ?: return
        val session = sessionManager.getSession(id) ?: return
        session.updateCurrentPath(nextPath)
        _activeCurrentPath.value = nextPath
        refreshRemoteFiles(id, nextPath)
    }

    fun connect() {
        val id = activeDeviceId.value ?: return
        scope.launch {
            sessionManager.getSession(id)?.connect()
        }
    }

    fun disconnect() {
        val id = activeDeviceId.value ?: return
        sessionManager.getSession(id)?.disconnect()
    }

    fun checkRemoteFileExists(targetFolder: String, fileName: String, onResult: (Boolean) -> Unit) {
        val id = activeDeviceId.value ?: run {
            onResult(false)
            return
        }
        scope.launch {
            val session = sessionManager.getSession(id) ?: return@launch
            val exists = session.sessionMutex.withLock {
                try {
                    val files = session.repository.listDirectory(targetFolder)
                    files.any { it.name == fileName }
                } catch (e: Exception) {
                    false
                }
            }
            withContext(Dispatchers.Main) {
                onResult(exists)
            }
        }
    }

    fun sendTextAsRemoteFile(fileName: String, content: String, targetFolder: String) {
        val id = activeDeviceId.value ?: return
        scope.launch {
            val tempFile = File.createTempFile("smartnav_", "_$fileName")
            tempFile.writeText(content)
            sendFiles(id, listOf(tempFile), targetFolder).join()
            tempFile.delete()
        }
    }

    fun prepareLocalSmartNavStaging(stagingDir: File, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            val foldersAndFiles = listOf(
                Pair(Constants.SmartnavRoot.DIR_PASSWORD, Pair(Constants.SmartnavRoot.FILE_PASSWORD, Constants.SmartnavRoot.DEFAULT_PASSWORD_VALUE)),
                Pair(Constants.SmartnavRoot.DIR_PASSWORD, Pair(Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD, Constants.SmartnavRoot.DEFAULT_MAINTENANCE_PASSWORD_VALUE)),
                Pair(Constants.SmartnavRoot.DIR_PASSWORD, Pair(Constants.SmartnavRoot.FILE_KMM_PASSWORD, Constants.SmartnavRoot.DEFAULT_KMM_PASSWORD_VALUE)),
                Pair("${Constants.SmartnavRoot.DIR_TRACKS}/${Constants.SmartnavRoot.DIR_TRACKS_META}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_TRACE, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_IMEI, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("updateApp", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_FIRMWARE_UPGRADE, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_RASTER}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_VECTOR}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair("${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_ICONS}", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_DATABASE, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")),
                Pair(Constants.SmartnavRoot.DIR_GNSS_DATA_LOGS, Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, ""))
            )
            for ((folder, filePair) in foldersAndFiles) {
                val (fileName, text) = filePair
                val dir = File(stagingDir, folder)
                dir.mkdirs()
                val file = File(dir, fileName)
                if (!file.exists()) {
                    file.writeText(text)
                }
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
```

---

## 6. Summary of Architectural Verification & Pass Criteria

- **Dependency Injection**: Registered via `appModule.kt` single binding `single { MainViewModel(get()) }`.
- **E2E Test Compatibility**: Matches `FakeMainViewModel` and test expectations across Tiers 1–4.
- **Per-Device Concurrency**: Uses `session.sessionMutex` per device; background transfers run in parallel without blocking tab switching or peer transfers.
