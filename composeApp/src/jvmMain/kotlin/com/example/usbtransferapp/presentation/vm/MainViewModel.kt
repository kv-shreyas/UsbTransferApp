package com.example.usbtransferapp.presentation.vm

import com.example.usbtransferapp.domain.usecases.*
import com.example.usbtransferapp.domain.model.RemoteFile
import com.example.usbtransferapp.data.converter.AnfToCsvConverter
import com.example.usbtransferapp.presentation.ui.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainViewModel(
    private val connectUseCase: ConnectUsbUseCase,
    private val disconnectUseCase: DisconnectUsbUseCase,
    private val receiveUseCase: ReceiveFileUseCase,
    private val sendUseCase: SendFileUseCase,
    private val fetchUseCase: FetchFileUseCase,
    private val fetchDirectoryUseCase: FetchDirectoryUseCase,
    private val listDirUseCase: ListDirectoryUseCase,
    private val cancelTransferUseCase: CancelTransferUseCase
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val usbMutex = Mutex()

    private val _state = MutableStateFlow("Idle")
    val state: StateFlow<String> = _state

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/sdcard")
    val currentRemotePath: StateFlow<String> = _currentRemotePath

    data class TransferProgress(
        val isVisible: Boolean = false,
        val filename: String = "",
        val percentage: Int = 0,
        val speed: String = "0 B/s",
        val transferred: String = "0 B",
        val total: String = "0 B",
        val eta: String = "Unknown",
        val elapsed: String = "0s",
        val currentFileIndex: Int = 0,
        val totalFiles: Int = 0,
        val statusMessage: String = "",
        val isComplete: Boolean = false,
        val batchElapsed: String = "0s"
    )

    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState

    private var transferJob: kotlinx.coroutines.Job? = null

    fun dismissProgress() {
        _progressState.value = TransferProgress()
    }

    fun cancelTransfer() {
        println("[ViewModel] Cancelling transfer job locally...")
        
        // Immediately dismiss the UI to prevent hanging feeling
        _progressState.value = TransferProgress()
        _state.value = "Transfer Cancelled ❌"
        
        scope.launch {
            // Pause monitor so it doesn't steal the lock while we abort
            connectionMonitorJob?.cancel()
            
            transferJob?.cancel()
            transferJob?.join() // Wait for it to release the mutex
            
            usbMutex.withLock {
                // Immediately notify Android to abort its read loop
                try {
                    cancelTransferUseCase()
                } catch (e: Exception) {
                    println("[ViewModel] Error sending cancel signal: ${e.message}")
                }
                println("[ViewModel] Transfer cancelled by user.")
            }
            
            // Resume monitoring
            startConnectionMonitor()
        }
    }

    private var connectionMonitorJob: kotlinx.coroutines.Job? = null

    fun connect() {
        scope.launch {
            usbMutex.withLock {
                println("[ViewModel] Attempting to connect to USB device...")
                _state.value = "Searching..."
                val success = connectUseCase()
                _state.value = if (success) "Ready" else "Connection Failed"
                println("[ViewModel] Connection status: success=$success")
                if (success) {
                    refreshRemoteFilesInternal()
                    startConnectionMonitor()
                }
            }
        }
    }

    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000) // Ping every 2 seconds
                val isAlive = usbMutex.withLock {
                    try {
                        listDirUseCase(_currentRemotePath.value)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!isAlive) {
                    println("[ViewModel] Connection lost from remote.")
                    disconnect()
                    _state.value = "Connection Lost"
                    break
                }
            }
        }
    }

    fun disconnect() {
        println("[ViewModel] Force disconnecting device...")
        transferJob?.cancel()
        connectionMonitorJob?.cancel()
        
        scope.launch {
            try {
                disconnectUseCase()
            } catch (e: Exception) {
                println("[ViewModel] Disconnect error: ${e.message}")
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
            println("[ViewModel] Listing directory: ${_currentRemotePath.value}")
            _state.value = "Listing ${_currentRemotePath.value}..."
            val files = listDirUseCase(_currentRemotePath.value)
            _remoteFiles.value = files
            _state.value = "Ready"
            println("[ViewModel] Successfully listed ${files.size} items.")
        } catch (e: Exception) {
            println("[ViewModel] Error listing directory: ${e.message}")
            _state.value = "Error: ${e.message}"
        }
    }

    fun navigateTo(file: RemoteFile) {
        if (file.isDirectory) {
            println("[ViewModel] Navigating into directory: ${file.path}")
            _currentRemotePath.value = file.path
            refreshRemoteFiles()
        }
    }

    fun navigateUp() {
        val current = _currentRemotePath.value
        if (current == "/") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        println("[ViewModel] Navigating up from $current to ${if (parent.isEmpty()) "/" else parent}")
        _currentRemotePath.value = if (parent.isEmpty()) "/" else parent
        refreshRemoteFiles()
    }

    fun sendFiles(files: List<File>) {
        val destinationPath = _currentRemotePath.value
        transferJob = scope.launch {
            val total = files.size
            val batchStartTime = System.currentTimeMillis()
            _progressState.value = _progressState.value.copy(isVisible = true, totalFiles = total, isComplete = false)
            for ((index, file) in files.withIndex()) {
                if (!isActive) break
                _progressState.value = _progressState.value.copy(currentFileIndex = index + 1)
                if (file.isDirectory) {
                    sendDirectory(file, destinationPath, batchStartTime)
                } else {
                    sendSingleFile(file, destinationPath, batchStartTime)
                }
            }
            if (isActive) {
                _progressState.value = _progressState.value.copy(isComplete = true, statusMessage = "Transfer Complete")
            }
        }
    }

    private suspend fun sendSingleFile(file: File, destinationPath: String, batchStartTime: Long = System.currentTimeMillis()) {
        usbMutex.withLock {
            println("[ViewModel] Sending file: ${file.name} (size: ${file.length()} bytes) to $destinationPath")
            _state.value = "Sending: ${file.name}..."

            val startTime = System.currentTimeMillis()
            val fileSize = file.length()
            
            _progressState.value = _progressState.value.copy(
                isVisible = true,
                filename = file.name,
                total = formatSize(fileSize),
                percentage = 0,
                speed = "0 B/s",
                transferred = "0 B",
                eta = "Calculating...",
                elapsed = "0s",
                statusMessage = "Sending ${file.name}..."
            )

            try {
                sendUseCase(file, destinationPath).collect { progress -> 
                    _state.value = "Sending: $progress%" 
                    if (progress % 2 == 0) println("[ViewModel] Upload progress: $progress%")

                    val currentTime = System.currentTimeMillis()
                    val elapsedSeconds = (currentTime - startTime) / 1000L
                    val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                    val transferredBytes = (fileSize * progress) / 100

                    val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                    val speed = formatSize(speedBytesPerSec) + "/s"

                    val eta = if (speedBytesPerSec > 0) {
                        val remainingBytes = fileSize - transferredBytes
                        val remainingSeconds = remainingBytes / speedBytesPerSec
                        formatTime(remainingSeconds)
                    } else "Calculating..."

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = formatSize(transferredBytes),
                        eta = eta,
                        elapsed = formatTime(elapsedSeconds),
                        batchElapsed = formatTime(batchElapsedSeconds)
                    )
                }
                _state.value = "Sent Successfully ✅"
                println("[ViewModel] File sent successfully: ${file.name}")
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
            _progressState.value = _progressState.value.copy(isVisible = true, totalFiles = total, isComplete = false)
            for ((index, remoteFile) in remoteFiles.withIndex()) {
                if (!isActive) break
                _progressState.value = _progressState.value.copy(currentFileIndex = index + 1)
                if (remoteFile.isDirectory) {
                    fetchDirectory(remoteFile, batchStartTime)
                } else {
                    fetchSingleFile(remoteFile, batchStartTime)
                }
            }
            if (isActive) {
                _progressState.value = _progressState.value.copy(isComplete = true, statusMessage = "Fetch Complete")
            }
        }
    }

    private suspend fun fetchSingleFile(remoteFile: RemoteFile, batchStartTime: Long = System.currentTimeMillis()) {
        val downloadDir = File(System.getProperty("user.home"), "Downloads")
        downloadDir.mkdirs()
        val localFile = File(downloadDir, remoteFile.name)

        usbMutex.withLock {
            println("[ViewModel] Fetching file: ${remoteFile.name} (size: ${remoteFile.size} bytes)")
            _state.value = "Fetching: ${remoteFile.name}..."

            val startTime = System.currentTimeMillis()
            val fileSize = remoteFile.size
            
            _progressState.value = _progressState.value.copy(
                isVisible = true,
                filename = remoteFile.name,
                total = formatSize(fileSize),
                percentage = 0,
                speed = "0 B/s",
                transferred = "0 B",
                eta = "Calculating...",
                elapsed = "0s",
                statusMessage = "Fetching ${remoteFile.name}..."
            )

            try {
                fetchUseCase(remoteFile.path, localFile).collect { progress -> 
                    _state.value = "Fetching: $progress%" 
                    val currentTime = System.currentTimeMillis()
                    val elapsedSeconds = (currentTime - startTime) / 1000L
                    val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                    val transferredBytes = (fileSize * progress) / 100

                    val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                    val speed = formatSize(speedBytesPerSec) + "/s"

                    val eta = if (speedBytesPerSec > 0) {
                        val remainingBytes = fileSize - transferredBytes
                        val remainingSeconds = remainingBytes / speedBytesPerSec
                        formatTime(remainingSeconds)
                    } else "Calculating..."

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = formatSize(transferredBytes),
                        eta = eta,
                        elapsed = formatTime(elapsedSeconds),
                        batchElapsed = formatTime(batchElapsedSeconds)
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

    private suspend fun sendDirectory(dir: File, destinationPath: String, batchStartTime: Long = System.currentTimeMillis()) {
        usbMutex.withLock {
            println("[ViewModel] Preparing to send directory: ${dir.name} to $destinationPath")
            _state.value = "Zipping: ${dir.name}..."
            
            _progressState.value = _progressState.value.copy(
                isVisible = true,
                filename = dir.name,
                percentage = 0,
                statusMessage = "Zipping ${dir.name}..."
            )

            val tempZip = withContext(Dispatchers.IO) {
                File.createTempFile(dir.name, ".zip")
            }
            try {
                zipDirectory(dir, tempZip)
                println("[ViewModel] Sending zipped directory: ${tempZip.name} to $destinationPath")
                _state.value = "Sending: ${dir.name}.zip..."
                
                val startTime = System.currentTimeMillis()
                val fileSize = tempZip.length()
                
                _progressState.value = _progressState.value.copy(
                    total = formatSize(fileSize),
                    statusMessage = "Sending ${dir.name}.zip..."
                )

                sendUseCase(tempZip, destinationPath, isDirectory = true, remoteFileName = "${dir.name}.zip").collect { progress -> 
                    _state.value = "Sending: $progress%" 
                    val currentTime = System.currentTimeMillis()
                    val elapsedSeconds = (currentTime - startTime) / 1000L
                    val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                    val transferredBytes = (fileSize * progress) / 100
                    val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                    val speed = formatSize(speedBytesPerSec) + "/s"
                    val eta = if (speedBytesPerSec > 0) {
                        formatTime((fileSize - transferredBytes) / speedBytesPerSec)
                    } else "Calculating..."

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = formatSize(transferredBytes),
                        eta = eta,
                        elapsed = formatTime(elapsedSeconds),
                        batchElapsed = formatTime(batchElapsedSeconds)
                    )
                }
                _state.value = "Sent Successfully ✅"
                println("[ViewModel] Directory sent successfully: ${dir.name}")
                refreshRemoteFilesInternal()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _state.value = "Error: ${e.message}"
                    _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                }
            } finally {
                withContext(Dispatchers.IO) { tempZip.delete() }
            }
        }
    }

    private suspend fun zipDirectory(dir: File, zipFile: File) = withContext(Dispatchers.IO) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zout ->
            dir.walkTopDown().forEach { file ->
                ensureActive()
                val entryName = file.toRelativeString(dir).replace('\\', '/')
                if (entryName.isNotEmpty()) {
                    val entry = java.util.zip.ZipEntry(if (file.isDirectory) "$entryName/" else entryName)
                    zout.putNextEntry(entry)
                    if (!file.isDirectory) {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                ensureActive()
                                zout.write(buffer, 0, bytes)
                                bytes = input.read(buffer)
                            }
                        }
                    }
                    zout.closeEntry()
                }
            }
        }
    }

    private suspend fun fetchDirectory(remoteFile: RemoteFile, batchStartTime: Long = System.currentTimeMillis()) {
        val downloadDir = File(System.getProperty("user.home"), "Downloads")
        downloadDir.mkdirs()
        val localFile = File(downloadDir, "${remoteFile.name}.zip")

        usbMutex.withLock {
            println("[ViewModel] Fetching directory: ${remoteFile.name}")
            _state.value = "Fetching: ${remoteFile.name}..."

            val startTime = System.currentTimeMillis()
            
            _progressState.value = _progressState.value.copy(
                isVisible = true, 
                filename = "${remoteFile.name}.zip", 
                total = "Calculating...",
                percentage = 0,
                speed = "0 B/s",
                transferred = "0 B",
                eta = "Calculating...",
                elapsed = "0s",
                statusMessage = "Fetching ${remoteFile.name}.zip..."
            )

            try {
                fetchDirectoryUseCase(remotePath = remoteFile.path, localFile = localFile).collect { progress -> 
                    val currentTime = System.currentTimeMillis()
                    val elapsedSeconds = (currentTime - startTime) / 1000L
                    val batchElapsedSeconds = (currentTime - batchStartTime) / 1000L
                    val transferredBytes = localFile.length()

                    val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                    val speed = formatSize(speedBytesPerSec) + "/s"

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = formatSize(transferredBytes),
                        total = "ZIP Stream",
                        eta = "Processing...",
                        elapsed = formatTime(elapsedSeconds),
                        batchElapsed = formatTime(batchElapsedSeconds),
                        statusMessage = "Downloading ${remoteFile.name}.zip..."
                    )
                }
                _state.value = "Fetched to ${localFile.absolutePath} ✅"
                println("[ViewModel] Directory fetched successfully: ${remoteFile.name}")
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _state.value = "Error: ${e.message}"
                    _progressState.value = _progressState.value.copy(statusMessage = "Error: ${e.message}", isComplete = true)
                }
            }
        }
    }

    private fun formatTime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun convertAnfFile(remoteFile: RemoteFile) {
        scope.launch {
            val tempAnf = File("temp_${remoteFile.name}")
            val csvFile = File(remoteFile.name.substringBeforeLast(".") + ".csv")
            
            println("[ViewModel] Conversion requested for: ${remoteFile.name}")
            println("[ViewModel] Step 1: Fetching remote ANF file to temporary location: ${tempAnf.absolutePath}")
            _state.value = "Fetching: ${remoteFile.name}..."
            
            var fetchSuccess = false
            usbMutex.withLock {
                try {
                    fetchUseCase(remoteFile.path, tempAnf).collect { progress -> 
                        _state.value = "Fetching: $progress%" 
                    }
                    fetchSuccess = true
                } catch (e: Exception) {
                    println("[ViewModel] Fetch error: ${e.message}")
                    _state.value = "Fetch Error: ${e.message}"
                }
            }

            if (fetchSuccess) {
                println("[ViewModel] Step 2: Converting ${tempAnf.name} to ${csvFile.name}...")
                _state.value = "Converting to CSV..."
                try {
                    FileInputStream(tempAnf).use { input ->
                        AnfToCsvConverter.convert(input, csvFile)
                    }
                    _state.value = "Converted to ${csvFile.absolutePath} ✅"
                    println("[ViewModel] Conversion successful! CSV file created at: ${csvFile.absolutePath}")
                } catch (e: Exception) {
                    println("[ViewModel] Conversion error: ${e.message}")
                    _state.value = "Conversion Error: ${e.message}"
                }
            }
            
            println("[ViewModel] Step 3: Cleaning up temporary file: ${tempAnf.absolutePath}")
            tempAnf.delete()
        }
    }

    fun convertLocalAnfFile(file: File) {
        scope.launch {
            val csvFile = File(file.parent, file.name.substringBeforeLast(".") + ".csv")
            println("[ViewModel] Local conversion requested for: ${file.absolutePath}")
            _state.value = "Converting ${file.name}..."
            
            try {
                FileInputStream(file).use { input ->
                    AnfToCsvConverter.convert(input, csvFile)
                }
                _state.value = "Converted to ${csvFile.absolutePath} ✅"
                println("[ViewModel] Local conversion successful: ${csvFile.absolutePath}")
            } catch (e: Exception) {
                println("[ViewModel] Local conversion error: ${e.message}")
                _state.value = "Conversion Error: ${e.message}"
            }
        }
    }

    fun receiveFile() {
        scope.launch {
            val file = File("received.bin")
            println("[ViewModel] Receiving data to: ${file.absolutePath}")
            usbMutex.withLock {
                try {
                    receiveUseCase(file).collect { bytes -> 
                        _state.value = "Received: $bytes bytes" 
                    }
                    _state.value = "Completed ✅"
                    println("[ViewModel] Data reception completed.")
                } catch (e: Exception) {
                    println("[ViewModel] Error receiving file: ${e.message}")
                    _state.value = "Error: ${e.message}"
                }
            }
        }
    }
}
