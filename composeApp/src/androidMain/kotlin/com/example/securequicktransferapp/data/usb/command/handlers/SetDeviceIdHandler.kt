package com.example.securequicktransferapp.data.usb.command.handlers

import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.domain.command.AppCommandIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SetDeviceIdHandler"

/**
 * Handles [AppCommandIds.CMD_SET_DEVICE_ID]: receives the host-assigned device ID string
 * and forwards it to the UI via [CommandContext.onSetDeviceId].
 *
 * Wire format received: `[4: idLen][idLen bytes: deviceId]`
 * Wire format sent:     (none — no response expected)
 */
@Singleton
class SetDeviceIdHandler @Inject constructor(
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_SET_DEVICE_ID

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val deviceId = CommandPayloadParser.readString(payload)

        usbLogger.d(TAG, "Received Host Device ID: '$deviceId'")
        withContext(Dispatchers.Main) { cmdCtx.onSetDeviceId?.invoke(deviceId) }
        return true
    }
}
