package com.example.securequicktransferapp.fixtures

import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardware Device Representation for Testing
 */
data class SimulatedUsbDevice(
    val id: String,
    val busNumber: Int,
    val portPath: String,
    val vendorId: Int,
    val productId: Int,
    val isAoa: Boolean,
    val deviceName: String
) {
    val isAndroid: Boolean
        get() {
            val knownAndroidVids = setOf(
                0x18D1, 0x04E8, 0x2717, 0x2A70, 0x22D9, 0x2B4C,
                0x1EBF, 0x22B8, 0x0FCE, 0x1004, 0x0BB4, 0x19D2, 0x05C6, 0x0E8D
            )
            return (vendorId in knownAndroidVids) || (productId == 0x2D00 || productId == 0x2D01)
        }
}

/**
 * Fake USB Hardware Layer Fixture for E2E Multi-Device Testing
 */
class FakeUsbHardwareFixture {
    private val connectedDevices = ConcurrentHashMap<String, SimulatedUsbDevice>()
    val refCountTracker = ConcurrentHashMap<String, AtomicInteger>()

    fun plugDevice(
        busNumber: Int,
        portPath: String,
        vendorId: Int,
        productId: Int,
        deviceName: String = "Test Device",
        isAoa: Boolean = (productId == 0x2D00 || productId == 0x2D01)
    ): SimulatedUsbDevice {
        val id = "bus_${busNumber}_port_${portPath}"
        val device = SimulatedUsbDevice(
            id = id,
            busNumber = busNumber,
            portPath = portPath,
            vendorId = vendorId,
            productId = productId,
            isAoa = isAoa,
            deviceName = "$deviceName ($id)"
        )
        connectedDevices[id] = device
        refCountTracker[id] = AtomicInteger(1)
        return device
    }

    fun unplugDevice(id: String) {
        connectedDevices.remove(id)
        refCountTracker[id]?.set(0)
    }

    fun discoverAndroidDevices(): List<SimulatedUsbDevice> {
        return connectedDevices.values.filter { it.isAndroid }
    }

    fun getDevice(id: String): SimulatedUsbDevice? {
        return connectedDevices[id]
    }

    fun switchAoaMode(id: String): Boolean {
        val dev = connectedDevices[id] ?: return false
        val newDev = dev.copy(
            productId = 0x2D01,
            isAoa = true,
            deviceName = dev.deviceName.replace("[NORMAL/MTP]", "[ACCESSORY/AOA]")
        )
        connectedDevices[id] = newDev
        return true
    }

    fun clear() {
        connectedDevices.clear()
        refCountTracker.clear()
    }
}

/**
 * Session connection state enum for test isolation
 */
enum class SessionConnectionStatus {
    DISCONNECTED, SEARCHING, CONNECTING, READY, TRANSFERRING, ERROR
}

/**
 * Per-device session state model for multi-device ViewModel & SessionManager testing
 */
data class UsbSessionState(
    val deviceId: String,
    val deviceName: String,
    val isAoaMode: Boolean = false,
    val connectionState: SessionConnectionStatus = SessionConnectionStatus.READY,
    val currentRemotePath: String = "/sdcard",
    val remoteFiles: List<RemoteFile> = emptyList(),
    val progress: TransferProgress = TransferProgress(),
    val errorMessage: String? = null
)

/**
 * Individual USB Session managing single physical device state and mutex lock
 */
