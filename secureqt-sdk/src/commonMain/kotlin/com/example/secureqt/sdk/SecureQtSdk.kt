package com.example.secureqt.sdk

import com.example.secureqt.sdk.buffer.SlidingWindowBuffer
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.client.ISecureTransferClient
import com.example.secureqt.sdk.client.SecureTransferClientImpl
import com.example.secureqt.sdk.protocol.PacketCodec
import com.example.secureqt.sdk.protocol.PacketTypes
import com.example.secureqt.sdk.transport.IUsbTransport
import com.example.secureqt.sdk.util.FormatUtils

/**
 * Main public entry point for the SecureQT SDK.
 * Exposes core transport, cryptographic, and protocol components for building secure USB applications.
 *
 * Designed to be lightweight and simple to integrate into any Kotlin/Java project:
 *
 * ### Desktop Host Example (JVM):
 * ```kotlin
 * // 1. Create native transport using libusb DeviceHandle
 * val transport = SecureQtSdk.createDesktopTransport(handle, inEp, outEp, ifaceNum)
 *
 * // 2. Wrap in encrypted channel and perform ECDH handshake
 * val channel = SecureQtSdk.createChannel(transport)
 * channel.performHandshake(isInitiator = true)
 *
 * // 3. Instantiate high-level transfer client
 * val client = SecureQtSdk.createTransferClient(channel)
 * client.sendFile(File("photo.jpg"), "/sdcard/Download")
 * ```
 *
 * ### Android Client Example (Android Open Accessory):
 * ```kotlin
 * // 1. Create transport from UsbAccessory
 * val transport = SecureQtSdk.createAndroidAccessoryTransport(usbManager, accessory)
 *
 * // 2. Wrap in encrypted channel and perform ECDH handshake as responder
 * val channel = SecureQtSdk.createChannel(transport)
 * channel.performHandshake(isInitiator = false)
 *
 * // 3. Receive secure packets
 * val decryptedBytes = channel.receiveSecure()
 * ```
 */
object SecureQtSdk {
    const val VERSION = "1.0.0"

    /** Creates a circular sliding window buffer for assembling fragmented USB bulk packets. */
    fun createBuffer(): SlidingWindowBuffer = SlidingWindowBuffer()

    /** Creates an encrypted channel over the provided [IUsbTransport]. */
    fun createChannel(transport: IUsbTransport): SecureChannel = SecureChannel(transport)

    /** Creates a high-level transfer client for issuing remote file operations. */
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

    /** Miscellaneous formatting utilities. */
    val Utils = FormatUtils
}
