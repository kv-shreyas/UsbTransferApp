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

private const val TAG = "CreateFolderHandler"

/**
 * Handles [AppCommandIds.CMD_CREATE_FOLDER]: creates a new directory on the Android device.
 *
 * Wire format received: `[4: pathLen][pathLen bytes: folderPath]`
 * Wire format sent:     `[1: success]`
 */
@Singleton
class CreateFolderHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_CREATE_FOLDER

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val folderPath = CommandPayloadParser.readString(payload)

        usbLogger.d(TAG, "Creating folder '$folderPath'")
        val success = if (SmartNavStorageResolver.isSmartNavPath(folderPath)) {
            SmartNavStorageResolver.createDirectory(context, folderPath)
        } else {
            val file = AndroidFilePathResolver.resolve(folderPath)
            try {
                if (!file.exists()) file.mkdirs() else false
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error creating folder '$folderPath'", e)
                false
            }
        }

        usbLogger.d(TAG, "CreateFolder result: success=$success")
        channel.sendSecure(byteArrayOf(if (success) 1.toByte() else 0.toByte()))
        return true
    }
}
