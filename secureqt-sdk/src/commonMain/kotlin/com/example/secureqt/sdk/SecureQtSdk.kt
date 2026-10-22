package com.example.secureqt.sdk

import com.example.secureqt.sdk.crypto.SecureSession
import com.example.secureqt.sdk.protocol.CommandConstants
import com.example.secureqt.sdk.protocol.PacketCodec
import com.example.secureqt.sdk.protocol.PacketTypes
import com.example.secureqt.sdk.buffer.SlidingWindowBuffer
import com.example.secureqt.sdk.util.FormatUtils

import com.example.secureqt.sdk.transport.IUsbTransport
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.client.ISecureTransferClient
import com.example.secureqt.sdk.client.SecureTransferClientImpl

/**
 * Main entry point for the SecureQT SDK.
 * Exposes core components for building secure KMP apps.
 */
object SecureQtSdk {
    const val VERSION = "1.0.0"
    
    fun createSession() = SecureSession()
    fun createBuffer() = SlidingWindowBuffer()
    
    fun createChannel(transport: IUsbTransport): SecureChannel {
        return SecureChannel(transport)
    }
    
    fun createTransferClient(channel: SecureChannel): ISecureTransferClient {
        return SecureTransferClientImpl(channel)
    }
    
    val Commands = CommandConstants
    val Packets = PacketTypes
    val Codec = PacketCodec
    val Utils = FormatUtils
}
