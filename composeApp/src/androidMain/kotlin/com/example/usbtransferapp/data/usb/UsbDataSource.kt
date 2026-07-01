package com.example.usbtransferapp.data.usb

import android.util.Log
import com.example.usbtransferapp.data.Packet
import com.example.usbtransferapp.data.crypto.CryptoManager
import com.example.usbtransferapp.data.crypto.KeyExchangeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UsbDataSource"

@Singleton
class UsbDataSource @Inject constructor(
    private val manager: IUsbConnection,
    private val keyExchange: KeyExchangeManager,
    private val crypto: CryptoManager
) {

    private var aesKey: SecretKey? = null

    // Optimized circular/sliding buffer to prevent O(N^2) array copies and GC pressure
    private var leftoverBuffer = ByteArray(1024 * 1024 * 5) // 5MB buffer
    private var bufferHead = 0
    private var bufferTail = 0

    private fun availableBytes() = bufferTail - bufferHead

    private fun compactBuffer() {
        if (bufferHead > 0) {
            val len = availableBytes()
            System.arraycopy(leftoverBuffer, bufferHead, leftoverBuffer, 0, len)
            bufferTail = len
            bufferHead = 0
        }
    }

    private suspend fun receivePacket(): Packet.PacketData? = withContext(Dispatchers.IO) {
        while (availableBytes() < Packet.HEADER_SIZE) {
            compactBuffer()
            // Read in larger chunks (256KB) to reduce JNI calls
            val rawData = manager.receive(256 * 1024) ?: run {
                Log.e(TAG, "receivePacket: Failed to read from USB stream")
                return@withContext null
            }
            if (bufferTail + rawData.size > leftoverBuffer.size) {
                // Expand buffer if needed (should be rare with 5MB)
                val newBuffer = ByteArray(leftoverBuffer.size * 2)
                System.arraycopy(leftoverBuffer, bufferHead, newBuffer, 0, availableBytes())
                leftoverBuffer = newBuffer
                bufferTail = availableBytes()
                bufferHead = 0
            }
            System.arraycopy(rawData, 0, leftoverBuffer, bufferTail, rawData.size)
            bufferTail += rawData.size
        }

        val bb = ByteBuffer.wrap(leftoverBuffer, bufferHead, availableBytes())
        val type = bb.get()
        val length = bb.int

        if (length < 0 || length > 1024 * 1024 * 10) { // 10MB limit
            Log.e(TAG, "receivePacket: Invalid packet length: $length")
            return@withContext null
        }

        while (availableBytes() < Packet.HEADER_SIZE + length) {
            compactBuffer()
            val rawData = manager.receive(256 * 1024) ?: run {
                Log.e(TAG, "receivePacket: Failed to read full payload from USB stream")
                return@withContext null
            }
            if (bufferTail + rawData.size > leftoverBuffer.size) {
                val newBuffer = ByteArray(leftoverBuffer.size * 2)
                System.arraycopy(leftoverBuffer, bufferHead, newBuffer, 0, availableBytes())
                leftoverBuffer = newBuffer
                bufferTail = availableBytes()
                bufferHead = 0
            }
            System.arraycopy(rawData, 0, leftoverBuffer, bufferTail, rawData.size)
            bufferTail += rawData.size
        }

        val payload = ByteArray(length)
        System.arraycopy(leftoverBuffer, bufferHead + Packet.HEADER_SIZE, payload, 0, length)
        
        bufferHead += Packet.HEADER_SIZE + length

        Packet.PacketData(type, length, payload)
    }

    suspend fun performHandshake(): Boolean = withContext(Dispatchers.IO) {
        bufferHead = 0
        bufferTail = 0
        Log.i(TAG, "Handshake: STARTING")
        val keyPair = keyExchange.generateKeyPair()

        // 1. Wait for Desktop Public Key
        Log.d(TAG, "Handshake: Step 1 - Waiting for Desktop Public Key...")
        val packet = receivePacket() ?: run {
            Log.e(TAG, "Handshake: Failed to receive Desktop Public Key")
            return@withContext false
        }
        
        if (packet.type != Packet.TYPE_PUBLIC_KEY) {
            Log.e(TAG, "Handshake: Expected TYPE_PUBLIC_KEY (0x01), got ${packet.type}")
            return@withContext false
        }
        
        val remotePub = try {
            keyExchange.bytesToPublicKey(packet.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Handshake: Failed to parse remote public key", e)
            return@withContext false
        }
        Log.d(TAG, "Handshake: Desktop Public Key received (${packet.payload.size} bytes)")

        // 2. Send our Public Key
        Log.d(TAG, "Handshake: Step 2 - Sending Android Public Key...")
        val pubBytes = keyExchange.publicKeyToBytes(keyPair.public)
        val sendResult = manager.send(Packet.build(Packet.TYPE_PUBLIC_KEY, pubBytes))
        if (sendResult <= 0) {
            Log.e(TAG, "Handshake: Failed to send Android Public Key")
            return@withContext false
        }

        // 3. Derive Secret
        Log.d(TAG, "Handshake: Step 3 - Deriving shared secret...")
        val secret = keyExchange.sharedSecret(keyPair.private, remotePub)
        aesKey = crypto.generateAESKey(secret)
        
        Log.i(TAG, "Handshake: SUCCESS. Secure channel established.")
        true
    }

    // ... (rest of the file remains the same)
    suspend fun sendSecure(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val key = aesKey ?: return@withContext false
        val (iv, encrypted) = crypto.encrypt(data, key)

        val payload = ByteBuffer.allocate(iv.size + encrypted.size)
            .put(iv)
            .put(encrypted)
            .array()

        manager.send(Packet.build(Packet.TYPE_DATA, payload)) > 0
    }

    suspend fun receiveSecure(): ByteArray? = withContext(Dispatchers.IO) {
        val key = aesKey ?: return@withContext null
        val packet = receivePacket() ?: return@withContext null
        if (packet.type != Packet.TYPE_DATA) return@withContext null

        val payload = packet.payload
        if (payload.size < 12) return@withContext null

        val iv = payload.copyOfRange(0, 12)
        val encrypted = payload.copyOfRange(12, payload.size)

        try {
            crypto.decrypt(iv, encrypted, key)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }
}
