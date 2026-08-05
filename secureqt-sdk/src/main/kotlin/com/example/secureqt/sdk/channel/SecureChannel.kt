package com.example.secureqt.sdk.channel

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.crypto.SecureSession
import com.example.secureqt.sdk.transport.IUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * Encapsulates the entire secure communication protocol over an abstract USB transport.
 * Handles framing, buffering, handshakes, and encryption/decryption.
 * 
 * Provides a unified single-source-of-truth for both Android and Desktop clients.
 */
class SecureChannel(private val transport: IUsbTransport) {
    
    private val session = SecureSession()
    private val rxBuffer = SecureQtSdk.createBuffer()
    private val sendMutex = Mutex()
    private val readBuffer = ByteArray(256 * 1024)

    /**
     * Internal exception thrown when a transfer cancellation packet is received.
     */
    class TransferCancelledException : Exception("Transfer cancelled by remote peer")

    /**
     * Reads a raw framed packet from the transport.
     * Blocks/suspends until a complete packet (header + payload) is received.
     */
    suspend fun receiveRawPacket(): Pair<Byte, ByteArray>? = withContext(Dispatchers.IO) {
        // Wait for 5-byte header
        while (!rxBuffer.has(SecureQtSdk.Codec.HEADER_SIZE)) {
            val bytesRead = transport.read(readBuffer)
            if (bytesRead <= 0) {
                if (!transport.isConnected() || !coroutineContext.isActive) return@withContext null
                if (bytesRead == 0) yield() else delay(5) // Reduced delay for higher throughput
                continue
            }
            rxBuffer.append(readBuffer, 0, bytesRead)
        }

        val header = rxBuffer.take(SecureQtSdk.Codec.HEADER_SIZE)
        val type = SecureQtSdk.Codec.decodeType(header)
        val length = SecureQtSdk.Codec.decodePayloadLength(header)

        if (length < 0 || length > 1024 * 1024 * 10) { // 10MB sanity limit
            return@withContext null
        }

        // Wait for payload
        while (!rxBuffer.has(length)) {
            val bytesRead = transport.read(readBuffer)
            if (bytesRead <= 0) {
                if (!transport.isConnected() || !coroutineContext.isActive) return@withContext null
                if (bytesRead == 0) yield() else delay(5)
                continue
            }
            rxBuffer.append(readBuffer, 0, bytesRead)
        }

        val payload = rxBuffer.take(length)
        Pair(type, payload)
    }

    /**
     * Sends a raw framed packet over the transport.
     */
    suspend fun sendRawPacket(type: Byte, payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val packet = SecureQtSdk.Codec.encode(type, payload)
            transport.write(packet)
        }
    }

    /**
     * Sends a pre-encoded packet (useful for zero-copy bulk encrypted transfers).
     */
    suspend fun sendPrebuiltPacket(packet: ByteArray): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            transport.write(packet)
        }
    }

    /**
     * Executes the ECDH handshake over the transport.
     * @param isInitiator True if this side initiates the handshake (sends its public key first).
     *                    False if this side waits for the remote public key first.
     */
    suspend fun performHandshake(isInitiator: Boolean): Boolean = withContext(Dispatchers.IO) {
        rxBuffer.clear()
        try { transport.clearBuffer() } catch (_: Exception) {}
        session.reset()

        if (isInitiator) {
            // Initiator: Send our public key first, then wait for responder
            val pubBytes = session.getPublicKey()
            if (!sendRawPacket(SecureQtSdk.Packets.TYPE_PUBLIC_KEY, pubBytes)) return@withContext false

            val remotePacket = waitForPublicKey() ?: return@withContext false
            return@withContext tryDeriveSecret(remotePacket.second)
        } else {
            // Responder: Wait for initiator's public key first, then send ours
            val remotePacket = waitForPublicKey() ?: return@withContext false
            val pubBytes = session.getPublicKey()
            if (!sendRawPacket(SecureQtSdk.Packets.TYPE_PUBLIC_KEY, pubBytes)) return@withContext false
            
            return@withContext tryDeriveSecret(remotePacket.second)
        }
    }

    private suspend fun waitForPublicKey(): Pair<Byte, ByteArray>? {
        for (i in 0 until 240) { // Try up to 60 seconds
            val packet = receiveRawPacket()
            if (packet == null) {
                delay(250)
                continue
            }
            if (packet.first == SecureQtSdk.Packets.TYPE_PUBLIC_KEY) return packet
        }
        return null
    }

    private fun tryDeriveSecret(remoteKey: ByteArray): Boolean {
        return try {
            session.deriveSharedSecret(remoteKey)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Encrypts and wraps data into a zero-copy data packet without sending it.
     * Useful for parallel chunk encryption workers.
     */
    fun encryptAndWrapData(data: ByteArray): ByteArray? {
        if (!session.isReady()) return null
        val encrypted = session.encrypt(data)
        return SecureQtSdk.Codec.encodeDataPacket(encrypted.iv, encrypted.ciphertext)
    }

    /**
     * Sends data securely over the channel.
     */
    suspend fun sendSecure(data: ByteArray): Boolean {
        val packet = encryptAndWrapData(data) ?: return false
        return sendPrebuiltPacket(packet)
    }

    /**
     * Receives and decrypts the next secure data packet.
     * @throws TransferCancelledException if a CANCEL control packet is received.
     */
    suspend fun receiveSecure(): ByteArray? = withContext(Dispatchers.IO) {
        val packet = receiveRawPacket() ?: return@withContext null
        
        when (packet.first) {
            SecureQtSdk.Packets.TYPE_CANCEL -> throw TransferCancelledException()
            SecureQtSdk.Packets.TYPE_EOF -> return@withContext receiveSecure() // Ignore EOF in secure stream mode
            SecureQtSdk.Packets.TYPE_DATA -> return@withContext decryptData(packet.second)
            else -> return@withContext receiveSecure() // Ignore unknown types
        }
    }

    /**
     * Decrypts a raw payload.
     */
    fun decryptData(payload: ByteArray): ByteArray? {
        if (!session.isReady() || payload.size < 12) return null
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        return try {
            session.decrypt(iv, ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        session.reset()
        rxBuffer.clear()
    }
}
