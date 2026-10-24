package com.example.securequicktransferapp.data.usb.command

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.domain.command.AppCommandRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AndroidClientCommandReceiver"

/**
 * Android Client Command Receiver: Listens on the secure USB channel when the Android
 * device acts as the USB Client (receiver), parsing incoming command packets and
 * dispatching each command to its registered [ICommandHandler].
 *
 * Receives and processes commands sent from [com.example.securequicktransferapp.data.repo.DesktopHostCommandSender] (Desktop Host).
 *
 * **SOLID principles:**
 * - **SRP**: Only responsible for the receive loop and dispatch — not for any command business logic.
 * - **OCP**: Adding a new command never requires modifying this class; only a new handler
 *   class + a `@Binds @IntoSet` registration in [com.example.securequicktransferapp.di.CommandHandlerModule].
 * - **DIP**: Depends on [CommandHandlerRegistry] and [ICommandHandler] abstractions.
 *
 * Wire payload parsing is delegated to [CommandPayloadParser].
 */
@Singleton
class AndroidClientCommandReceiver @Inject constructor(
    private val channel: SecureChannel,
    private val registry: CommandHandlerRegistry,
    private val logger: UsbLogger
) {
    /**
     * Starts the secure command receive loop. Suspends until the loop exits (disconnect
     * command received, connection lost, or coroutine cancelled).
     *
     * The loop:
     * 1. Sends a brief burst of PING packets to signal readiness to the host.
     * 2. Waits for incoming encrypted command packets.
     * 3. Parses each packet with [CommandPayloadParser].
     * 4. Dispatches to the registered [ICommandHandler] via [CommandHandlerRegistry].
     * 5. Exits when a handler returns `false` or the connection is lost.
     */
    suspend fun startListening(
        onReceiveStarted: (String, Long) -> Unit = { _, _ -> },
        onReceiveProgress: (Float) -> Unit = {},
        onReceiveFinished: () -> Unit = {},
        onReceiveCancelled: () -> Unit = {},
        onReceiveError: (String) -> Unit = {},
        onDisconnectReceived: () -> Unit = {},
        onSetDeviceId: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        registry.logRegistered()
        logger.d(TAG, "Command receive loop started")

        val cmdCtx = CommandContext(
            onReceiveStarted    = onReceiveStarted,
            onReceiveProgress   = onReceiveProgress,
            onReceiveFinished   = onReceiveFinished,
            onReceiveCancelled  = onReceiveCancelled,
            onReceiveError      = onReceiveError,
            onDisconnectReceived = onDisconnectReceived,
            onSetDeviceId       = onSetDeviceId
        )

        // Send a brief burst of PING packets so the Desktop knows the client is ready
        val ackJob = launch {
            repeat(4) {
                try { channel.sendRawPacket(SecureQtSdk.Packets.TYPE_PING, ByteArray(0)) }
                catch (_: Exception) { return@launch }
                delay(150.milliseconds)
            }
        }

        try {
            while (isActive) {
                // Receive next encrypted command payload (blocks until data arrives)
                val raw = try {
                    channel.receiveSecure() ?: break
                } catch (e: SecureChannel.TransferCancelledException) {
                    logger.w(TAG, "Transfer cancelled by remote — resuming command loop")
                    withContext(Dispatchers.Main) { onReceiveCancelled() }
                    continue
                }

                ackJob.cancel() // Stop pinging once real commands start arriving

                // Parse: extract commandId + slice payload ByteBuffer (zero-copy)
                val command = CommandPayloadParser.parse(raw) ?: continue
                val cmdName = AppCommandRegistry.nameFor(command.commandId)
                logger.d(TAG, "Dispatching $cmdName (0x${command.commandId.toInt().and(0xFF).toString(16).padStart(2,'0').uppercase()})")

                // Dispatch to the registered handler
                val handler = registry.handlerFor(command.commandId)
                if (handler == null) {
                    logger.e(TAG, "No handler registered for $cmdName — ignoring")
                    continue
                }

                val shouldContinue: Boolean = try {
                    handler.handle(command.payload, cmdCtx)
                } catch (e: SecureChannel.TransferCancelledException) {
                    logger.w(TAG, "${handler::class.simpleName}: cancelled by remote")
                    withContext(Dispatchers.Main) { onReceiveCancelled() }
                    true
                } catch (e: Exception) {
                    logger.e(TAG, "${handler::class.simpleName}: unhandled error", e)
                    withContext(Dispatchers.Main) { onReceiveError(e.message ?: "Unknown error") }
                    true
                }

                if (!shouldContinue) break
            }
        } catch (e: Exception) {
            logger.e(TAG, "Command loop fatal error", e)
        }

        logger.d(TAG, "Command receive loop stopped")
    }
}
