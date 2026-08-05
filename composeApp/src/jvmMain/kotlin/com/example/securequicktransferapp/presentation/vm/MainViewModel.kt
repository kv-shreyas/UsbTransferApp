package com.example.securequicktransferapp.presentation.vm

import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.usecases.UsbUseCases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(
    private val usbUseCases: UsbUseCases,
    private val usbRepository: com.example.securequicktransferapp.domain.repo.UsbRepository
) {
    val TAG = "MainViewModel"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val usbMutex = Mutex()

    private val _state = MutableStateFlow("Idle")
    val state: StateFlow<String> = _state

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/sdcard")
    val currentRemotePath: StateFlow<String> = _currentRemotePath



    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState

    private val _isPhysicallyConnected = MutableStateFlow(false)
    val isPhysicallyConnected: StateFlow<Boolean> = _isPhysicallyConnected

    private val _physicallyConnectedDeviceName = MutableStateFlow<String?>("No Device")
    val physicallyConnectedDeviceName: StateFlow<String?> = _physicallyConnectedDeviceName

    init {
        startPhysicalConnectionMonitor()
    }

    private var physicalMonitorJob: kotlinx.coroutines.Job? = null

    private fun startPhysicalConnectionMonitor() {
        physicalMonitorJob?.cancel()
        physicalMonitorJob = scope.launch {
            while (isActive) {
                try {
                    val (connected, name) = usbRepository.checkPhysicalConnection()
                    val wasConnected = _isPhysicallyConnected.value
                    _isPhysicallyConnected.value = connected
                    _physicallyConnectedDeviceName.value = name ?: "No Device"

                    if (!connected && wasConnected) {
                        println($$"$$TAG USB cable physically unplugged from Desktop! Auto-disconnecting...")
                        if (_state.value != "Idle" && _state.value != "Searching..." && !_state.value.contains("Disconnect")) {
                            disconnect()
                        }
                    } else if (!connected && _state.value != "Idle" && _state.value != "Searching..." && !_state.value.contains("Disconnect") && !_state.value.contains("Failed") && !_state.value.contains("Connection Lost")) {
                        println($$"$$TAG USB device no longer present on bus! Auto-disconnecting...")
                        disconnect()
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    val isAoaMode: Boolean
        get() = usbRepository.isAoaMode

    private var transferJob: kotlinx.coroutines.Job? = null

    fun dismissProgress() {
        _progressState.value = TransferProgress()
    }

    fun cancelTransfer() {
        println($$"$$TAG Cancelling transfer job locally...")
        transferJob?.cancel()
        transferJob = null
        
        // Immediately dismiss the UI to prevent hanging feeling
        _progressState.value = TransferProgress()
        _state.value = "Transfer Cancelled ❌"
        
        scope.launch {
            // Pause monitor so it doesn't steal the lock while we abort
            connectionMonitorJob?.cancel()
            
            usbMutex.withLock {
                try {
                    usbUseCases.cancelTransfer()
                } catch (e: Exception) {
                    println($$"$$TAG Cancel transfer error: ${e.message}")
                }
            }
            println($$"$$TAG Transfer cancelled successfully.")
        }
    }

    private var connectionMonitorJob: kotlinx.coroutines.Job? = null

    fun connect() {
        if (_state.value == "Searching..." || _state.value == "Connecting...") return
        scope.launch {
            // Ensure any previous background jobs are cleanly cancelled
            transferJob?.cancel()
            connectionMonitorJob?.cancel()
            transferJob = null
            connectionMonitorJob = null

            usbMutex.withLock {
                println($$"$$TAG Attempting to connect to USB device...")
                _state.value = "Searching..."
                val success = usbUseCases.connectUsb()
                _state.value = if (success) "Ready" else "Connection Failed"
                println($$"$$TAG Connection status: success=$success")
                if (success) {
                    refreshRemoteFilesInternal()
                    startConnectionMonitor()
                }
            }
        }
    }

    private fun startConnectionMonitor() {
        // We removed the aggressive polling (which fetched the entire directory every 2 seconds)
        // because it causes the Android application to get overwhelmed and crash,
        // thereby dropping the AOA connection unexpectedly.
    }

    fun disconnect() {
        if (_state.value == "Idle" || _state.value == "Disconnecting..." || _state.value.contains("Disconnected")) return
        println($$"$$TAG Force disconnecting device...")
        _state.value = "Disconnecting..."
        transferJob?.cancel()
        connectionMonitorJob?.cancel()
        
        scope.launch {
            try {
                usbUseCases.disconnectUsb()
            } catch (e: Exception) {
                println($$"$$TAG Disconnect error: ${e.message}")
            } finally {
                _remoteFiles.value = emptyList()
                _currentRemotePath.value = "/sdcard"
                _state.value = "Idle"
                _progressState.value = TransferProgress()
            }
        }
    }

    fun refreshRemoteFiles() {
        scope.launch {
            usbMutex.withLock {
                refreshRemoteFilesInternal()
            }
        }
    }

    private suspend fun refreshRemoteFilesInternal() {
        try {
            println($$"$$TAG Listing directory: ${_currentRemotePath.value}")
            _state.value = "Listing ${_currentRemotePath.value}..."
            val files = usbUseCases.listDirectory(_currentRemotePath.value)
            
            if (!usbRepository.checkPhysicalConnection().first) {
                println($$"$$TAG Device disconnected during listDirectory. Aborting refresh.")
                return
            }
            
            _remoteFiles.value = files
            _state.value = "Ready"
            println($$"$$TAG Successfully listed ${files.size} items.")
        } catch (e: Exception) {
            println($$"$$TAG Error listing directory: ${e.message}")
            _state.value = "Error: ${e.message}"
        }
    }

    fun navigateTo(file: RemoteFile) {
        if (file.isDirectory) {
            println($$"$$TAG Navigating into directory: ${file.path}")
            _currentRemotePath.value = file.path
            refreshRemoteFiles()
        }
    }

    fun navigateUp() {
        val current = _currentRemotePath.value
        if (current == "/" || current == "/sdcard") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        // If parent is empty or somehow goes above sdcard when restricted, clamp it (though our root is / or /sdcard)
        val nextPath = if (parent.isEmpty()) "/" else parent
        
        println($$"$$TAG Navigating up from $current to $nextPath")
        _currentRemotePath.value = nextPath
        refreshRemoteFiles()
    }

    fun sendFiles(files: List<File>, targetPath: String? = null) {
        val destinationPath = targetPath ?: _currentRemotePath.value
        transferJob = scope.launch {
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
            
            _progressState.value = _progressState.value.copy(
                isVisible = true, 
                totalFiles = total, 
                isComplete = false,
                queue = queueNames
            )
            
            for ((index, item) in allItems.withIndex()) {
                if (!isActive) break
                val (file, remoteParentOrPath) = item
                _progressState.value = _progressState.value.copy(currentFileIndex = index + 1)
                
                if (file.isDirectory) {
                    usbMutex.withLock {
                        _state.value = "Creating Folder: ${file.name}..."
                        try {
                            usbUseCases.createFolder(remoteParentOrPath)
                            _state.value = "Folder Created: ${file.name}"
                        } catch (e: Exception) {
                            if (e !is kotlinx.coroutines.CancellationException) {
                                println($$"$$TAG Error creating folder: ${e.message}")
                            }
                        }
                    }
                } else {
                    transferSingleFile(file, remoteParentOrPath, batchStartTime)
                }
            }
            
            if (isActive) {
                _progressState.value = _progressState.value.copy(isComplete = true, statusMessage = "Transfer Complete")
                refreshRemoteFiles()
            }
        }
    }

    private suspend fun transferSingleFile(file: File, destinationPath: String, batchStartTime: Long = System.currentTimeMillis()) {
        usbMutex.withLock {
            println($$"$$TAG Sending file/directory: ${file.name} to $destinationPath")
            _state.value = "Sending: ${file.name}..."

            val startTime = System.currentTimeMillis()
            val fileSize = file.length()
            
            _progressState.value = _progressState.value.copy(
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

            try {
                usbUseCases.sendFile(file, destinationPath, isDirectory = file.isDirectory).collect { progress -> 
                    _state.value = "Sending: $progress%" 
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

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = if (file.isDirectory) "Processing..." else SecureQtSdk.Utils.formatSize(transferredBytes),
                        eta = eta,
                        elapsed = SecureQtSdk.Utils.formatTime(elapsedSeconds),
                        batchElapsed = SecureQtSdk.Utils.formatTime(batchElapsedSeconds)
                    )
                }
                _state.value = "Sent Successfully ✅"
                println($$"$$TAG File/Directory sent successfully: ${file.name}")
                refreshRemoteFilesInternal()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _state.value = "Error: ${e.message}"
                    _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                }
            }
        }
    }

    fun fetchFiles(remoteFiles: List<RemoteFile>) {
        transferJob = scope.launch {
            val total = remoteFiles.size
            val batchStartTime = System.currentTimeMillis()
            val queueNames = remoteFiles.map { it.name }
            _progressState.value = _progressState.value.copy(
                isVisible = true, 
                totalFiles = total, 
                isComplete = false,
                queue = queueNames
            )
            for ((index, remoteFile) in remoteFiles.withIndex()) {
                if (!isActive) break
                _progressState.value = _progressState.value.copy(currentFileIndex = index + 1)
                executeFetch(remoteFile, batchStartTime)
            }
            if (isActive) {
                _progressState.value = _progressState.value.copy(isComplete = true, statusMessage = "Fetch Complete")
            }
        }
    }

    private suspend fun executeFetch(remoteFile: RemoteFile, batchStartTime: Long = System.currentTimeMillis()) {
        val downloadDir = File(System.getProperty("user.home"), "Downloads")
        downloadDir.mkdirs()
        val localFile = File(downloadDir, remoteFile.name)

        usbMutex.withLock {
            println($$"$$TAG Fetching: ${remoteFile.name} (size: ${remoteFile.size} bytes)")
            _state.value = "Fetching: ${remoteFile.name}..."

            val startTime = System.currentTimeMillis()
            val fileSize = remoteFile.size
            
            _progressState.value = _progressState.value.copy(
                isVisible = true,
                filename = remoteFile.name,
                total = if (remoteFile.isDirectory) "Directory" else SecureQtSdk.Utils.formatSize(fileSize),
                percentage = 0,
                speed = "0 B/s",
                transferred = "0 B",
                eta = "Calculating...",
                elapsed = "0s",
                statusMessage = "Fetching ${remoteFile.name}..."
            )

            try {
                val flow = if (remoteFile.isDirectory) usbUseCases.fetchDirectory(remoteFile.path, localFile)
                           else usbUseCases.fetchFile(remoteFile.path, localFile)
                
                flow.collect { progress -> 
                    _state.value = "Fetching: $progress%" 
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

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = if (remoteFile.isDirectory) "Processing..." else SecureQtSdk.Utils.formatSize(transferredBytes),
                        eta = eta,
                        elapsed = SecureQtSdk.Utils.formatTime(elapsedSeconds),
                        batchElapsed = SecureQtSdk.Utils.formatTime(batchElapsedSeconds)
                    )
                }
                _state.value = "Fetched to ${localFile.absolutePath} ✅"
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _state.value = "Error: ${e.message}"
                    _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                }
            }
        }
    }


    fun deleteFile(remoteFile: RemoteFile) {
        scope.launch {
            usbMutex.withLock {
                if (_remoteFiles.value.none { it.path == remoteFile.path }) return@withLock
                
                _state.value = "Deleting ${remoteFile.name}..."
                try {
                    val success = usbRepository.deleteFile(remoteFile.path)
                    if (success) {
                        _state.value = "Deleted ${remoteFile.name}"
                        _remoteFiles.value = _remoteFiles.value.filter { it.path != remoteFile.path }
                        refreshRemoteFilesInternal()
                    } else {
                        _state.value = "Failed to delete ${remoteFile.name}"
                    }
                } catch (e: Exception) {
                    _state.value = "Error: ${e.message}"
                }
            }
        }
    }

    fun renameFile(remoteFile: RemoteFile, newName: String) {
        scope.launch {
            usbMutex.withLock {
                if (_remoteFiles.value.none { it.path == remoteFile.path }) return@withLock
                
                _state.value = "Renaming to $newName..."
                try {
                    val success = usbRepository.renameFile(remoteFile.path, newName)
                    if (success) {
                        _state.value = "Renamed successfully"
                        refreshRemoteFilesInternal()
                    } else {
                        _state.value = "Failed to rename"
                    }
                } catch (e: Exception) {
                    _state.value = "Error: ${e.message}"
                }
            }
        }
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        scope.launch {
            usbMutex.withLock {
                _state.value = "Creating folder: $folderName..."
                try {
                    val remotePath = "${_currentRemotePath.value}/$folderName".replace("//", "/")
                    val success = usbUseCases.createFolder(remotePath)
                    if (success) {
                        _state.value = "Folder created successfully"
                        refreshRemoteFilesInternal()
                    } else {
                        _state.value = "Failed to create folder"
                    }
                } catch (e: Exception) {
                    _state.value = "Error: ${e.message}"
                }
            }
        }
    }

    fun checkRemoteFileExists(targetFolder: String, fileName: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val exists = usbMutex.withLock {
                try {
                    val files = usbUseCases.listDirectory(targetFolder)
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
        scope.launch {
            usbMutex.withLock {
                println("[ViewModel] sendTextAsRemoteFile: Preparing to send $fileName to $targetFolder...")
                try {
                    val tempFile = File.createTempFile("smartnav_", "_$fileName")
                    tempFile.writeText(content)
                    val destinationPath = targetFolder.trimEnd('/')
                    usbUseCases.sendFile(tempFile, destinationPath, isDirectory = false, remoteFileName = fileName).collect { progress ->
                        _progressState.value = _progressState.value.copy(
                            isVisible = true,
                            filename = fileName,
                            percentage = progress,
                            statusMessage = "Sending $fileName... ($progress%)"
                        )
                    }
                    tempFile.delete()
                    _progressState.value = TransferProgress(isComplete = true, statusMessage = "Sent $fileName")
                    println("[ViewModel] sendTextAsRemoteFile: Successfully sent $fileName to $destinationPath.")
                    refreshRemoteFilesInternal()
                } catch (e: Exception) {
                    println("[ViewModel] sendTextAsRemoteFile: Error sending text file $fileName: ${e.message}")
                }
            }
        }
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
                Pair("updateApp", Pair(Constants.SmartnavRoot.FILE_KEEP_PLACEHOLDER, "")), // PATH_APP_UPDATE logic simplification
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
