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

    /**
     * Reads a full packet by reading the available stream data.
     * Improved to handle cases where header and payload arrive in the same USB frame.
     */
    private suspend fun receivePacket(): Packet.PacketData? = withContext(Dispatchers.IO) {
        Log.d(TAG, "receivePacket: Waiting for data...")
        
        // Read a large chunk (USB bulk packets are typically 512 or 1024 bytes)
        val rawData = manager.receive(16384) ?: run {
            Log.e(TAG, "receivePacket: Failed to read from USB stream (null response)")
            return@withContext null
        }

        if (rawData.size < Packet.HEADER_SIZE) {
            Log.e(TAG, "receivePacket: Received incomplete header (${rawData.size} bytes)")
            return@withContext null
        }

        val bb = ByteBuffer.wrap(rawData)
        val type = bb.get()
        val length = bb.int

        Log.d(TAG, "receivePacket: Detected packet Type=$type, Length=$length")

        if (length < 0 || length > 1024 * 1024) {
            Log.e(TAG, "receivePacket: Invalid packet length: $length")
            return@withContext null
        }

        // Extract payload from the same buffer if possible
        val payload = if (length > 0) {
            val remaining = rawData.size - Packet.HEADER_SIZE
            if (remaining >= length) {
                // Entire payload was in the first read
                rawData.copyOfRange(Packet.HEADER_SIZE, Packet.HEADER_SIZE + length)
            } else {
                Log.w(TAG, "receivePacket: Payload fragmented. Need $length, got $remaining. Reading more...")
                // If fragmented (rare for small handshake packets), read the rest
                val rest = manager.receiveExact(length - remaining) ?: return@withContext null
                val combined = ByteArray(length)
                System.arraycopy(rawData, Packet.HEADER_SIZE, combined, 0, remaining)
                System.arraycopy(rest, 0, combined, remaining, length - remaining)
                combined
            }
        } else {
            ByteArray(0)
        }

        Packet.PacketData(type, length, payload)
    }

    suspend fun performHandshake(): Boolean = withContext(Dispatchers.IO) {
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
