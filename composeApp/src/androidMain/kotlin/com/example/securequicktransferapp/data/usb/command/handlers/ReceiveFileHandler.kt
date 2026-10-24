package com.example.securequicktransferapp.data.usb.command.handlers

import android.content.Context
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.data.usb.storage.AndroidFilePathResolver
import com.example.securequicktransferapp.data.usb.storage.SmartNavStorageResolver
import com.example.securequicktransferapp.domain.command.AppCommandIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReceiveFileHandler"

/**
 * Handles [AppCommandIds.CMD_SEND_FILE_START]: receives an encrypted file stream from
 * the Desktop host and writes it to the Android filesystem.
 *
 * Uses a three-stage parallel pipeline:
 *   Stage 1 (IO) -> Read raw encrypted packets from USB
 *   Stage 2 (Default) -> Decrypt each packet payload
 *   Stage 3 (IO) -> Write decrypted chunks to disk + report progress
 *
 * Wire format received (header): `[4: nameLen][name bytes][8: fileSize]`
 * Wire format received (stream): TYPE_DATA packets until TYPE_EOF or TYPE_CANCEL
 */
@Singleton
class ReceiveFileHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_SEND_FILE_START

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val (fileName, fileSize) = CommandPayloadParser.readFileStart(payload)

        usbLogger.d(TAG, "Receiving '$fileName' ($fileSize bytes)")
        withContext(Dispatchers.Main) { cmdCtx.onReceiveStarted(fileName, fileSize) }

        var outputStream: OutputStream? = null
        var targetFile: File? = null
        try {
            if (SmartNavStorageResolver.isSmartNavPath(fileName)) {
                outputStream = SmartNavStorageResolver.openOutputStream(context, fileName)
            }
            if (outputStream == null) {
                targetFile = AndroidFilePathResolver.resolve(fileName)
                targetFile.parentFile?.mkdirs()
                outputStream = FileOutputStream(targetFile)
            }
        } catch (e: Exception) {
            usbLogger.e(TAG, "Failed to open OutputStream for '$fileName' — sinking data to keep pipe clear", e)
        }

        try {
            receiveStream(fileSize, outputStream, cmdCtx)
        } catch (e: Exception) {
            outputStream?.runCatching { close() }
            outputStream = null
            if (SmartNavStorageResolver.isSmartNavPath(fileName)) {
                SmartNavStorageResolver.delete(context, fileName)
            } else {
                targetFile?.delete()
            }
            throw e
        } finally {
            outputStream?.close()
        }

        if (outputStream != null) {
            usbLogger.d(TAG, "Successfully received '$fileName'")
            withContext(Dispatchers.Main) { cmdCtx.onReceiveFinished() }
        } else {
            usbLogger.w(TAG, "Finished sinking '$fileName' — file could not be saved")
        }
        return true
    }

    private suspend fun receiveStream(
        fileSize: Long,
        outputStream: OutputStream?,
        cmdCtx: CommandContext
    ) = coroutineScope {
        val rawPacketChannel = Channel<ByteArray>(32)
        val decryptedChannel = Channel<ByteArray>(32)

        // Stage 1: USB receive
        launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val packet = channel.receiveRawPacket() ?: break
                    if (packet.first == SecureQtSdk.Packets.TYPE_CANCEL) throw SecureChannel.TransferCancelledException()
                    if (packet.first != SecureQtSdk.Packets.TYPE_DATA) break
                    rawPacketChannel.send(packet.second)
                }
            } finally {
                rawPacketChannel.close()
            }
        }

        // Stage 2: Decrypt
        launch(Dispatchers.Default) {
            try {
                for (raw in rawPacketChannel) {
                    val decrypted = channel.decryptData(raw) ?: break
                    decryptedChannel.send(decrypted)
                }
            } finally {
                decryptedChannel.close()
            }
        }

        // Stage 3: Write + progress
        var received = 0L
        var lastReportTime = 0L
        for (chunk in decryptedChannel) {
            outputStream?.write(chunk)
            received += chunk.size
            if (fileSize > 0) {
                val now = System.currentTimeMillis()
                if (now - lastReportTime > 250L || received >= fileSize) {
                    lastReportTime = now
                    val progress = received.toFloat() / fileSize
                    launch(Dispatchers.Main) { cmdCtx.onReceiveProgress(progress) }
                }
            }
            if (fileSize > 0 && received >= fileSize) break
        }
    }
}
