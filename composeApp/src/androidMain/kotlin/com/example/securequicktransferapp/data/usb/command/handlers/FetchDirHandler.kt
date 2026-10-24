package com.example.securequicktransferapp.data.usb.command.handlers

import android.content.Context
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.data.usb.storage.AndroidFilePathResolver
import com.example.securequicktransferapp.data.usb.storage.SmartNavStorageResolver
import com.example.securequicktransferapp.data.usb.storage.UsbDataSource
import com.example.securequicktransferapp.data.usb.util.ZipUtils
import com.example.securequicktransferapp.domain.command.AppCommandIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FetchDirHandler"

/**
 * Handles [AppCommandIds.CMD_FETCH_DIR_ZIP_START]: zips a directory and streams it
 * (encrypted) back to the Desktop host.
 *
 * Wire format received: `[4: pathLen][pathLen bytes: path]`
 * Wire format sent (size): `[8: zipSize]` — then TYPE_DATA packets — then TYPE_EOF
 * On error: sends size=0 then TYPE_EOF.
 */
@Singleton
class FetchDirHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataSource: UsbDataSource,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_FETCH_DIR_ZIP_START

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val path = CommandPayloadParser.readString(payload)

        usbLogger.d(TAG, "FetchDir request for '$path'")

        if (SmartNavStorageResolver.isSmartNavPath(path)) {
            handleSmartNavDir(path)
        } else {
            handleRegularDir(path)
        }
        return true
    }

    private suspend fun handleSmartNavDir(path: String) {
        val (zipSize, zipPath) = SmartNavStorageResolver.zipDirectory(context, path)
        if (zipSize <= 0L || zipPath == null) {
            usbLogger.w(TAG, "SmartNav zip failed or empty for '$path'")
            sendEmpty()
            return
        }

        usbLogger.d(TAG, "Sending SmartNav ZIP: $zipSize bytes from '$zipPath'")
        dataSource.sendSecure(ByteBuffer.allocate(8).putLong(zipSize).array())

        val input: InputStream = SmartNavStorageResolver.openInputStream(context, zipPath)
            ?: tryOpenFallbackStream(zipPath) ?: run { sendEmpty(); return }

        try {
            sendStream(input)
        } finally {
            SmartNavStorageResolver.cleanupTempFile(context, zipPath)
            File(zipPath).delete()
        }
        usbLogger.d(TAG, "SmartNav FetchDir complete for '$path'")
    }

    private suspend fun handleRegularDir(path: String) {
        val dir = AndroidFilePathResolver.resolve(path)
        if (!dir.exists() || !dir.isDirectory) {
            usbLogger.w(TAG, "Directory not found: '${dir.absolutePath}'")
            sendEmpty()
            return
        }

        val tempZip = File(context.cacheDir, "transfer_${dir.name}.zip")
        try {
            usbLogger.d(TAG, "Zipping '${dir.absolutePath}' -> '${tempZip.absolutePath}'")
            ZipUtils.zipDirectory(dir, tempZip)
            val zipSize = tempZip.length()
            usbLogger.d(TAG, "Sending ZIP: $zipSize bytes")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(zipSize).array())
            sendStream(FileInputStream(tempZip))
        } catch (e: Exception) {
            usbLogger.e(TAG, "Error zipping/sending directory '$path'", e)
        } finally {
            tempZip.delete()
        }
        usbLogger.d(TAG, "FetchDir complete for '$path'")
    }

    private fun tryOpenFallbackStream(zipPath: String): InputStream? {
        return try {
            FileInputStream(File(zipPath))
        } catch (e: Exception) {
            if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $zipPath")).inputStream
                } catch (_: Exception) { null }
            } else null
        }
    }

    private suspend fun sendEmpty() {
        dataSource.sendSecure(ByteBuffer.allocate(8).putLong(0L).array())
        dataSource.sendRawPacket(SecureQtSdk.Packets.TYPE_EOF, ByteArray(0))
    }

    private suspend fun sendStream(input: InputStream) = coroutineScope {
        val chunkChannel = Channel<ByteArray>(32)
        val encryptedChannel = Channel<ByteArray>(32)

        launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(256 * 1024)
                while (isActive) {
                    val read = input.read(buf)
                    if (read == -1) break
                    chunkChannel.send(if (read == buf.size) buf.copyOf() else buf.copyOf(read))
                }
            } finally {
                chunkChannel.close()
                input.close()
            }
        }

        launch(Dispatchers.Default) {
            try {
                for (chunk in chunkChannel) {
                    val encrypted = dataSource.encryptAndWrapData(chunk) ?: break
                    encryptedChannel.send(encrypted)
                }
            } finally {
                encryptedChannel.close()
            }
        }

        for (encrypted in encryptedChannel) {
            if (!dataSource.sendPrebuiltPacket(encrypted)) break
        }
        dataSource.sendRawPacket(SecureQtSdk.Packets.TYPE_EOF, ByteArray(0))
    }
}
