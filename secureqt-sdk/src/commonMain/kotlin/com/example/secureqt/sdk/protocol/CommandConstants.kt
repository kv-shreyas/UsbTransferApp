package com.example.secureqt.sdk.protocol

/**
 * Single source of truth for all command bytes sent over the secure USB channel.
 * Replaces duplicated hardcoded constants across JVM and Android implementations.
 */
object CommandConstants {
    const val CMD_LIST_DIR: Byte = 0
    const val CMD_SEND_FILE_START: Byte = 1
    const val CMD_FETCH_FILE_START: Byte = 2
    const val CMD_FETCH_DIR_ZIP_START: Byte = 3
    const val CMD_DISCONNECT: Byte = 4
    const val CMD_SEND_DIR_ZIP_START: Byte = 5
    const val CMD_DELETE_FILE: Byte = 6
    const val CMD_RENAME_FILE: Byte = 7
    const val CMD_CREATE_FOLDER: Byte = 8
    const val CMD_SET_DEVICE_ID: Byte = 9
    
    // Transfer cancellation
    const val CMD_CANCEL_TRANSFER: Byte = 0x0F
}
