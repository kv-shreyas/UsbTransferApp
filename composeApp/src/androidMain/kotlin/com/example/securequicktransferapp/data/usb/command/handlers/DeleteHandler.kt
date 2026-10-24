package com.example.securequicktransferapp.data.usb.command.handlers

import android.content.Context
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.data.usb.storage.AndroidFilePathResolver
import com.example.securequicktransferapp.data.usb.storage.SmartNavStorageResolver
import com.example.securequicktransferapp.domain.command.AppCommandIds
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeleteHandler"

/**
 * Handles [AppCommandIds.CMD_DELETE_FILE]: deletes a file or directory tree on the Android device.
 *
 * Wire format received: `[4: pathLen][pathLen bytes: path]`
 * Wire format sent:     `[1: success]` — `0x01` = success, `0x00` = failure
 */
@Singleton
class DeleteHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_DELETE_FILE

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val path = CommandPayloadParser.readString(payload)

        usbLogger.d(TAG, "Deleting '$path'")
        val success = if (SmartNavStorageResolver.isSmartNavPath(path)) {
            SmartNavStorageResolver.delete(context, path)
        } else {
            val file = AndroidFilePathResolver.resolve(path)
            try {
                when {
                    !file.exists() -> false
                    file.isDirectory -> file.deleteRecursively()
                    else -> file.delete()
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error deleting '$path'", e)
                false
            }
        }

        usbLogger.d(TAG, "Delete result: success=$success")
        channel.sendSecure(byteArrayOf(if (success) 1.toByte() else 0.toByte()))
        return true
    }
}
