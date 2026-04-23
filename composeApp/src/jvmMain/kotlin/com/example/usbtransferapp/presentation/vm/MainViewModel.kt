package com.example.usbtransferapp.presentation.vm

import com.example.usbtransferapp.domain.usecases.*
import com.example.usbtransferapp.domain.model.RemoteFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(
    private val connectUseCase: ConnectUsbUseCase,
    private val receiveUseCase: ReceiveFileUseCase,
    private val sendUseCase: SendFileUseCase,
    private val fetchUseCase: FetchFileUseCase,
    private val listDirUseCase: ListDirectoryUseCase
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableStateFlow("Idle")
    val state: StateFlow<String> = _state

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/")
    val currentRemotePath: StateFlow<String> = _currentRemotePath

    fun connect() {
        scope.launch {
            _state.value = "Searching..."
            val success = connectUseCase()
            _state.value = if (success) "Ready" else "Connection Failed"
            if (success) refreshRemoteFiles()
        }
    }

    fun refreshRemoteFiles() {
        scope.launch {
            try {
                _state.value = "Listing ${_currentRemotePath.value}..."
                _remoteFiles.value = listDirUseCase(_currentRemotePath.value)
                _state.value = "Ready"
            } catch (e: Exception) {
                _state.value = "Error: ${e.message}"
            }
        }
    }

    fun navigateTo(file: RemoteFile) {
        if (file.isDirectory) {
            _currentRemotePath.value = file.path
            refreshRemoteFiles()
        }
    }

    fun navigateUp() {
        val current = _currentRemotePath.value
        if (current == "/") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        _currentRemotePath.value = if (parent.isEmpty()) "/" else parent
        refreshRemoteFiles()
    }

    fun sendFile(file: File) {
        scope.launch {
            _state.value = "Sending: ${file.name}..."
            sendUseCase(file).collect { progress -> _state.value = "Sending: $progress%" }
            _state.value = "Sent Successfully ✅"
            refreshRemoteFiles()
        }
    }

    fun fetchFile(remoteFile: RemoteFile) {
        scope.launch {
            val localFile = File("fetched_${remoteFile.name}")
            _state.value = "Fetching: ${remoteFile.name}..."
            fetchUseCase(remoteFile.path, localFile).collect { progress -> _state.value = "Fetching: $progress%" }
            _state.value = "Fetched to ${localFile.absolutePath} ✅"
        }
    }

    fun receiveFile() {
        scope.launch {
            val file = File("received.bin")
            receiveUseCase(file).collect { bytes -> _state.value = "Received: $bytes bytes" }
            _state.value = "Completed ✅"
        }
    }
}
