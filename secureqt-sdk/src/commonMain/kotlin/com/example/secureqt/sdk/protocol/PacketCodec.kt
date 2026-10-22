package com.example.secureqt.sdk.protocol

import java.nio.ByteBuffer

/**
 * Handles encoding and decoding of protocol packets.
 * 
 * Wire format:
 * [1 byte: type] [4 bytes: payload length N] [N bytes: payload]
 * 
 * Total size = 5 + N.
 * This codec replaces the duplicated `Packet` class logic.
 */
object PacketCodec {

    const val HEADER_SIZE = 5

    /**
     * Builds a full packet byte array for transmission over the wire.
     *
     * @param type    One of the constants from [PacketTypes]
     * @param payload The data payload (can be encrypted or unencrypted depending on type)
     * @return A complete byte array ready to be sent over USB
     */
    fun encode(type: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.put(type)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Extracts the payload length from a valid 5-byte header.
     * 
     * @param header A byte array of at least 5 bytes
     * @return The integer length of the subsequent payload
     */
    fun decodePayloadLength(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) throw IllegalArgumentException("Header must be at least 5 bytes")
        return ByteBuffer.wrap(header, 1, 4).int
    }
    
    /**
     * Extracts the type byte from a valid 5-byte header.
     */
    fun decodeType(header: ByteArray): Byte {
        if (header.isEmpty()) throw IllegalArgumentException("Header is empty")
        return header[0]
    }

    /**
     * Builds a complete DATA packet from separate IV and ciphertext arrays
     * in a **single allocation**, avoiding the intermediate `iv + ciphertext`
     * concatenation that would otherwise create a temporary ~256KB array.
     *
     * @param iv         The encryption IV (12 bytes for AES-GCM)
     * @param ciphertext The encrypted data
     * @return A complete byte array ready to be sent over USB
     */
    fun encodeDataPacket(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val payloadSize = iv.size + ciphertext.size
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payloadSize)
        buffer.put(PacketTypes.TYPE_DATA)
        buffer.putInt(payloadSize)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }
}
