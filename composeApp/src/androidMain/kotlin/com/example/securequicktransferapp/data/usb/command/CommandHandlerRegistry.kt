package com.example.securequicktransferapp.data.usb.command

import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.domain.command.AppCommandRegistry
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CommandHandlerRegistry"

/**
 * Maps application command byte IDs to their [ICommandHandler] implementations.
 *
 * **SOLID principles applied:**
 * - **OCP**: The registry never needs modification. Registering a new command requires
 *   only a new `@Binds @IntoSet` entry in [CommandHandlerModule].
 * - **DIP**: Handlers are provided by Hilt DI as a `Set<ICommandHandler>` —
 *   this class depends on the abstraction, never on concrete handler types.
 *
 * Hilt populates [handlers] via multibinding. Every handler annotated with
 * `@Binds @IntoSet` in [CommandHandlerModule] is automatically included.
 */
@Singleton
class CommandHandlerRegistry @Inject constructor(
    handlers: Set<@JvmSuppressWildcards ICommandHandler>,
    private val logger: UsbLogger
) {
    private val handlerMap: Map<Byte, ICommandHandler> = handlers.associateBy { it.commandId }

    init {
        if (handlerMap.size != handlers.size) {
            logger.e(TAG, "Duplicate command IDs detected among registered handlers! " +
                "Ensure every ICommandHandler has a unique commandId.")
        }
    }

    /**
     * Returns the [ICommandHandler] for [commandId], or `null` if none is registered.
     * [AndroidClientCommandReceiver] logs a warning and skips the command when `null` is returned.
     */
    fun handlerFor(commandId: Byte): ICommandHandler? = handlerMap[commandId]

    /** Logs all registered handlers at debug level. Call once at command-loop startup. */
    fun logRegistered() {
        val entries = handlerMap.entries.joinToString { (id, handler) ->
            "0x${id.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase()}" +
            "=${AppCommandRegistry.nameFor(id)}→${handler::class.simpleName}"
        }
        logger.d(TAG, "Registered ${handlerMap.size} handlers: [$entries]")
    }
}
