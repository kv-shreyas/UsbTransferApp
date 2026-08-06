package com.example.securequicktransferapp.presentation.vm

import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.data.usb.TransferQueue
import com.example.securequicktransferapp.data.usb.UsbSession
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferItemStatus
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.TransferQueueItem
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val sessionManager: UsbSessionManager
) {
    val TAG = "MainViewModel"
    private val scope = CoroutineScope(Dispatchers.IO)

    // --- Multi-device state ---
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId

    /** All active device sessions (deviceId -> UsbSessionState) */
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState

    // Per-device queue completion watchers
    private val queueWatchers = ConcurrentHashMap<String, Job>()

    // Track devices that have had their initial file list fetched
    private val initializedDevices = ConcurrentHashMap.newKeySet<String>()

    init {
        sessionManager.startPolling()
        scope.launch {
            sessionsState.collect { sessions ->
                // Auto-select the first device when it appears
                if (_selectedDeviceId.value == null && sessions.isNotEmpty()) {
                    _selectedDeviceId.value = sessions.keys.first()
                }
                // If the selected device was unplugged, clear selection or pick another
                if (_selectedDeviceId.value != null && _selectedDeviceId.value !in sessions) {
                    _selectedDeviceId.value = sessions.keys.firstOrNull()
                }

                // Clean up disconnected devices from initialization tracking
                initializedDevices.retainAll(sessions.keys)

                // Auto-fetch files for newly ready devices
                sessions.forEach { (id, state) ->
                    if (state.status is DeviceSessionStatus.Ready && !initializedDevices.contains(id)) {
                        initializedDevices.add(id)
                        val session = sessionManager.getSession(id)
                        if (session != null) {
                            scope.launch {
                                session.sessionMutex.withLock {
                                    refreshRemoteFilesInternal(session)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Queue state flows ---

    /** Whether the selected device's queue is actively processing */
    val isQueueProcessing: StateFlow<Boolean> =
        selectedDeviceId.flatMapLatest { id ->
            if (id != null) {
                sessionManager.getSession(id)?.transferQueue?.isProcessing ?: flowOf(false)
            } else {
                flowOf(false)
            }
        }.stateIn(scope, SharingStarted.Eagerly, false)

    // --- Convenience derived flows for UI backward compat ---

    private fun selectedSessionState(): StateFlow<UsbSessionState?> =
        combine(selectedDeviceId, sessionsState) { id, sessions ->
            if (id != null) sessions[id] else null
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _selectedState = selectedSessionState()

    val state: StateFlow<String> = _selectedState.map { ss ->
        when (ss?.status) {
            is DeviceSessionStatus.Disconnected -> "Idle"
            is DeviceSessionStatus.Connecting -> "Searching..."
            is DeviceSessionStatus.Ready -> "Ready"
            is DeviceSessionStatus.Error -> "Connection Failed"
            null -> "Idle"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Idle")

    val remoteFiles: StateFlow<List<RemoteFile>> = _selectedState.map { ss ->
        ss?.remoteFiles ?: emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val currentRemotePath: StateFlow<String> = _selectedState.map { ss ->
        ss?.currentPath ?: "/sdcard"
    }.stateIn(scope, SharingStarted.Eagerly, "/sdcard")

    val progressState: StateFlow<TransferProgress> = _selectedState.map { ss ->
        ss?.progress ?: TransferProgress()
    }.stateIn(scope, SharingStarted.Eagerly, TransferProgress())

    val isPhysicallyConnected: StateFlow<Boolean> = sessionsState.map { sessions ->
        sessions.isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val physicallyConnectedDeviceName: StateFlow<String?> = _selectedState.map { ss ->
        ss?.deviceName ?: "No Device"
    }.stateIn(scope, SharingStarted.Eagerly, "No Device")

    val isAoaMode: Boolean
        get() = selectedSession()?.sessionState?.value?.isAoaMode ?: false

    // --- Device selection ---

    fun selectDevice(deviceId: String) {
        _selectedDeviceId.value = deviceId
    }

    private fun selectedSession(): UsbSession? {
        val id = _selectedDeviceId.value ?: return null
        return sessionManager.getSession(id)
    }

    // --- Transfer Queue management ---

    fun dismissProgress() {
        val session = selectedSession() ?: return
        session.updateProgress(TransferProgress())
    }

    fun cancelTransfer() {
        val session = selectedSession() ?: return
        val id = session.deviceId
        println("$TAG Cancelling all queued transfers for device $id...")
        session.transferQueue.cancelAll()
        session.updateProgress(TransferProgress())
        session.updateStatus(DeviceSessionStatus.Ready)
        println("$TAG All transfers cancelled for device $id.")
    }

    fun cancelQueueItem(itemId: String) {
        val session = selectedSession() ?: return
        session.transferQueue.cancelItem(itemId)
    }

    fun clearFinishedQueue() {
        val session = selectedSession() ?: return
        session.transferQueue.clearFinished()
    }

    fun moveQueueItemToFront(itemId: String) {
        val session = selectedSession() ?: return
        session.transferQueue.moveToFront(itemId)
    }

    /** Get the TransferQueue for a specific device (used by DeviceCard for inline progress) */
    fun getDeviceQueue(deviceId: String): TransferQueue? {
        return sessionManager.getSession(deviceId)?.transferQueue
    }

    fun connect() {
        val session = selectedSession() ?: return
        val currentStatus = session.sessionState.value.status
        if (currentStatus is DeviceSessionStatus.Connecting || currentStatus is DeviceSessionStatus.Ready) return
        scope.launch {
            val id = session.deviceId
            session.transferQueue.cancelAll()

            println("$TAG Attempting to connect to USB device $id...")
            val success = session.connect()
            println("$TAG Connection status for $id: success=$success")
            if (success) {
                refreshRemoteFilesInternal(session)
            }
        }
    }

    fun disconnect() {
        val session = selectedSession() ?: return
        val id = session.deviceId
        println("$TAG Force disconnecting device $id...")
        session.transferQueue.cancelAll()
        queueWatchers.remove(id)?.cancel()

        scope.launch {
            try {
                session.disconnect()
            } catch (e: Exception) {
                println("$TAG Disconnect error: ${e.message}")
            } finally {
                session.updateRemoteFiles(emptyList())
                session.updateCurrentPath("/sdcard")
                session.updateProgress(TransferProgress())
                session.updateStatus(DeviceSessionStatus.Disconnected)
            }
        }
    }

    fun refreshRemoteFiles() {
        val session = selectedSession() ?: return
        scope.launch {
            session.sessionMutex.withLock {
                refreshRemoteFilesInternal(session)
            }
        }
    }

    private suspend fun refreshRemoteFilesInternal(session: UsbSession) {
        try {
            val path = session.sessionState.value.currentPath
            println("$TAG Listing directory: $path for device ${session.deviceId}")
            session.updateStatus(DeviceSessionStatus.Ready)

            val files = session.repository.listDirectory(path)

            val (connected, _) = session.repository.checkPhysicalConnection()
            if (!connected) {
                println("$TAG Device ${session.deviceId} disconnected during listDirectory. Aborting refresh.")
                return
            }

            session.updateRemoteFiles(files)
            session.updateStatus(DeviceSessionStatus.Ready)
            println("$TAG Successfully listed ${files.size} items for device ${session.deviceId}.")
        } catch (e: Exception) {
            println("$TAG Error listing directory for device ${session.deviceId}: ${e.message}")
            session.updateStatus(DeviceSessionStatus.Error("Error: ${e.message}"))
        }
    }

    fun navigateTo(file: RemoteFile) {
        if (file.isDirectory) {
            val session = selectedSession() ?: return
            println("$TAG Navigating into directory: ${file.path}")
            session.updateCurrentPath(file.path)
            refreshRemoteFiles()
        }
    }

    fun navigateUp() {
        val session = selectedSession() ?: return
        val current = session.sessionState.value.currentPath
        if (current == "/" || current == "/sdcard") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        val nextPath = if (parent.isEmpty()) "/" else parent

        println("$TAG Navigating up from $current to $nextPath")
        session.updateCurrentPath(nextPath)
        refreshRemoteFiles()
    }

    fun sendFiles(files: List<File>, targetPath: String? = null) {
        val session = selectedSession() ?: return
        val destinationPath = targetPath ?: session.sessionState.value.currentPath
        println("$TAG Enqueuing ${files.size} file(s) to send → $destinationPath for device ${session.deviceId}")
        session.transferQueue.enqueueSendBatch(files, destinationPath)
        watchQueueCompletion(session)
    }

    fun sendFilesToDevices(files: List<File>, targetDeviceIds: Set<String>, targetPath: String) {
        if (files.isEmpty() || targetDeviceIds.isEmpty()) return
        targetDeviceIds.forEach { deviceId ->
            val session = sessionManager.getSession(deviceId) ?: return@forEach
            println("$TAG [Multi-Clone] Enqueuing ${files.size} file(s) to send → $targetPath for device $deviceId")
            session.transferQueue.enqueueSendBatch(files, targetPath)
            watchQueueCompletion(session)
        }
    }

    /**
     * Watches a device's queue for completion and auto-refreshes files when done.
     * Only one watcher per device — calling again replaces the old watcher.
     */
    private fun watchQueueCompletion(session: UsbSession) {
        val id = session.deviceId
        // Don't create duplicate watchers
        if (queueWatchers.containsKey(id)) return

        queueWatchers[id] = scope.launch {
            session.transferQueue.isProcessing.collect { processing ->
                if (!processing && session.transferQueue.queue.value.any {
                    it.status == TransferItemStatus.COMPLETED
                }) {
                    // Queue finished processing — refresh the file list
                    println("$TAG Queue completed for device $id, refreshing file list...")
                    session.sessionMutex.withLock {
                        refreshRemoteFilesInternal(session)
                    }
                    queueWatchers.remove(id)?.cancel()
                }
            }
        }
    }

    fun fetchFiles(remoteFiles: List<RemoteFile>) {
        val session = selectedSession() ?: return
        println("$TAG Enqueuing ${remoteFiles.size} file(s) to fetch from device ${session.deviceId}")
        session.transferQueue.enqueueFetchBatch(remoteFiles)
        watchQueueCompletion(session)
    }

    // executeFetch removed — now handled by TransferQueue

    fun deleteFile(remoteFile: RemoteFile) {
        val session = selectedSession() ?: return
        scope.launch {
            session.sessionMutex.withLock {
                if (session.sessionState.value.remoteFiles.none { it.path == remoteFile.path }) return@withLock

                try {
                    val success = session.repository.deleteFile(remoteFile.path)
                    if (success) {
                        session.updateRemoteFiles(session.sessionState.value.remoteFiles.filter { it.path != remoteFile.path })
                        refreshRemoteFilesInternal(session)
                    }
                } catch (e: Exception) {
                    session.updateStatus(DeviceSessionStatus.Error("Error: ${e.message}"))
                }
            }
        }
    }

    fun renameFile(remoteFile: RemoteFile, newName: String) {
        val session = selectedSession() ?: return
        scope.launch {
            session.sessionMutex.withLock {
                if (session.sessionState.value.remoteFiles.none { it.path == remoteFile.path }) return@withLock

                try {
                    val success = session.repository.renameFile(remoteFile.path, newName)
                    if (success) {
                        refreshRemoteFilesInternal(session)
                    }
                } catch (e: Exception) {
                    session.updateStatus(DeviceSessionStatus.Error("Error: ${e.message}"))
                }
            }
        }
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        val session = selectedSession() ?: return
        scope.launch {
            session.sessionMutex.withLock {
                try {
                    val remotePath = "${session.sessionState.value.currentPath}/$folderName".replace("//", "/")
                    val success = session.repository.createFolder(remotePath)
                    if (success) {
                        refreshRemoteFilesInternal(session)
                    }
                } catch (e: Exception) {
                    session.updateStatus(DeviceSessionStatus.Error("Error: ${e.message}"))
                }
            }
        }
    }

    fun checkRemoteFileExists(targetFolder: String, fileName: String, onResult: (Boolean) -> Unit) {
        val session = selectedSession() ?: run { onResult(false); return }
        scope.launch {
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
        val session = selectedSession() ?: return
        println("[ViewModel] sendTextAsRemoteFile: Enqueuing $fileName to $targetFolder for device ${session.deviceId}...")
        val tempDir = java.nio.file.Files.createTempDirectory("smartnav_").toFile()
        val tempFile = File(tempDir, fileName)
        tempFile.writeText(content)
        val destinationPath = targetFolder.trimEnd('/')
        session.transferQueue.enqueueSend(tempFile, destinationPath, isDirectory = false)
        watchQueueCompletion(session)
    }

    fun prepareLocalSmartNavStaging(stagingDir: File, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            println("[ViewModel] prepareLocalSmartNavStaging: Initializing SmartNavRoot hierarchy under ${stagingDir.absolutePath}...")
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
                val (fileName, content) = filePair
                val dir = File(stagingDir, folder)
                dir.mkdirs()
                val file = File(dir, fileName)
                if (!file.exists()) {
                    file.writeText(content)
                }
            }
            println("[ViewModel] prepareLocalSmartNavStaging: Done.")
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
