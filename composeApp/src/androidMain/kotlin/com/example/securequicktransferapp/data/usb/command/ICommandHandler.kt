package com.example.securequicktransferapp.data.usb.command

import java.nio.ByteBuffer

/**
 * Contract for all client-side USB command handlers.
 *
 * Each implementation handles exactly **one** command (Single Responsibility Principle).
 * New commands are added by creating a new class implementing this interface and
 * registering it via `@Binds @IntoSet` in [CommandHandlerModule] — no existing handler
 * or dispatcher code is ever modified (Open/Closed Principle).
 *
 * Implementations are injected into [CommandHandlerRegistry] by Hilt's multibinding,
 * satisfying the Dependency Inversion Principle.
 *
 * **Example — future `CMD_GET_APP_VERSION` command:**
 * ```kotlin
 * @Singleton
 * class GetAppVersionHandler @Inject constructor(
 *     private val channel: SecureChannel,
 *     private val logger: UsbLogger
 * ) : ICommandHandler {
 *     override val commandId = AppCommandIds.CMD_GET_APP_VERSION
 *
 *     override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
 *         val version = BuildConfig.VERSION_NAME.toByteArray(Charsets.UTF_8)
 *         // respond with: [4: versionLen][version bytes]
 *         val response = ByteBuffer.allocate(4 + version.size)
 *             .putInt(version.size).put(version).array()
 *         channel.sendSecure(response)
 *         return true  // continue the command loop
 *     }
 * }
 * // Then add in CommandHandlerModule:
 * // @Binds @IntoSet abstract fun bindGetAppVersionHandler(impl: GetAppVersionHandler): ICommandHandler
 * ```
 *
 * @property commandId The single byte this handler responds to. Must be unique across all
 *                     registered handlers. Defined in [AppCommandIds].
 */
interface ICommandHandler {
    val commandId: Byte

    /**
     * Executes the command logic.
     *
     * @param payload A [ByteBuffer] positioned **immediately after** the command byte,
     *                containing all command-specific arguments as produced by
     *                [CommandPayloadParser]. Handlers read directly from this buffer.
     * @param cmdCtx  UI callbacks for reporting progress and state. Handlers dispatch
     *                to Main thread as needed via `withContext(Dispatchers.Main)`.
     * @return `true` to keep the command loop running; `false` to stop it.
     *         Only [DisconnectHandler] should return `false`.
     */
    suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean
}
