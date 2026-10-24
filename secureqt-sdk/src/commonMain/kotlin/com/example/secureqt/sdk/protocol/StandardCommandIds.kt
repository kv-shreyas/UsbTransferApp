package com.example.secureqt.sdk.protocol

/**
 * Standard command identifiers for the SecureQT file-transfer and management protocol.
 * Application-specific custom commands can be defined in application modules (e.g. `AppCommandIds`).
 */
object StandardCommandIds {
    const val CMD_LIST_DIR: Byte = 0x00
    const val CMD_SEND_FILE_START: Byte = 0x01
    const val CMD_FETCH_FILE_START: Byte = 0x02
    const val CMD_FETCH_DIR_ZIP_START: Byte = 0x03
    const val CMD_DISCONNECT: Byte = 0x04
    const val CMD_SEND_DIR_ZIP_START: Byte = 0x05
    const val CMD_DELETE_FILE: Byte = 0x06
    const val CMD_RENAME_FILE: Byte = 0x07
    const val CMD_CREATE_FOLDER: Byte = 0x08
    const val CMD_SET_DEVICE_ID: Byte = 0x09
}
