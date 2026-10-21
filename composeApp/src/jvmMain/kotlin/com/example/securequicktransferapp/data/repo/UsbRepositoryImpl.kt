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
import kotlin.time.Duration.Companion.milliseconds

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
            val vid = desc.idVendor().toInt() and 0xFFFF
            val pid = desc.idProduct().toInt() and 0xFFFF
            val isAlreadyAoa = pid in 0x2D00..0x2D05
            
            if (isAlreadyAoa) {
                isAoaMode = true
                if (!connection.open(device)) {
                    deviceManager.reportDriverIssue(vid, pid, "Android Device (AOA)")
                    if (deviceManager.isDriverInstalling()) {
                        logger.i(TAG, "WinUSB driver installation in progress for AOA device. Awaiting completion...")
                        val installed = kotlinx.coroutines.runBlocking { deviceManager.waitForDriverInstall() }
                        if (installed) {
                            logger.i(TAG, "Driver installation finished! Re-acquiring AOA device after PnP binding...")
                            Thread.sleep(1200)
                            val freshAccessory = if (targetDeviceId != null) {
                                deviceManager.findDeviceById(targetDeviceId, requireAccessory = true)
                                    ?: deviceManager.findAndroidDevice(requireAccessory = true)
                            } else {
                                deviceManager.findAndroidDevice(requireAccessory = true)
                            }
                            if (freshAccessory != null) {
                                try {
                                    if (connection.open(freshAccessory)) {
                                        logger.i(TAG, "Successfully opened AOA device after driver installation!")
                                    } else {
                                        logger.e(TAG, "Failed to open connection to AOA accessory after driver install.")
                                        return false
                                    }
                                } finally {
                                    deviceManager.releaseDevice(freshAccessory)
                                }
                            } else {
                                logger.e(TAG, "Could not find AOA device after driver install.")
                                return false
                            }
                        } else {
                            return false
                        }
                    } else {
                        return false
                    }
                }
            } else {
                if (connection.switchToAoa(device)) {
                    var accessory: org.usb4java.Device? = null
                    logger.i(TAG, "Waiting up to 10s for device to reconnect in AOA mode (targetId: $targetDeviceId)...")
                    for (i in 0 until 50) {
                        Thread.sleep(200)
                        accessory = if (targetDeviceId != null) {
                            deviceManager.findDeviceById(targetDeviceId, requireAccessory = true)
                                ?: deviceManager.findAndroidDevice(requireAccessory = true)
                        } else {
                            deviceManager.findAndroidDevice(requireAccessory = true)
                        }
                        if (accessory != null) {
                            logger.i(TAG, "Device re-enumerated in AOA mode on attempt ${i + 1}!")
                            break
                        }
                    }
                    if (accessory == null) {
                        logger.w(TAG, "Device failed to reconnect in AOA mode within 10s. If the phone is locked, please unlock it. Prompting driver setup for AOA mode...")
                        deviceManager.reportDriverIssue(0x18D1, 0x2D00, "Android Open Accessory")
                        return false
                    }
                    isAoaMode = true
                    try {
                        // Allow Windows PnP to complete driver binding to the new USB device node
                        Thread.sleep(600)
                        if (!connection.open(accessory)) {
                            logger.e(TAG, "Failed to open connection to accessory in AOA mode.")
                            deviceManager.reportDriverIssue(0x18D1, 0x2D00, "Android Open Accessory")
                            if (deviceManager.isDriverInstalling()) {
                                logger.i(TAG, "WinUSB driver installation in progress for AOA mode. Awaiting completion...")
                                val installed = kotlinx.coroutines.runBlocking { deviceManager.waitForDriverInstall() }
                                if (installed) {
                                    logger.i(TAG, "Driver installation finished! Re-acquiring AOA device after PnP binding...")
                                    Thread.sleep(1200)
                                    val freshAccessory = if (targetDeviceId != null) {
                                        deviceManager.findDeviceById(targetDeviceId, requireAccessory = true)
                                            ?: deviceManager.findAndroidDevice(requireAccessory = true)
                                    } else {
                                        deviceManager.findAndroidDevice(requireAccessory = true)
                                    }
                                    if (freshAccessory != null) {
                                        try {
                                            if (!connection.open(freshAccessory)) {
                                                logger.e(TAG, "Failed to open connection to accessory after driver install.")
                                                return false
                                            }
                                        } finally {
                                            deviceManager.releaseDevice(freshAccessory)
                                        }
                                    } else {
                                        logger.e(TAG, "Could not find AOA device after driver install.")
                                        return false
                                    }
                                } else {
                                    return false
                                }
                            } else {
                                return false
                            }
                        }
                    } finally {
                        deviceManager.releaseDevice(accessory)
                    }
                } else {
                    logger.w(TAG, "Failed to switch device to AOA mode. Ensure device is unlocked and file transfer/AOA is allowed.")
                    return false
                }
            }
        } finally {
            deviceManager.releaseDevice(device)
        }

        // Perform Desktop-side handshake sequence using the unified channel protocol
        try {
            return kotlinx.coroutines.runBlocking {
                val handshakeSuccess = channel.performHandshake(isInitiator = true)
                if (handshakeSuccess) {
                    transferClient.sendDeviceId(targetDeviceId ?: "UnknownDevice")
                }
                handshakeSuccess
            }
        } catch (e: Exception) {
            logger.e(TAG, "Handshake failed", e)
            return false
        }
    }

    override fun disconnect() {
        try {
            kotlinx.coroutines.runBlocking { 
                transferClient.sendDisconnect()
                kotlinx.coroutines.delay(100.milliseconds) // Allow time for packet to traverse USB and be read by Android
            }
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
