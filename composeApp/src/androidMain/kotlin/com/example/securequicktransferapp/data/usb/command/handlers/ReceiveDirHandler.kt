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
import com.example.securequicktransferapp.data.usb.util.ZipUtils
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
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReceiveDirHandler"

/**
 * Handles [AppCommandIds.CMD_SEND_DIR_ZIP_START]: receives a directory ZIP stream from
 * the Desktop host, writes it to a temp file, then extracts it to the target directory.
 *
 * Wire format received (header): `[4: nameLen][name bytes][8: zipSize]`
 * Wire format received (stream): TYPE_DATA packets until TYPE_EOF or TYPE_CANCEL
 */
@Singleton
class ReceiveDirHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_SEND_DIR_ZIP_START

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val (fileName, fileSize) = CommandPayloadParser.readFileStart(payload)
        val folderName = fileName.removeSuffix(".zip")

        usbLogger.d(TAG, "Receiving directory '$folderName' (zipSize=$fileSize bytes)")
        withContext(Dispatchers.Main) { cmdCtx.onReceiveStarted(folderName, fileSize) }

        val targetDirectory = AndroidFilePathResolver.resolve(folderName).also { it.mkdirs() }
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val tempZip = File(downloadsDir, ".temp_receive_${File(fileName).name}")

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(tempZip)
        } catch (e: Exception) {
            usbLogger.e(TAG, "Failed to create temp zip file", e)
        }

        try {
            receiveStream(fileSize, fos, cmdCtx)
        } catch (e: Exception) {
            fos?.runCatching { close() }
            fos = null
            tempZip.delete()
            throw e
        } finally {
            fos?.close()
        }

        if (fos != null) {
            usbLogger.d(TAG, "Received ZIP — starting extraction to '$folderName'")
            try {
                withContext(Dispatchers.Main) { cmdCtx.onReceiveStarted("Extracting $folderName...", fileSize) }
                withContext(Dispatchers.IO) {
                    if (SmartNavStorageResolver.isSmartNavPath(folderName)) {
                        val unzipped = SmartNavStorageResolver.unzipDirectory(context, tempZip, folderName)
                        if (!unzipped) ZipUtils.unzipFile(tempZip, targetDirectory, context)
                    } else {
                        ZipUtils.unzipFile(tempZip, targetDirectory, context)
                    }
                }
                usbLogger.d(TAG, "Extraction complete for '$folderName'")
                withContext(Dispatchers.Main) { cmdCtx.onReceiveFinished() }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Extraction failed for '$folderName'", e)
            } finally {
                tempZip.delete()
            }
        }
        return true
    }

    private suspend fun receiveStream(
        fileSize: Long,
        outputStream: FileOutputStream?,
        cmdCtx: CommandContext
    ) = coroutineScope {
        val rawPacketChannel = Channel<ByteArray>(32)
        val decryptedChannel = Channel<ByteArray>(32)

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
