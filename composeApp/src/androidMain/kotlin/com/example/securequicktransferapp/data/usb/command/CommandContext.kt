package com.example.securequicktransferapp.data.usb.command

/**
 * Carries all UI-layer callbacks needed by command handlers during execution.
 *
 * Bundling callbacks into a single data class gives [ICommandHandler.handle] a stable,
 * clean signature. Adding a new callback requires updating only this class and the
 * single call-site in [AndroidClientCommandReceiver] — not every handler.
 *
 * @property onReceiveStarted   Called when a file/directory receive begins.
 *                              Args: (displayName, totalBytes).
 * @property onReceiveProgress  Called periodically with normalised progress in [0.0, 1.0].
 * @property onReceiveFinished  Called on successful receive completion.
 * @property onReceiveCancelled Called when the remote side cancels an in-progress transfer.
 * @property onReceiveError     Called on an unrecoverable error. Arg: error message string.
 * @property onDisconnectReceived Called when the host sends a disconnect command.
 * @property onSetDeviceId      Optional — invoked when the host pushes a device-ID string.
 */
data class CommandContext(
    val onReceiveStarted: (name: String, totalBytes: Long) -> Unit,
    val onReceiveProgress: (progress: Float) -> Unit,
    val onReceiveFinished: () -> Unit,
    val onReceiveCancelled: () -> Unit,
    val onReceiveError: (message: String) -> Unit,
    val onDisconnectReceived: () -> Unit,
    val onSetDeviceId: ((deviceId: String) -> Unit)?
)
