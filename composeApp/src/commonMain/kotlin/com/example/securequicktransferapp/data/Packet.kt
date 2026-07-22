package com.example.securequicktransferapp.data

import java.nio.ByteBuffer

object Packet {

    const val TYPE_PUBLIC_KEY: Byte = 0x01
    const val TYPE_DATA: Byte = 0x02
    const val TYPE_ACK: Byte = 0x03
    const val TYPE_CANCEL: Byte = 0x04
    const val TYPE_EOF: Byte = 0x05

    const val HEADER_SIZE = 5

    data class PacketData(val type: Byte, val length: Int, val payload: ByteArray)

    fun build(type: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.put(type)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun buildDataPacket(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + iv.size + ciphertext.size)
        buffer.put(TYPE_DATA)
        buffer.putInt(iv.size + ciphertext.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun parse(buffer: ByteArray): PacketData? {
        if (buffer.size < HEADER_SIZE) return null
        
        val bb = ByteBuffer.wrap(buffer)
        val type = bb.get()
        val length = bb.int
        
        if (length < 0 || length > buffer.size - HEADER_SIZE) return null
        
        val payload = ByteArray(length)
        bb.get(payload)
        return PacketData(type, length, payload)
    }
}