package com.example.securequicktransferapp.presentation.vm

import com.example.securequicktransferapp.data.usb.TransferQueue
import com.example.securequicktransferapp.data.usb.UsbSession
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferItemStatus
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

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

    val stagingDirPath = MutableStateFlow(java.io.File(System.getProperty("user.home"), "Downloads/SmartNavStaging").absolutePath)

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

    fun disconnectAll() {
        println("$TAG Force disconnecting all connected devices...")
        val sessions = sessionManager.getActiveSessions().values
        for (session in sessions) {
            val id = session.deviceId
            // Avoid async TYPE_CANCEL racing with synchronous CMD_DISCONNECT during app teardown
            queueWatchers.remove(id)?.cancel()
            try {
                // Must be synchronous or blocking to ensure it finishes before exit, 
                // but we can't runBlocking easily in all environments, so we'll just runBlocking here
                kotlinx.coroutines.runBlocking {
                    session.disconnect()
                }
            } catch (e: Exception) {
                println("$TAG Disconnect all error for $id: ${e.message}")
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

    fun sendFiles(
        files: List<File>, 
        targetPath: String? = null,
        conflictResolution: com.example.securequicktransferapp.data.usb.ConflictResolution? = null
    ) {
        val session = selectedSession() ?: return
        val destinationPath = targetPath ?: session.sessionState.value.currentPath
        if (conflictResolution != null) {
            session.transferQueue.globalConflictResolution = conflictResolution
        }
        val overwrite = conflictResolution == com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE ||
                        conflictResolution == com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE_ALL
        println("$TAG Enqueuing ${files.size} file(s) to send → $destinationPath for device ${session.deviceId} (overwrite=$overwrite)")
        session.transferQueue.enqueueSendBatch(files, destinationPath, overwrite = overwrite)
        watchQueueCompletion(session)
    }

    private val calculateRemoteFolderSizesUseCase = com.example.securequicktransferapp.domain.usecase.CalculateRemoteFolderSizesUseCase()

    suspend fun calculateRemoteFolderSizes(deviceId: String, rootPath: String): Map<String, Long> {
        val session = sessionManager.getSession(deviceId) ?: return emptyMap()
        return calculateRemoteFolderSizesUseCase(session, rootPath)
    }

    fun checkCloneConflicts(
        files: List<File>, 
        targetDeviceIds: Set<String>, 
        defaultTargetPath: String, 
        onResult: (List<DeviceConflictInfo>) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val conflictingDetails = mutableListOf<DeviceConflictInfo>()
            
            // 1. Gather all local files and their expected remote parent directories ONCE
            val allLocalFiles = mutableListOf<Pair<File, String>>()
            fun flatten(file: File, remoteParent: String) {
                if (file.isDirectory) {
                    val remoteDir = if (remoteParent.endsWith("/")) remoteParent + file.name else "$remoteParent/${file.name}"
                    file.listFiles()?.forEach { flatten(it, remoteDir) }
                } else {
                    allLocalFiles.add(file to remoteParent)
                }
            }

            val updateAppFiles = files.filter { it.name == Constants.SmartnavRoot.DIR_APP_UPDATE }
            val updateOsFiles = files.filter { it.name == Constants.SmartnavRoot.DIR_OS_UPDATE }
            val standardFiles = files.filter { it.name != Constants.SmartnavRoot.DIR_APP_UPDATE && it.name != Constants.SmartnavRoot.DIR_OS_UPDATE }

            standardFiles.forEach { flatten(it, defaultTargetPath) }
            updateAppFiles.forEach { flatten(it, Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH) }
            updateOsFiles.forEach { flatten(it, Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH) }

            val groupedByDir = allLocalFiles.groupBy { it.second }
            println("$TAG checkCloneConflicts: Found ${allLocalFiles.size} total files across ${groupedByDir.size} remote directories to check.")

            // 2. Check each target device
            for (deviceId in targetDeviceIds) {
                val session = sessionManager.getSession(deviceId) ?: continue
                val deviceName = session.discoveredDevice.deviceName.take(20)
                val deviceFolders = mutableListOf<FolderConflictInfo>()
                
                session.sessionMutex.withLock {
                    for ((remoteDir, localFiles) in groupedByDir) {
                        try {
                            // Add a small delay to prevent overwhelming the Android AOA accessory
                            delay(100.milliseconds)
                            println("$TAG checkCloneConflicts: Listing remote directory $remoteDir for device $deviceId")
                            val remoteList = session.repository.listDirectory(remoteDir)
                            val folderConflicts = mutableListOf<String>()
                            for ((localFile, _) in localFiles) {
                                if (remoteList.any { !it.isDirectory && it.name == localFile.name }) {
                                    folderConflicts.add(localFile.name)
                                }
                            }
                            if (folderConflicts.isNotEmpty()) {
                                deviceFolders.add(FolderConflictInfo(remoteDir, folderConflicts))
                            }
                        } catch (e: Exception) {
                            println("$TAG checkCloneConflicts: Error listing $remoteDir for device $deviceId: ${e.message}")
                        }
                    }
                }
                
                if (deviceFolders.isNotEmpty()) {
                    conflictingDetails.add(DeviceConflictInfo(deviceName, deviceFolders))
                }
            }
            
            println("$TAG checkCloneConflicts: Check complete. Total conflicts found: ${conflictingDetails.size}")
            withContext(Dispatchers.Main) {
                onResult(conflictingDetails)
            }
        }
    }

    fun sendFilesToDevices(
        files: List<File>, 
        targetDeviceIds: Set<String>, 
        defaultTargetPath: String,
        conflictResolution: com.example.securequicktransferapp.data.usb.ConflictResolution? = null
    ) {
        if (files.isEmpty() || targetDeviceIds.isEmpty()) return
        targetDeviceIds.forEach { deviceId ->
            val session = sessionManager.getSession(deviceId) ?: return@forEach
            
            if (conflictResolution != null) {
                session.transferQueue.globalConflictResolution = conflictResolution
            }
            
            val updateAppFiles = files.filter { it.name == Constants.SmartnavRoot.DIR_APP_UPDATE }
            val updateOsFiles = files.filter { it.name == Constants.SmartnavRoot.DIR_OS_UPDATE }
            val standardFiles = files.filter { it.name != Constants.SmartnavRoot.DIR_APP_UPDATE && it.name != Constants.SmartnavRoot.DIR_OS_UPDATE }
            
            val overwrite = conflictResolution == com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE ||
                            conflictResolution == com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE_ALL

            if (standardFiles.isNotEmpty()) {
                println("$TAG [Multi-Clone] Enqueuing ${standardFiles.size} standard file(s) to send → $defaultTargetPath for device $deviceId (overwrite=$overwrite)")
                session.transferQueue.enqueueSendBatch(standardFiles, defaultTargetPath, overwrite = overwrite)
            }
            
            if (updateAppFiles.isNotEmpty()) {
                println("$TAG [Multi-Clone] Enqueuing ${updateAppFiles.size} file(s) to send → /sdcard for device $deviceId (overwrite=$overwrite)")
                session.transferQueue.enqueueSendBatch(updateAppFiles, Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH, overwrite = overwrite)
            }
            
            if (updateOsFiles.isNotEmpty()) {
                println("$TAG [Multi-Clone] Enqueuing ${updateOsFiles.size} file(s) to send → /sdcard for device $deviceId (overwrite=$overwrite)")
                session.transferQueue.enqueueSendBatch(updateOsFiles, Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH, overwrite = overwrite)
            }

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

    fun fetchFiles(remoteFiles: List<RemoteFile>, destinationDir: java.io.File = java.io.File(System.getProperty("user.home"), "Downloads")) {
        val session = selectedSession() ?: return
        println("$TAG Enqueuing ${remoteFiles.size} file(s) to fetch from device ${session.deviceId} to ${destinationDir.absolutePath}")
        session.transferQueue.enqueueFetchBatch(remoteFiles, destinationDir)
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

    fun sendTextAsRemoteFile(fileName: String, content: String, targetFolder: String, overwrite: Boolean = false) {
        val session = selectedSession() ?: return
        println("[ViewModel] sendTextAsRemoteFile: Enqueuing $fileName to $targetFolder for device ${session.deviceId}...")
        val tempDir = java.nio.file.Files.createTempDirectory("smartnav_").toFile()
        val tempFile = File(tempDir, fileName)
        tempFile.writeText(content)
        val destinationPath = targetFolder.trimEnd('/')
        session.transferQueue.enqueueSend(tempFile, destinationPath, isDirectory = false, overwrite = overwrite)
        watchQueueCompletion(session)
    }

    private val _editingFileContent = MutableStateFlow<String?>(null)
    val editingFileContent: StateFlow<String?> = _editingFileContent

    private val _editingFileName = MutableStateFlow<String?>(null)
    val editingFileName: StateFlow<String?> = _editingFileName

    fun openFileForEditing(remoteFile: RemoteFile) {
        val session = selectedSession() ?: return
        scope.launch {
            try {
                val tempDir = java.nio.file.Files.createTempDirectory("edit_").toFile()
                tempDir.deleteOnExit()
                val dummyLocalFile = File(tempDir, remoteFile.name)
                
                session.sessionMutex.withLock {
                    session.repository.fetchFile(remoteFile.path, dummyLocalFile).collect { progress ->
                        if (progress == 100) {
                            val downloadedFile = File(tempDir, remoteFile.name)
                            if (downloadedFile.exists()) {
                                val content = downloadedFile.readText()
                                _editingFileName.value = remoteFile.name
                                _editingFileContent.value = content
                            } else {
                                println("$TAG Error: Downloaded file not found at ${downloadedFile.absolutePath}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("$TAG Error opening file for editing: ${e.message ?: e.toString()}")
            }
        }
    }

    fun saveEditedFile(fileName: String, content: String, targetFolder: String) {
        sendTextAsRemoteFile(fileName, content, targetFolder, overwrite = true)
        _editingFileName.value = null
        _editingFileContent.value = null
    }

    fun cancelEditing() {
        _editingFileName.value = null
        _editingFileContent.value = null
    }

    fun checkLocalSmartNavStagingConflicts(stagingDir: File): List<String> {
        val foldersAndFiles = Constants.SmartnavRoot.DEFAULT_FOLDERS_AND_FILES
        val conflicts = mutableListOf<String>()
        for ((folder, filePair) in foldersAndFiles) {
            val (fileName, _) = filePair
            val file = File(File(stagingDir, folder), fileName)
            if (file.exists()) {
                conflicts.add("${folder}/${fileName}")
            }
        }
        return conflicts
    }

    fun prepareLocalSmartNavStaging(stagingDir: File, overwrite: Boolean, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            println("[ViewModel] prepareLocalSmartNavStaging: Initializing SmartNavRoot hierarchy under ${stagingDir.absolutePath}...")
            kotlinx.coroutines.delay(600.milliseconds) // Artificial delay for intuitive UI feedback
            val foldersAndFiles = Constants.SmartnavRoot.DEFAULT_FOLDERS_AND_FILES
            for ((folder, filePair) in foldersAndFiles) {
                val (fileName, content) = filePair
                val dir = File(stagingDir, folder)
                dir.mkdirs()
                val file = File(dir, fileName)
                if (!file.exists() || overwrite) {
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
data class FolderConflictInfo(val folderPath: String, val files: List<String>)
data class DeviceConflictInfo(val deviceName: String, val folders: List<FolderConflictInfo>)
