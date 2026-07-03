package com.example.usbtransferapp.presentation.vm

import com.example.usbtransferapp.domain.usecases.*
import com.example.usbtransferapp.domain.model.RemoteFile
import com.example.usbtransferapp.data.converter.AnfToCsvConverter
import com.example.usbtransferapp.presentation.ui.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val elapsed: String = "0s"
    )

    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState

    private var transferJob: kotlinx.coroutines.Job? = null

    fun cancelTransfer() {
        println("[ViewModel] Cancelling transfer job locally...")
        transferJob?.cancel() // This throws CancellationException inside the transfer and releases the mutex
        
        scope.launch {
            usbMutex.withLock {
                _progressState.value = TransferProgress()
                _state.value = "Transfer Cancelled ❌"
                
                // Immediately notify Android to abort its read loop
                cancelTransferUseCase()
                
                println("[ViewModel] Transfer cancelled by user.")
            }
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

    fun sendFile(file: File) {
        val destinationPath = _currentRemotePath.value
        transferJob = scope.launch {
            usbMutex.withLock {
                println("[ViewModel] Sending file: ${file.name} (size: ${file.length()} bytes) to $destinationPath")
                _state.value = "Sending: ${file.name}..."

                val startTime = System.currentTimeMillis()
                val fileSize = file.length()
                
                _progressState.value = TransferProgress(
                    isVisible = true,
                    filename = file.name,
                    total = formatSize(fileSize)
                )

                try {
                    sendUseCase(file, destinationPath).collect { progress -> 
                        _state.value = "Sending: $progress%" 
                        if (progress % 2 == 0) println("[ViewModel] Upload progress: $progress%")

                        val currentTime = System.currentTimeMillis()
                        val elapsedSeconds = (currentTime - startTime) / 1000L
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
                            elapsed = formatTime(elapsedSeconds)
                        )
                    }
                    _state.value = "Sent Successfully ✅"
                    println("[ViewModel] File sent successfully: ${file.name}")
                    refreshRemoteFilesInternal()
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _state.value = "Error: ${e.message}"
                    }
                } finally {
                    _progressState.value = TransferProgress() // Hide
                }
            }
        }
    }

    fun fetchFile(remoteFile: RemoteFile) {
        transferJob = scope.launch {
            if (remoteFile.isDirectory) {
                fetchDirectory(remoteFile)
                return@launch
            }
            usbMutex.withLock {
                val localFile = File("fetched_${remoteFile.name}")
                val startTime = System.currentTimeMillis()
                
                _progressState.value = TransferProgress(
                    isVisible = true, 
                    filename = remoteFile.name, 
                    total = formatSize(remoteFile.size)
                )

                try {
                    fetchUseCase(remoteFileName = remoteFile.path, localFile = localFile).collect { progress ->
                        val currentTime = System.currentTimeMillis()
                        val elapsedSeconds = (currentTime - startTime) / 1000L
                        val transferredBytes = (remoteFile.size * progress) / 100

                        val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                        val speed = formatSize(speedBytesPerSec) + "/s"

                        val eta = if (speedBytesPerSec > 0) {
                            val remainingBytes = remoteFile.size - transferredBytes
                            val remainingSeconds = remainingBytes / speedBytesPerSec
                            formatTime(remainingSeconds)
                        } else "Calculating..."

                        _progressState.value = _progressState.value.copy(
                            percentage = progress,
                            speed = speed,
                            transferred = formatSize(transferredBytes),
                            eta = eta,
                            elapsed = formatTime(elapsedSeconds)
                        )
                    }
                    _state.value = "Fetched to ${localFile.absolutePath} ✅"
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _state.value = "Error: ${e.message}"
                    }
                } finally {
                    _progressState.value = TransferProgress() // Hide
                }
            }
        }
    }

    private suspend fun fetchDirectory(remoteFile: RemoteFile) {
        usbMutex.withLock {
            val localFile = File("fetched_${remoteFile.name}.zip")
            val startTime = System.currentTimeMillis()
            
            _progressState.value = TransferProgress(
                isVisible = true, 
                filename = "${remoteFile.name}.zip", 
                total = "Calculating..."
            )

            try {
                fetchDirectoryUseCase(remotePath = remoteFile.path, localFile = localFile).collect { progress -> 
                    val currentTime = System.currentTimeMillis()
                    val elapsedSeconds = (currentTime - startTime) / 1000L
                    val transferredBytes = localFile.length()

                    val speedBytesPerSec = if (elapsedSeconds > 0) (transferredBytes / elapsedSeconds) else 0L
                    val speed = formatSize(speedBytesPerSec) + "/s"

                    _progressState.value = _progressState.value.copy(
                        percentage = progress,
                        speed = speed,
                        transferred = formatSize(transferredBytes),
                        total = "ZIP Stream",
                        eta = "Processing...",
                        elapsed = formatTime(elapsedSeconds)
                    )
                }
                _state.value = "Fetched ZIP to ${localFile.absolutePath} ✅"
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _state.value = "Error: ${e.message}"
                }
            } finally {
                _progressState.value = TransferProgress() // Hide
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