class UsbSession(
    val deviceId: String,
    val deviceName: String,
    val isAoaMode: Boolean = false
) {
    val mutex = Mutex()
    private val _state = MutableStateFlow(
        UsbSessionState(deviceId = deviceId, deviceName = deviceName, isAoaMode = isAoaMode)
    )
    val state: StateFlow<UsbSessionState> = _state.asStateFlow()

    var isConnected = true
    var shouldFailTransfer = false
    var shouldCorruptStream = false
    val fileStore = ConcurrentHashMap<String, MutableList<RemoteFile>>()

    init {
        fileStore["/sdcard"] = mutableListOf(
            RemoteFile(name = "DCIM", path = "/sdcard/DCIM", isDirectory = true, size = 0),
            RemoteFile(name = "Download", path = "/sdcard/Download", isDirectory = true, size = 0),
            RemoteFile(name = "sample.txt", path = "/sdcard/sample.txt", isDirectory = false, size = 1024)
        )
    }

    suspend fun listDirectory(path: String): List<RemoteFile> = mutex.withLock {
        checkConnected()
        val files = fileStore[path] ?: emptyList()
        _state.update { it.copy(currentRemotePath = path, remoteFiles = files) }
        return files
    }

    suspend fun createFolder(remoteDirPath: String): Boolean = mutex.withLock {
        checkConnected()
        val parent = if (remoteDirPath.contains("/")) remoteDirPath.substringBeforeLast("/") else "/sdcard"
        val folderName = remoteDirPath.substringAfterLast("/")
        val parentPath = if (parent.isEmpty()) "/sdcard" else parent

        val list = fileStore.getOrPut(parentPath) { mutableListOf() }
        if (list.none { it.path == remoteDirPath }) {
            list.add(RemoteFile(name = folderName, path = remoteDirPath, isDirectory = true, size = 0))
        }
        fileStore.putIfAbsent(remoteDirPath, mutableListOf())
        _state.update { it.copy(remoteFiles = fileStore[it.currentRemotePath]?.toList() ?: emptyList()) }
        return true
    }

    fun updateAoaMode(isAoa: Boolean) {
        _state.update { it.copy(isAoaMode = isAoa) }
    }

    fun cancel() {
        _state.update {
            it.copy(
                connectionState = SessionConnectionStatus.READY,
                errorMessage = "Cancelled"
            )
        }
    }

    suspend fun sendFile(
        file: File,
        remoteDir: String,
        progressCallback: ((Int) -> Unit)? = null
    ): Boolean = mutex.withLock {
        checkConnected()
        if (shouldFailTransfer) throw IllegalStateException("USB write failed for device $deviceId")
        if (shouldCorruptStream) throw java.io.IOException("Corrupted USB packet stream on $deviceId")

        _state.update { it.copy(connectionState = SessionConnectionStatus.TRANSFERRING) }
        val steps = 5
        for (i in 1..steps) {
            if (!isConnected) throw java.io.IOException("Device $deviceId unplugged during transfer")
            val pct = (i * 100) / steps
            progressCallback?.invoke(pct)
            _state.update {
                it.copy(
                    progress = it.progress.copy(
                        isVisible = true,
                        filename = file.name,
                        percentage = pct,
                        statusMessage = "Sending ${file.name} ($pct%)"
                    )
                )
            }
            delay(10)
        }

        val targetPath = "$remoteDir/${file.name}".replace("//", "/")
        val list = fileStore.getOrPut(remoteDir) { mutableListOf() }
        list.removeAll { it.name == file.name }
        list.add(RemoteFile(name = file.name, path = targetPath, isDirectory = file.isDirectory, size = file.length()))

        _state.update {
            it.copy(
                connectionState = SessionConnectionStatus.READY,
                progress = TransferProgress(isComplete = true, statusMessage = "Transfer Complete")
            )
        }
        return true
    }

    suspend fun fetchFile(
        remotePath: String,
        localFile: File,
        progressCallback: ((Int) -> Unit)? = null
    ): Boolean = mutex.withLock {
        checkConnected()
        if (shouldFailTransfer) throw IllegalStateException("USB read failed for device $deviceId")

        _state.update { it.copy(connectionState = SessionConnectionStatus.TRANSFERRING) }
        val steps = 5
        for (i in 1..steps) {
            if (!isConnected) throw java.io.IOException("Device $deviceId unplugged during fetch")
            val pct = (i * 100) / steps
            progressCallback?.invoke(pct)
            delay(10)
        }

        localFile.parentFile?.mkdirs()
        localFile.writeText("Simulated downloaded content from $remotePath")

        _state.update {
            it.copy(
                connectionState = SessionConnectionStatus.READY,
                progress = TransferProgress(isComplete = true, statusMessage = "Fetch Complete")
            )
        }
        return true
    }

    suspend fun deleteFile(remotePath: String): Boolean = mutex.withLock {
        checkConnected()
        val parent = remotePath.substringBeforeLast("/")
        val parentPath = if (parent.isEmpty()) "/sdcard" else parent
        val list = fileStore[parentPath] ?: return false
        val removed = list.removeAll { it.path == remotePath }
        if (removed) {
            fileStore.remove(remotePath)
        }
        return removed
    }

    private fun checkConnected() {
        if (!isConnected) {
            throw IllegalStateException("Device $deviceId is physically disconnected")
        }
    }
}
