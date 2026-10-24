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
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FetchFileHandler"

/**
 * Handles [AppCommandIds.CMD_FETCH_FILE_START]: reads a file from the Android filesystem
 * and streams it (encrypted) back to the Desktop host.
 *
 * Wire format received: `[4: pathLen][pathLen bytes: path]`
 * Wire format sent (size): `[8: fileSize]` — then TYPE_DATA packets — then TYPE_EOF
 * On error: sends size=0 then TYPE_EOF immediately.
 *
 * Uses a three-stage parallel pipeline:
 *   Stage 1 (IO) -> Read file chunks from disk
 *   Stage 2 (Default) -> Encrypt each chunk
 *   Stage 3 (IO) -> Send prebuilt encrypted packet over USB
 */
@Singleton
class FetchFileHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_FETCH_FILE_START

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val path = CommandPayloadParser.readString(payload)

        usbLogger.d(TAG, "Fetch request for '$path'")

        val resolved = resolveInputStream(path)
        if (resolved == null) {
            usbLogger.w(TAG, "File not found or unreadable: '$path'")
            channel.sendSecure(ByteBuffer.allocate(8).putLong(0L).array())
            channel.sendRawPacket(SecureQtSdk.Packets.TYPE_EOF, ByteArray(0))
            return true
        }

        val (inputStream, length) = resolved
        usbLogger.d(TAG, "Sending $length bytes from '$path'")
        channel.sendSecure(ByteBuffer.allocate(8).putLong(length).array())

        try {
            sendStream(inputStream)
        } finally {
            inputStream.close()
        }

        usbLogger.d(TAG, "Fetch complete for '$path'")
        return true
    }

    private fun resolveInputStream(path: String): Pair<InputStream, Long>? {
        return if (SmartNavStorageResolver.isSmartNavPath(path)) {
            val info = SmartNavStorageResolver.getFileInfo(context, path)
            if (!info.exists || info.isDirectory) {
                val direct = AndroidFilePathResolver.resolve(path)
                if (!direct.exists() || direct.isDirectory) return null
                Pair(FileInputStream(direct), direct.length())
            } else {
                val stream = SmartNavStorageResolver.openInputStream(context, path)
                    ?: AndroidFilePathResolver.resolve(path).let { if (it.exists()) FileInputStream(it) else null }
                    ?: return null
                Pair(stream, info.size)
            }
        } else {
            val file = AndroidFilePathResolver.resolve(path)
            if (!file.exists() || file.isDirectory) return null
            Pair(FileInputStream(file), file.length())
        }
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
            }
        }

        launch(Dispatchers.Default) {
            try {
                for (chunk in chunkChannel) {
                    val encrypted = channel.encryptAndWrapData(chunk) ?: break
                    encryptedChannel.send(encrypted)
                }
            } finally {
                encryptedChannel.close()
            }
        }

        for (encrypted in encryptedChannel) {
            if (!channel.sendPrebuiltPacket(encrypted)) break
        }
        channel.sendRawPacket(SecureQtSdk.Packets.TYPE_EOF, ByteArray(0))
    }
}
