package com.example.securequicktransferapp.fixtures

import com.example.securequicktransferapp.domain.model.RemoteFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake MainViewModel test fixture implementing multi-device VM contracts from PROJECT.md
 */
class FakeMainViewModel(
    val sessionManager: FakeUsbSessionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _activeDeviceId = MutableStateFlow<String?>(null)
    val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

    val deviceSessions: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState

    private val _activeRemoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val activeRemoteFiles: StateFlow<List<RemoteFile>> = _activeRemoteFiles.asStateFlow()

    private val _activeCurrentPath = MutableStateFlow<String>("/sdcard")
    val activeCurrentPath: StateFlow<String> = _activeCurrentPath.asStateFlow()

    val tabSwitchCount = AtomicInteger(0)
    private val activeJobs = mutableMapOf<String, Job>()

    init {
        scope.launch {
            deviceSessions.collect { map ->
                val currentActive = _activeDeviceId.value
                if (currentActive == null || currentActive !in map) {
                    val nextDevice = map.keys.firstOrNull()
                    _activeDeviceId.value = nextDevice
                    if (nextDevice != null) {
                        updateActiveExplorerView(nextDevice)
                    } else {
                        _activeRemoteFiles.value = emptyList()
                    }
                } else {
                    updateActiveExplorerView(currentActive)
                }
            }
        }
    }

    fun selectDevice(deviceId: String) {
        tabSwitchCount.incrementAndGet()
        _activeDeviceId.value = deviceId
        updateActiveExplorerView(deviceId)
    }

    private fun updateActiveExplorerView(deviceId: String) {
        val session = sessionManager.getSession(deviceId)
        if (session != null) {
            val state = session.state.value
            _activeCurrentPath.value = state.currentRemotePath
            _activeRemoteFiles.value = state.remoteFiles
        } else {
            _activeRemoteFiles.value = emptyList()
        }
    }

    fun sendFiles(deviceId: String, files: List<File>, targetPath: String = "/sdcard"): Job {
        val job = scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            files.forEach { file ->
                session.sendFile(file, targetPath)
            }
            if (_activeDeviceId.value == deviceId) {
                updateActiveExplorerView(deviceId)
            }
        }
        activeJobs[deviceId] = job
        return job
    }

    fun fetchFiles(deviceId: String, remoteFiles: List<RemoteFile>, localDir: File): Job {
        val job = scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            remoteFiles.forEach { remoteFile ->
                val localFile = File(localDir, remoteFile.name)
                session.fetchFile(remoteFile.path, localFile)
            }
        }
        activeJobs[deviceId] = job
        return job
    }

    fun cancelTransfer(deviceId: String) {
        activeJobs[deviceId]?.cancel()
        activeJobs.remove(deviceId)
        val session = sessionManager.getSession(deviceId)
        session?.cancel()
    }

    fun createFolder(deviceId: String, folderName: String): Job {
        return scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            val currentPath = session.state.value.currentRemotePath
            val newPath = "$currentPath/$folderName".replace("//", "/")
            session.createFolder(newPath)
            session.listDirectory(currentPath)
            if (_activeDeviceId.value == deviceId) {
                updateActiveExplorerView(deviceId)
            }
        }
    }

    fun deleteFile(deviceId: String, remoteFile: RemoteFile): Job {
        return scope.launch {
            val session = sessionManager.getSession(deviceId) ?: return@launch
            session.deleteFile(remoteFile.path)
            val currentPath = session.state.value.currentRemotePath
            session.listDirectory(currentPath)
            if (_activeDeviceId.value == deviceId) {
                updateActiveExplorerView(deviceId)
            }
        }
    }

    fun getSidebarRenderedDeviceIds(): List<String> {
        return deviceSessions.value.keys.toList()
    }
}
