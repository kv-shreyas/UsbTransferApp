package com.example.securequicktransferapp.data.repo

import com.example.securequicktransferapp.domain.repo.UsbRepository
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.secureqt.sdk.host.DesktopAoaLink
import com.example.secureqt.sdk.logging.ILogger
import com.example.securequicktransferapp.data.logging.ConsoleLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File

/**
 * Desktop Host Command Sender: Implements [UsbRepository] when the Desktop acts
 * as the USB Host (initiator), sending file transfer and management commands to
 * the connected Android accessory / client.
 *
 * Communicates with the Android client receiver ([AndroidClientCommandReceiver]) and
 * delegates low-level transfer command encoding to the SDK's [com.example.secureqt.sdk.client.ISecureTransferClient].
 */
class DesktopHostCommandSender(
    private val deviceManager: UsbDeviceManager,
    private val connection: UsbConnection,
    private val targetDeviceId: String? = null,
    private val logger: ILogger = ConsoleLogger()
) : UsbRepository {

    // SDK integration: the link owns device discovery, AOA switching, handshake and the transfer client
    private val link = DesktopAoaLink(deviceManager, connection, targetDeviceId, logger)
    private val transferClient = link.client

    override val isAoaMode: Boolean
        get() = link.isAoaMode

    override fun connect(): Boolean = link.connect()

    override fun disconnect() = link.disconnect()

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

    override suspend fun executeCustomCommand(commandId: Byte, payload: ByteArray): ByteArray? {
        return transferClient.executeCustomCommand(commandId, payload)
    }

    override fun checkPhysicalConnection(): Pair<Boolean, String?> = link.checkPhysicalConnection()
}
