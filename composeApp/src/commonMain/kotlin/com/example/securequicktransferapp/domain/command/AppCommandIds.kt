package com.example.securequicktransferapp.domain.command

import com.example.secureqt.sdk.protocol.StandardCommandIds

/**
 * Single source of truth for all application-level command byte identifiers.
 *
 * **Architecture separation:**
 * - Core file-management command constants delegate to [StandardCommandIds] defined in the SDK.
 * - Application-level custom extensions (e.g. `CMD_GET_APP_VERSION = 0x10`) are defined directly here.
 *
 * **Adding a new custom command:**
 * 1. Define its ID here (e.g. `const val CMD_GET_APP_VERSION: Byte = 0x10`).
 * 2. Add its name in [AppCommandRegistry].
 * 3. Android (client): implement [com.example.securequicktransferapp.data.usb.command.ICommandHandler]
 *    and register in [com.example.securequicktransferapp.di.CommandHandlerModule].
 * 4. Desktop (host): call `repo.executeCustomCommand(CMD_GET_APP_VERSION, payload)` on [com.example.securequicktransferapp.domain.repo.UsbRepository].
 */
object AppCommandIds {

    // ── Core file-management commands (standard SDK protocol) ─────────────────
    /** Request a directory listing from the remote device. */
    const val CMD_LIST_DIR: Byte = StandardCommandIds.CMD_LIST_DIR

    /** Initiate sending a file TO the remote device (host → client). */
    const val CMD_SEND_FILE_START: Byte = StandardCommandIds.CMD_SEND_FILE_START

    /** Request the remote device to send a file back (host pulls from client). */
    const val CMD_FETCH_FILE_START: Byte = StandardCommandIds.CMD_FETCH_FILE_START

    /** Request the remote device to zip and stream a directory back. */
    const val CMD_FETCH_DIR_ZIP_START: Byte = StandardCommandIds.CMD_FETCH_DIR_ZIP_START

    /** Cleanly terminate the session. */
    const val CMD_DISCONNECT: Byte = StandardCommandIds.CMD_DISCONNECT

    /** Initiate sending a directory (as ZIP stream) TO the remote device. */
    const val CMD_SEND_DIR_ZIP_START: Byte = StandardCommandIds.CMD_SEND_DIR_ZIP_START

    /** Delete a file or directory tree on the remote device. */
    const val CMD_DELETE_FILE: Byte = StandardCommandIds.CMD_DELETE_FILE

    /** Rename a file or directory on the remote device. */
    const val CMD_RENAME_FILE: Byte = StandardCommandIds.CMD_RENAME_FILE

    /** Create a new directory on the remote device. */
    const val CMD_CREATE_FOLDER: Byte = StandardCommandIds.CMD_CREATE_FOLDER

    /** Push the host-assigned device identifier string to the client. */
    const val CMD_SET_DEVICE_ID: Byte = StandardCommandIds.CMD_SET_DEVICE_ID

    // ── Application-specific custom extensions (0x10–0x1F) ────────────────────
    /** Retrieve application version information from client. */
    const val CMD_GET_APP_VERSION: Byte = 0x10
}
