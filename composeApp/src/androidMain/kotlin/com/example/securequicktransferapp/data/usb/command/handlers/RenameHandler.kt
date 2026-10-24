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
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RenameHandler"

/**
 * Handles [AppCommandIds.CMD_RENAME_FILE]: renames a file or directory on the Android device.
 *
 * Wire format received: `[4: oldPathLen][oldPath bytes][4: newNameLen][newName bytes]`
 * Wire format sent:     `[1: success]`
 */
@Singleton
class RenameHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_RENAME_FILE

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val (oldPath, newName) = CommandPayloadParser.readTwoStrings(payload)

        usbLogger.d(TAG, "Renaming '$oldPath' -> '$newName'")
        val success = if (SmartNavStorageResolver.isSmartNavPath(oldPath)) {
            SmartNavStorageResolver.rename(context, oldPath, newName)
        } else {
            val file = AndroidFilePathResolver.resolve(oldPath)
            try {
                if (file.exists()) file.renameTo(File(file.parentFile, newName)) else false
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error renaming '$oldPath'", e)
                false
            }
        }

        usbLogger.d(TAG, "Rename result: success=$success")
        channel.sendSecure(byteArrayOf(if (success) 1.toByte() else 0.toByte()))
        return true
    }
}
