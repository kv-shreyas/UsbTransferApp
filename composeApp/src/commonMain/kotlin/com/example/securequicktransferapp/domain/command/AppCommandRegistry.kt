package com.example.securequicktransferapp.domain.command

/**
 * Maps application command byte IDs to human-readable names for logging and debugging.
 *
 * Acts as living protocol documentation: every command in [AppCommandIds] should have
 * a corresponding entry here. Unknown bytes are gracefully formatted as hex strings
 * rather than causing a crash.
 *
 * When a new [AppCommandIds] constant is added, its name is registered here so it
 * appears correctly in all log output across both Android and Desktop.
 */
object AppCommandRegistry {

    private val commandNames: Map<Byte, String> = buildMap {
        put(AppCommandIds.CMD_LIST_DIR,            "CMD_LIST_DIR")
        put(AppCommandIds.CMD_SEND_FILE_START,     "CMD_SEND_FILE_START")
        put(AppCommandIds.CMD_FETCH_FILE_START,    "CMD_FETCH_FILE_START")
        put(AppCommandIds.CMD_FETCH_DIR_ZIP_START, "CMD_FETCH_DIR_ZIP_START")
        put(AppCommandIds.CMD_DISCONNECT,          "CMD_DISCONNECT")
        put(AppCommandIds.CMD_SEND_DIR_ZIP_START,  "CMD_SEND_DIR_ZIP_START")
        put(AppCommandIds.CMD_DELETE_FILE,         "CMD_DELETE_FILE")
        put(AppCommandIds.CMD_RENAME_FILE,         "CMD_RENAME_FILE")
        put(AppCommandIds.CMD_CREATE_FOLDER,       "CMD_CREATE_FOLDER")
        put(AppCommandIds.CMD_SET_DEVICE_ID,       "CMD_SET_DEVICE_ID")
        put(AppCommandIds.CMD_GET_APP_VERSION,     "CMD_GET_APP_VERSION")
    }

    /**
     * Returns the human-readable name for [commandId], or a hex fallback
     * (e.g. `"UNKNOWN(0x10)"`) if the command is not registered.
     */
    fun nameFor(commandId: Byte): String =
        commandNames[commandId]
            ?: "UNKNOWN(0x${commandId.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase()})"

    /** Returns `true` if [commandId] is a known, registered command. */
    fun isKnown(commandId: Byte): Boolean = commandNames.containsKey(commandId)
}
