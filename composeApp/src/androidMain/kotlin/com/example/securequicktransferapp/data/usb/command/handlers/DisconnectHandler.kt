package com.example.securequicktransferapp.data.usb.command.handlers

import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.domain.command.AppCommandIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DisconnectHandler"

/**
 * Handles [AppCommandIds.CMD_DISCONNECT]: signals the command loop to stop gracefully.
 *
 * Wire format received: `(no payload)`
 * Wire format sent:     (none — connection is being terminated)
 *
 * This is the only handler that returns `false` — which instructs [AndroidClientCommandReceiver]
 * to exit the receive loop and clean up the session.
 */
@Singleton
class DisconnectHandler @Inject constructor(
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_DISCONNECT

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        usbLogger.d(TAG, "Received DISCONNECT — stopping command loop")
        withContext(Dispatchers.Main) { cmdCtx.onDisconnectReceived() }
        return false // Signal command loop to exit
    }
}
