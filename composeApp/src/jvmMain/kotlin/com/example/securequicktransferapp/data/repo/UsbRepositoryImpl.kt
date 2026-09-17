package com.example.securequicktransferapp.data.repo

import com.example.securequicktransferapp.domain.repo.UsbRepository
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.DesktopUsbTransport
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.logging.ILogger
import com.example.securequicktransferapp.data.logging.ConsoleLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File

class UsbRepositoryImpl(
    private val deviceManager: UsbDeviceManager,
    private val connection: UsbConnection,
    private val targetDeviceId: String? = null,
    private val logger: ILogger = ConsoleLogger()
) : UsbRepository {

    private val TAG = "UsbRepo"
    override var isAoaMode: Boolean = false
        private set

    // SDK integration: The transport adapts the desktop connection, the channel manages framing/crypto, and client manages the transfer logic
    private val transport = DesktopUsbTransport(connection)
    private val channel = SecureQtSdk.createChannel(transport)
    private val transferClient = SecureQtSdk.createTransferClient(channel)

    override fun connect(): Boolean {
        isAoaMode = false
        logger.i(TAG, "Attempting to find Android device (targetDeviceId: $targetDeviceId)...")
        
        var device: org.usb4java.Device? = null
        for (i in 0 until 5) {
            device = if (targetDeviceId != null) {
                deviceManager.findDeviceById(targetDeviceId)
            } else {
                deviceManager.findAndroidDevice()
            }
            if (device != null) break
            Thread.sleep(200)
        }
        
        if (device == null) return false
        
        try {
            val desc = org.usb4java.DeviceDescriptor()
            org.usb4java.LibUsb.getDeviceDescriptor(device, desc)
            val pid = desc.idProduct().toInt() and 0xFFFF
            val isAlreadyAoa = pid == 0x2D00 || pid == 0x2D01
            
            if (isAlreadyAoa) {
                isAoaMode = true
                if (!connection.open(device)) return false
            } else {
                if (connection.switchToAoa(device)) {
                    var accessory: org.usb4java.Device? = null
                    for (i in 0 until 25) {
                        Thread.sleep(120)
                        accessory = if (targetDeviceId != null) {
                            deviceManager.findDeviceById(targetDeviceId, requireAccessory = true)
                        } else {
                            deviceManager.findAndroidDevice(requireAccessory = true)
                        }
                        if (accessory != null) break
                    }
                    if (accessory == null) return false
                    isAoaMode = true
                    try {
                        Thread.sleep(120)
                        if (!connection.open(accessory)) return false
                    } finally {
                        deviceManager.releaseDevice(accessory)
                    }
                } else {
                    return false
                }
            }
        } finally {
            deviceManager.releaseDevice(device)
        }

        // Perform Desktop-side handshake sequence using the unified channel protocol
        try {
            return kotlinx.coroutines.runBlocking {
                channel.performHandshake(isInitiator = true)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Handshake failed", e)
            return false
        }
    }

    override fun disconnect() {
        try {
            kotlinx.coroutines.runBlocking { transferClient.sendDisconnect() }
        } catch (e: Exception) {
            logger.w(TAG, "Error sending disconnect signal: ${e.message}")
        }
        connection.close()
    }

    override fun receiveStream(): Flow<ByteArray> = channelFlow { }

    override suspend fun listDirectory(path: String): List<RemoteFile> {
        return transferClient.listRemoteFiles(path).map { RemoteFile(it.name, it.isDirectory, it.size, path + "/" + it.name) }
    }

    override fun sendFile(file: File, destinationPath: String, isDirectory: Boolean, remoteFileName: String): Flow<Int> = channelFlow {
        if (isDirectory) {
            transferClient.sendDirectory(file, destinationPath) { sent, total ->
                val progress = if (total > 0) ((sent.toFloat() / total) * 100).toInt() else 0
                trySend(progress)
            }
        } else {
            transferClient.sendFile(file, destinationPath, remoteFileName) { sent, total ->
                val progress = if (total > 0) ((sent.toFloat() / total) * 100).toInt() else 0
                trySend(progress)
            }
        }
        trySend(100)
    }

    override fun fetchFile(remotePath: String, localFile: File): Flow<Int> = channelFlow {
        val success = transferClient.fetchFile(remotePath, localFile.parentFile) { sent, total ->
            val progress = if (total > 0) ((sent.toFloat() / total) * 100).toInt() else 0
            trySend(progress)
        }
        if (success) {
            trySend(100)
        } else {
            error("Failed to fetch file from device: $remotePath")
        }
    }

    override fun fetchDirectory(remotePath: String, localFile: File): Flow<Int> = channelFlow {
        val success = transferClient.fetchRemoteDirectory(remotePath, localFile.parentFile) { sent, total ->
            val progress = if (total > 0) ((sent.toFloat() / total) * 100).toInt() else 0
            trySend(progress)
        }
        if (success) {
            trySend(100)
        } else {
            error("Failed to fetch directory from device: $remotePath")
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean = transferClient.deleteFile(remotePath)
    override suspend fun renameFile(remotePath: String, newName: String): Boolean = transferClient.renameFile(remotePath, newName)
    override suspend fun createFolder(remotePath: String): Boolean = transferClient.createFolder(remotePath)

    override fun cancelTransfer() {
        transferClient.cancelTransfer()
    }

    override fun checkPhysicalConnection(): Pair<Boolean, String?> {
        return if (targetDeviceId != null) {
            deviceManager.isDevicePhysicallyConnected(targetDeviceId)
        } else {
            deviceManager.isDevicePhysicallyConnected()
        }
    }
}
