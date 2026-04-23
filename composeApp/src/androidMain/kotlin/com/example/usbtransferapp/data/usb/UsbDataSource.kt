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

private const val TAG = "UsbDataSource"

class UsbDataSource @Inject constructor(
    private val manager: IUsbConnection,
    private val keyExchange: KeyExchangeManager,
    private val crypto: CryptoManager
) {

    private var aesKey: SecretKey? = null

    private suspend fun receivePacket(): Packet.PacketData? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to receive packet...")
        val header = manager.receiveExact(Packet.HEADER_SIZE) ?: run {
            Log.e(TAG, "Failed to receive packet header")
            return@withContext null
        }
        val bb = ByteBuffer.wrap(header)
        val type = bb.get()
        val length = bb.int

        if (length < 0 || length > 1024 * 1024) {
            Log.e(TAG, "Invalid packet length: $length")
            return@withContext null
        }

        val payload = if (length > 0) {
            manager.receiveExact(length) ?: run {
                Log.e(TAG, "Failed to receive payload of length $length")
                return@withContext null
            }
        } else {
            ByteArray(0)
        }

        Packet.PacketData(type, length, payload)
    }

    suspend fun performHandshake(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting handshake...")
        val keyPair = keyExchange.generateKeyPair()

        // 1. Wait for Desktop Public Key
        val packet = receivePacket() ?: return@withContext false
        if (packet.type != Packet.TYPE_PUBLIC_KEY) return@withContext false
        val remotePub = keyExchange.bytesToPublicKey(packet.payload)

        // 2. Send our Public Key
        val pubBytes = keyExchange.publicKeyToBytes(keyPair.public)
        manager.send(Packet.build(Packet.TYPE_PUBLIC_KEY, pubBytes))

        // 3. Derive Secret
        val secret = keyExchange.sharedSecret(keyPair.private, remotePub)
        aesKey = crypto.generateAESKey(secret)
        
        Log.d(TAG, "Handshake successful, AES key derived")
        true
    }

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
