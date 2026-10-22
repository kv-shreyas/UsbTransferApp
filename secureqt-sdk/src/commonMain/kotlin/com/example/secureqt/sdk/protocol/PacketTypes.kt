package com.example.secureqt.sdk.protocol

/**
 * Single source of truth for Packet type bytes.
 * These are used in the 5-byte header of the custom protocol packet.
 */
object PacketTypes {
    /** First packet sent, containing the unencrypted ECDH public key */
    const val TYPE_PUBLIC_KEY: Byte = 0x01
    
    /** Encrypted data payload */
    const val TYPE_DATA: Byte = 0x02
    
    /** Control packet indicating completion of a transfer stream */
    const val TYPE_EOF: Byte = 0x03
    
    /** Heartbeat or keep-alive packet (optional/future use) */
    const val TYPE_PING: Byte = 0x04
    
    /** Control packet indicating an error occurred during transfer */
    const val TYPE_ERROR: Byte = 0x05
    
    /** Control packet indicating transfer was cancelled by user */
    const val TYPE_CANCEL: Byte = 0x06
}
