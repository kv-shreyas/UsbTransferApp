package com.example.secureqt.sdk

import com.example.secureqt.sdk.crypto.SecureSession
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.secureqt.sdk.protocol.CommandPayloadBuilder
import com.example.secureqt.sdk.protocol.PacketCodec
import com.example.secureqt.sdk.protocol.PacketTypes
import com.example.secureqt.sdk.protocol.StandardCommandIds
import com.example.secureqt.sdk.buffer.SlidingWindowBuffer
import com.example.secureqt.sdk.util.FormatUtils
import com.example.secureqt.sdk.transport.IUsbTransport
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.client.ISecureTransferClient
import com.example.secureqt.sdk.client.SecureTransferClientImpl

/**
 * Main entry point for the SecureQT SDK.
 * Exposes core transport and crypto components for building secure KMP applications.
 *
 * **Core components:**
 * - Packet framing: [Codec] / [Packets]
 * - ECDH key exchange + AES-GCM encryption: [createSession]
 * - Full secure channel pipeline: [createChannel]
 * - Command wire serialization/deserialization: [CommandPayloadBuilder] / [CommandPayloadParser]
 * - Standard file transfer client: [createTransferClient]
 */
object SecureQtSdk {
    const val VERSION = "1.0.0"

    fun createSession() = SecureSession()
    fun createBuffer() = SlidingWindowBuffer()

    fun createChannel(transport: IUsbTransport): SecureChannel = SecureChannel(transport)

    fun createTransferClient(channel: SecureChannel): ISecureTransferClient =
        SecureTransferClientImpl(channel)

    /** Wire-framing packet type constants (outer packet header layer). */
    val Packets = PacketTypes

    /** Packet encode / decode utilities (outer packet header layer). */
    val Codec = PacketCodec

    /**
     * Parses a decrypted byte array into a [com.example.secureqt.sdk.protocol.ReceivedCommand].
     * Extracts commandId and provides typed readers for payload arguments.
     */
    val CommandPayloadParser = com.example.secureqt.sdk.protocol.CommandPayloadParser

    /**
     * Builds application-layer command payloads for sending.
     * Provides typed builders for simple, string, file, and custom payloads.
     */
    val CommandPayloadBuilder = com.example.secureqt.sdk.protocol.CommandPayloadBuilder

    /** Standard baseline command identifiers for file transfer operations. */
    val StandardCommands = StandardCommandIds

    /** Miscellaneous formatting utilities. */
    val Utils = FormatUtils
}
